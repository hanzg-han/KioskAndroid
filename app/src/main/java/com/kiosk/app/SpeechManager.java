package com.kiosk.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 语音识别管理器
 * 整合 UART/Audio/Video 三个通道，处理 ASR/NLP 结果
 */
public class SpeechManager {

    private static SpeechManager sInstance;

    private UartClient mUartClient;
    private AudioClient mAudioClient;
    private VideoClient mVideoClient;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SpeechCallback mCallback;

    // 当前状态
    private volatile boolean mIsWakeup = false;
    private volatile String mIatText = "";       // 当前识别文本
    private volatile String mLastFinalIat = "";  // 上一次最终结果
    private volatile String mNlpResult = "";     // NLP 语义结果
    private volatile int mVadStatus = 0;

    // IAT PGS 支持
    private final String[] mIatPgsStack = new String[256];
    private int mIatPgsIndex = 0;

    public interface SpeechCallback {
        void onWakeup();
        void onSleep();
        void onIatResult(String text, boolean isFinal);
        void onNlpResult(String text);
        void onVadChanged(int vadStatus);
        void onError(String message);
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
                UpdateLog.i("SpeechManager: UART " + (connected ? "connected" : "disconnected"));
            }

            @Override
            public void onError(String message) {
                notifyError("UART: " + message);
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
                }
            }

            @Override
            public void onConnectionState(boolean connected) {
                UpdateLog.i("SpeechManager: Audio " + (connected ? "connected" : "disconnected"));
            }

            @Override
            public void onError(String message) {
                UpdateLog.e("SpeechManager: Audio error: " + message);
            }
        });

        // 视频通道 (本机)
        mVideoClient = new VideoClient(new AiuiProtocol.VideoCallback() {
            @Override
            public void onVideoFrame(int frameIndex, byte[] frameData) {
                // 视频帧处理，后续可扩展显示
            }

            @Override
            public void onConnectionState(boolean connected) {
                UpdateLog.i("SpeechManager: Video " + (connected ? "connected" : "disconnected"));
            }

            @Override
            public void onError(String message) {
                UpdateLog.e("SpeechManager: Video error: " + message);
            }
        });

        UpdateLog.i("SpeechManager: initialized");
    }

    public void connect() {
        if (mUartClient != null) mUartClient.connect();
        if (mAudioClient != null) mAudioClient.connect();
        if (mVideoClient != null) mVideoClient.connect();
    }

    public void disconnect() {
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
                    mHandler.post(() -> {
                        if (mCallback != null) mCallback.onWakeup();
                    });
                    break;
                case "EVENT_SLEEP":
                case "5":
                    mIsWakeup = false;
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

            boolean hasNlp = false;

            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                JSONObject params = item.optJSONObject("params");
                if (params == null) continue;

                String sub = params.optString("sub", "");

                if ("iat".equals(sub)) {
                    processIatResult(item);
                } else if ("nlp".equals(sub) || "cbm_semantic".equals(sub)) {
                    hasNlp = true;
                    processNlpResult(item);
                }
            }
        } catch (Exception e) {
            UpdateLog.e("SpeechManager: processResultEvent error", e);
        }
    }

    /**
     * 处理 IAT (语音识别) 结果
     */
    private void processIatResult(JSONObject item) {
        try {
            JSONObject text = item.optJSONObject("text");
            if (text == null) return;

            boolean isLast = text.optBoolean("ls", false);
            String pgs = text.optString("pgs", "");
            int sn = text.optInt("sn", 0);

            // 拼接识别文本
            StringBuilder sb = new StringBuilder();
            JSONArray ws = text.optJSONArray("ws");
            if (ws != null) {
                for (int i = 0; i < ws.length(); i++) {
                    JSONArray cw = ws.getJSONObject(i).optJSONArray("cw");
                    if (cw != null && cw.length() > 0) {
                        sb.append(cw.getJSONObject(0).optString("w", ""));
                    }
                }
            }
            String word = sb.toString();

            // 处理 PGS (动态修正)
            if ("rpl".equals(pgs)) {
                // 替换模式
                JSONArray rg = text.optJSONArray("rg");
                if (rg != null && rg.length() >= 2 && sn < mIatPgsStack.length) {
                    String current = mIatPgsStack[sn] != null ? mIatPgsStack[sn] : "";
                    int start = rg.optInt(0);
                    int end = rg.optInt(1);
                    if (start >= 0 && end <= current.length()) {
                        current = current.substring(0, start) + word + current.substring(end);
                    }
                    mIatPgsStack[sn] = current;
                    word = current;
                }
                mIatPgsIndex = sn;
            } else {
                if (sn < mIatPgsStack.length) {
                    mIatPgsStack[sn] = word;
                }
                mIatPgsIndex = sn;
            }

            // 拼接完整文本
            StringBuilder fullText = new StringBuilder();
            for (int i = 0; i <= mIatPgsIndex; i++) {
                if (mIatPgsStack[i] != null) {
                    fullText.append(mIatPgsStack[i]);
                }
            }
            mIatText = fullText.toString();

            if (isLast) {
                mLastFinalIat = mIatText;
                // 清理栈
                for (int i = 0; i < mIatPgsStack.length; i++) {
                    mIatPgsStack[i] = null;
                }
                mIatPgsIndex = 0;
            }

            final String displayText = mIatText;
            final boolean finalResult = isLast;

            mHandler.post(() -> {
                if (mCallback != null) mCallback.onIatResult(displayText, finalResult);
            });
        } catch (Exception e) {
            UpdateLog.e("SpeechManager: processIatResult error", e);
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
}
