package com.kiosk.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 语音识别管理器
 * 整合 UART/Audio/Video/Qwen3-ASR 通道，处理 ASR/NLP 结果
 * - UART: 唤醒/休眠/TTS 控制
 * - Audio: 接收 PCM 音频流
 * - Video: 接收视频流
 * - AsrWebSocket: 将 PCM 音频发送到 Qwen3-ASR WebSocket 获取识别文本
 */
public class SpeechManager {

    private static SpeechManager sInstance;

    private UartClient mUartClient;
    private AudioClient mAudioClient;
    private VideoClient mVideoClient;
    private AsrWebSocketClient mAsrWsClient;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SpeechCallback mCallback;

    // 当前状态
    private volatile boolean mIsWakeup = false;
    private volatile String mIatText = "";       // 当前识别文本 (来自 Qwen3-ASR)
    private volatile String mLastFinalIat = "";  // 上一次最终结果
    private volatile String mNlpResult = "";     // NLP 语义结果
    private volatile int mVadStatus = 0;

    // Qwen3-ASR 累积的完整文本（去重拼接）
    private final StringBuilder mAsrFullText = new StringBuilder();

    public interface SpeechCallback {
        void onWakeup();
        void onSleep();
        void onIatResult(String text, boolean isFinal);
        void onNlpResult(String text);
        void onVadChanged(int vadStatus);
        void onError(String message);
        /** 视频帧回调 */
        void onVideoFrame(byte[] frameData);
        /** 接口日志回调 */
        void onLog(String tag, String message);
    }

    private SpeechManager() {}

    public static synchronized SpeechManager getInstance() {
        if (sInstance == null) {
            sInstance = new SpeechManager();
        }
        return sInstance;
    }

    public void init(SpeechCallback callback) {
        this.mCallback = callback;

        // UART 通道 (ASR 服务器)
        mUartClient = new UartClient(new AiuiProtocol.UartCallback() {
            @Override
            public void onEvent(String eventType, int seqId, String jsonContent) {
                handleUartEvent(eventType, jsonContent);
            }

            @Override
            public void onConnectionState(boolean connected) {
                String msg = "UART " + (connected ? "已连接 " + AiuiProtocol.DEFAULT_LOCAL_IP + ":" + AiuiProtocol.UART_PORT :
                        "断开连接");
                UpdateLog.i("SpeechManager: " + msg);
                notifyLog("ASR", msg);
            }

            @Override
            public void onError(String message) {
                notifyError("UART: " + message);
                notifyLog("ASR", "错误: " + message);
            }
        });

        // 音频通道 (本机)
        mAudioClient = new AudioClient(new AiuiProtocol.AudioCallback() {
            @Override
            public void onAudioFrame(int vadStatus, int engineIndex, int frameIndex, byte[] pcmData) {
                if (engineIndex == 99) { // 盒子内部送给 AIUI 的音频
                    mVadStatus = vadStatus;
                    mHandler.post(() -> {
                        if (mCallback != null) mCallback.onVadChanged(vadStatus);
                    });
                    // 同时将 PCM 音频转发到 Qwen3-ASR WebSocket 进行识别
                    if (mAsrWsClient != null && pcmData != null && pcmData.length > 0) {
                        mAsrWsClient.sendAudio(pcmData);
                    }
                }
            }

            @Override
            public void onConnectionState(boolean connected) {
                String msg = "Audio " + (connected ? "已连接 " + AiuiProtocol.DEFAULT_LOCAL_IP + ":" + AiuiProtocol.AUDIO_PORT :
                        "断开连接");
                UpdateLog.i("SpeechManager: " + msg);
                notifyLog("音频", msg);
            }

            @Override
            public void onError(String message) {
                String msg = "Audio error: " + message;
                UpdateLog.e("SpeechManager: " + msg);
                notifyLog("音频", "错误: " + message);
            }
        });

        // 视频通道 (本机)
        mVideoClient = new VideoClient(new AiuiProtocol.VideoCallback() {
            @Override
            public void onVideoFrame(int frameIndex, byte[] frameData) {
                // 转发视频帧到上层显示
                final byte[] data = frameData;
                mHandler.post(() -> {
                    if (mCallback != null) mCallback.onVideoFrame(data);
                });
            }

            @Override
            public void onConnectionState(boolean connected) {
                String msg = "Video " + (connected ? "已连接 " + AiuiProtocol.DEFAULT_LOCAL_IP + ":" + AiuiProtocol.VIDEO_PORT :
                        "断开连接");
                UpdateLog.i("SpeechManager: " + msg);
                notifyLog("视频", msg);
            }

            @Override
            public void onError(String message) {
                String msg = "Video error: " + message;
                UpdateLog.e("SpeechManager: " + msg);
                notifyLog("视频", "错误: " + message);
            }
        });

        // Qwen3-ASR WebSocket 通道 (代替 UART 做语音识别)
        mAsrWsClient = new AsrWebSocketClient(new AsrWebSocketClient.AsrCallback() {
            @Override
            public void onResult(String text, boolean isFinal) {
                // Qwen3-ASR 返回增量文本，累积拼接
                if (text != null && !text.isEmpty()) {
                    mAsrFullText.append(text);
                }
                final String fullText = mAsrFullText.toString();
                mIatText = fullText;
                mHandler.post(() -> {
                    if (mCallback != null) mCallback.onIatResult(fullText, isFinal);
                });
            }

            @Override
            public void onReady() {
                UpdateLog.i("SpeechManager: Qwen3-ASR ready");
                notifyLog("QwenASR", "服务就绪，可接收音频");
            }

            @Override
            public void onConnectionState(boolean connected) {
                String msg = "QwenASR " + (connected ? "已连接 " + AsrWebSocketClient.DEFAULT_ASR_WS_URL :
                        "断开连接");
                UpdateLog.i("SpeechManager: " + msg);
                notifyLog("QwenASR", msg);
            }

            @Override
            public void onError(String message) {
                UpdateLog.e("SpeechManager: QwenASR error: " + message);
                notifyLog("QwenASR", "错误: " + message);
            }
        });

        UpdateLog.i("SpeechManager: initialized");
    }

    public void connect() {
        notifyLog("系统", "开始连接: UART=" + AiuiProtocol.DEFAULT_LOCAL_IP + ":" + AiuiProtocol.UART_PORT);
        notifyLog("系统", "开始连接: 音频=" + AiuiProtocol.DEFAULT_LOCAL_IP + ":" + AiuiProtocol.AUDIO_PORT);
        notifyLog("系统", "开始连接: 视频=" + AiuiProtocol.DEFAULT_LOCAL_IP + ":" + AiuiProtocol.VIDEO_PORT);
        notifyLog("系统", "开始连接: QwenASR=" + AsrWebSocketClient.DEFAULT_ASR_WS_URL);
        if (mUartClient != null) mUartClient.connect();
        if (mAudioClient != null) mAudioClient.connect();
        if (mVideoClient != null) mVideoClient.connect();
        if (mAsrWsClient != null) mAsrWsClient.connect();
    }

    public void disconnect() {
        if (mAsrWsClient != null) {
            mAsrWsClient.flush();
            mAsrWsClient.disconnect();
        }
        if (mUartClient != null) mUartClient.disconnect();
        if (mAudioClient != null) mAudioClient.disconnect();
        if (mVideoClient != null) mVideoClient.disconnect();
    }

    public void wakeup() {
        if (mUartClient != null) mUartClient.sendWakeup();
    }

    public void tts(String text) {
        if (mUartClient != null) mUartClient.sendTts(text);
    }

    public boolean isWakeup() {
        return mIsWakeup;
    }

    public String getIatText() {
        return mIatText;
    }

    public String getNlpResult() {
        return mNlpResult;
    }

    public String getLastFinalIat() {
        return mLastFinalIat;
    }

    // ========== 事件处理 ==========

    private void handleUartEvent(String eventType, String json) {
        try {
            JSONObject obj = new JSONObject(json);
            String content = obj.optString("content", "");

            switch (eventType) {
                case "EVENT_RESULT":
                case "3":
                    processResultEvent(content);
                    break;
                case "EVENT_WAKEUP":
                case "4":
                    mIsWakeup = true;
                    mIatText = "";
                    mNlpResult = "";
                    mAsrFullText.setLength(0);  // 清空 Qwen3-ASR 累积文本
                    mLastFinalIat = "";
                    mHandler.post(() -> {
                        if (mCallback != null) mCallback.onWakeup();
                    });
                    break;
                case "EVENT_SLEEP":
                case "5":
                    mIsWakeup = false;
                    // 休眠前 flush 剩余音频到 Qwen3-ASR
                    if (mAsrWsClient != null) mAsrWsClient.flush();
                    mAsrFullText.setLength(0);
                    mLastFinalIat = mIatText;
                    mHandler.post(() -> {
                        if (mCallback != null) mCallback.onSleep();
                    });
                    break;
                case "EVENT_VAD":
                case "6":
                    processVadEvent(content);
                    break;
                case "EVENT_ERROR":
                case "2":
                    processErrorEvent(content);
                    break;
                default:
                    // 兼容: 不带 eventType，从 content 内部解析
                    if (content != null && !content.isEmpty()) {
                        try {
                            JSONObject cObj = new JSONObject(content);
                            String sub = cObj.optString("eventType", "");
                            if (!sub.isEmpty()) {
                                handleUartEvent(sub, json);
                            }
                        } catch (Exception ignored) {}
                    }
                    break;
            }
        } catch (Exception e) {
            UpdateLog.e("SpeechManager: handleUartEvent error", e);
        }
    }

    private void processResultEvent(String content) {
        try {
            JSONObject cObj = new JSONObject(content);
            JSONArray data = cObj.optJSONArray("data");
            if (data == null) return;

            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                JSONObject params = item.optJSONObject("params");
                if (params == null) continue;

                String sub = params.optString("sub", "");

                // IAT 识别已由 Qwen3-ASR WebSocket 处理，此处跳过
                // 仅处理 NLP 语义结果
                if ("nlp".equals(sub) || "cbm_semantic".equals(sub)) {
                    processNlpResult(item);
                }
            }
        } catch (Exception e) {
            UpdateLog.e("SpeechManager: processResultEvent error", e);
        }
    }

    /**
     * 处理 NLP (语义理解) 结果
     */
    private void processNlpResult(JSONObject item) {
        try {
            StringBuilder result = new StringBuilder();

            // 尝试 GPT 流式格式
            String contentText = item.optString("content", "");
            if (!contentText.isEmpty() && contentText.startsWith("{")) {
                try {
                    JSONObject contentObj = new JSONObject(contentText);
                    if (contentObj.has("choices")) {
                        JSONArray choices = contentObj.optJSONArray("choices");
                        if (choices != null && choices.length() > 0) {
                            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                            if (delta != null) {
                                String r = delta.optString("content", "");
                                result.append(r);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 尝试 CBM Semantic 格式
            if (result.length() == 0) {
                String answer = item.optString("answer", "");
                if (!answer.isEmpty()) {
                    result.append(answer);
                } else {
                    String intent = item.optString("intent", "");
                    if (!intent.isEmpty()) {
                        result.append("[").append(intent).append("]");
                        String slot = item.optString("slot", "");
                        if (!slot.isEmpty()) result.append(" ").append(slot);
                    }
                }
            }

            mNlpResult = result.toString();
            final String nlpText = mNlpResult;

            mHandler.post(() -> {
                if (mCallback != null) mCallback.onNlpResult(nlpText);
            });
        } catch (Exception e) {
            UpdateLog.e("SpeechManager: processNlpResult error", e);
        }
    }

    private void processVadEvent(String content) {
        try {
            JSONObject cObj = new JSONObject(content);
            int vad = cObj.optInt("vad", cObj.optInt("status", -1));
            if (vad >= 0) {
                mVadStatus = vad;
                mHandler.post(() -> {
                    if (mCallback != null) mCallback.onVadChanged(vad);
                });
            }
        } catch (Exception ignored) {}
    }

    private void processErrorEvent(String content) {
        try {
            JSONObject cObj = new JSONObject(content);
            int code = cObj.optInt("code", 0);
            String msg = cObj.optString("msg", "");
            notifyError("AIUI Error " + code + ": " + msg);
        } catch (Exception ignored) {}
    }

    private void notifyError(final String msg) {
        mHandler.post(() -> {
            if (mCallback != null) mCallback.onError(msg);
        });
    }

    private void notifyLog(final String tag, final String msg) {
        mHandler.post(() -> {
            if (mCallback != null) mCallback.onLog(tag, msg);
        });
    }
}
