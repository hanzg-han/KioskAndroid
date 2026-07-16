package com.kiosk.app;

import android.os.Handler;
import android.os.Looper;

/**
 * 语音识别管理器 (VAD 驱动版，无 AIUI 引擎)
 *
 * 架构:
 *   AudioClient (TCP:9080) → PCM音频帧(含VAD状态) → VAD_BOS/EOS 控制 ASR 发送
 *   AsrWebSocketClient         → 流式发送音频到 Qwen3-ASR → 识别文本
 *
 * 状态机 (VAD 驱动):
 *   IDLE ──VAD_BOS──▶ SENDING (连接ASR, 开始发送音频)
 *   SENDING ──VAD_VOL──▶ SENDING (继续发送音频)
 *   SENDING ──VAD_EOS──▶ IDLE (flush ASR, 获取最终结果)
 *
 * 不再依赖:
 *   - UartClient (AIUI 引擎的 WAKEUP/SLEEP/NLP 事件)
 *   - VideoClient
 *   - engineIdx 过滤 (所有音频都处理)
 */
public class SpeechManager {

    private static SpeechManager sInstance;

    private AudioClient mAudioClient;
    private AsrWebSocketClient mAsrWsClient;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SpeechCallback mCallback;

    // VAD 驱动状态
    private static final int ST_IDLE = 0;
    private static final int ST_SENDING = 1;
    private volatile int mState = ST_IDLE;

    private volatile int mVadStatus = AiuiProtocol.VAD_SILENCE;
    private volatile String mIatText = "";

    // ASR 累积文本
    private final StringBuilder mAsrFullText = new StringBuilder();

    // 发送统计
    private long mSendCount = 0;
    private long mLastSendStatTime = 0;
    private long mSkipNotSending = 0;
    private long mSkipChannelFiltered = 0;
    private boolean mFirstAudioLogged = false;
    /** 只处理通道号 99 的音频帧 */
    private static final int AUDIO_ENGINE_INDEX = 99;
    private static final int SEND_STAT_INTERVAL_MS = 10000;

    public interface SpeechCallback {
        /** VAD_BOS: 开始说话 */
        void onWakeup();
        /** VAD_EOS: 结束说话 */
        void onSleep();
        /** Qwen3-ASR 识别结果 */
        void onIatResult(String text, boolean isFinal);
        /** VAD 状态变化 (BOS/VOL/EOS/SILENCE) */
        void onVadChanged(int vadStatus);
        void onError(String message);
        void onLog(String tag, String message);
    }

    private void notifyError(final String msg) {
        mHandler.post(() -> {
            if (mCallback != null) mCallback.onError(msg);
        });
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

        // ── 音频通道 ──
        mAudioClient = new AudioClient(new AiuiProtocol.AudioCallback() {
            @Override
            public void onAudioFrame(int vadStatus, int engineIndex, int frameIndex, byte[] pcmData) {
                if (engineIndex != AUDIO_ENGINE_INDEX) {
                    mSkipChannelFiltered++;
                    return;
                }
                onAudioFrameReceived(vadStatus, frameIndex, pcmData);
            }

            @Override
            public void onConnectionState(boolean connected) {
                String msg = connected ? "Audio 已连接" : "Audio 断开连接";
                UpdateLog.i("SpeechMgr: " + msg);
                notifyLog("音频", msg);
            }

            @Override
            public void onError(String message) {
                UpdateLog.e("SpeechMgr: Audio error: " + message);
                notifyLog("音频", "错误: " + message);
            }
        });

        // ── ASR WebSocket ──
        mAsrWsClient = new AsrWebSocketClient(new AsrWebSocketClient.AsrCallback() {
            @Override
            public void onResult(String text, boolean isFinal) {
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
                UpdateLog.i("SpeechMgr: ASR ready");
                notifyLog("QwenASR", "就绪");
            }

            @Override
            public void onConnectionState(boolean connected) {
                String msg = connected ? "QwenASR 已连接" : "QwenASR 断开连接";
                UpdateLog.i("SpeechMgr: " + msg);
                notifyLog("QwenASR", msg);
            }

            @Override
            public void onError(String message) {
                UpdateLog.e("SpeechMgr: ASR error: " + message);
                notifyLog("QwenASR", "错误: " + message);
            }
        });

        UpdateLog.i("SpeechMgr: init done (VAD-driven, no AIUI engine)");
    }

    /** 连接：仅启动音频客户端，ASR 在首次 VAD_BOS 时按需连接 */
    public void connect() {
        if (mAudioClient != null) mAudioClient.connect();
    }

    /** 断开所有连接 */
    public void disconnect() {
        if (mAsrWsClient != null) {
            mAsrWsClient.flush();
            mAsrWsClient.disconnect();
        }
        if (mAudioClient != null) mAudioClient.disconnect();
        mState = ST_IDLE;
    }

    // ── 状态查询 ──

    public boolean isWakeup() { return mState == ST_SENDING; }
    public String getIatText() { return mIatText; }

    // ═══════════════════════════════════════════════════
    //  核心：VAD 驱动的音频处理
    // ═══════════════════════════════════════════════════

    private void onAudioFrameReceived(int vadStatus, int frameIndex, byte[] pcmData) {
        if (!mFirstAudioLogged) {
            mFirstAudioLogged = true;
            UpdateLog.i(String.format("SpeechMgr: FIRST audio frame! vad=%d pcmLen=%d",
                    vadStatus, pcmData != null ? pcmData.length : 0));
        }

        mVadStatus = vadStatus;
        notifyVadChanged(vadStatus);

        switch (vadStatus) {
            case AiuiProtocol.VAD_BOS:
                onVadBos(pcmData);
                break;
            case AiuiProtocol.VAD_VOL:
                onVadVol(pcmData);
                break;
            case AiuiProtocol.VAD_EOS:
                onVadEos(pcmData);
                break;
            default:
                // VAD_SILENCE: 不发送
                if (mState == ST_SENDING) mSkipNotSending++;
                break;
        }

        // 每 10 秒统计
        long now = System.currentTimeMillis();
        if (mLastSendStatTime == 0) mLastSendStatTime = now;
        if (now - mLastSendStatTime >= SEND_STAT_INTERVAL_MS) {
            float rate = mSendCount * 1000f / (now - mLastSendStatTime);
            UpdateLog.i(String.format("AudioSend: frames=%d rate=%.1f/s state=%d skipNoSend=%d skipChan=%d",
                    mSendCount, rate, mState, mSkipNotSending, mSkipChannelFiltered));
            mLastSendStatTime = now;
            mSendCount = 0;
            mSkipNotSending = 0;
            mSkipChannelFiltered = 0;
        }
    }

    private void onVadBos(byte[] pcmData) {
        UpdateLog.i("SpeechMgr: ★ VAD_BOS → start new ASR session");
        mAsrFullText.setLength(0);
        mIatText = "";

        // 连接到 ASR（内部自动处理已连接状态）
        if (mAsrWsClient != null) {
            if (!mAsrWsClient.isReady()) {
                mAsrWsClient.connect();
                UpdateLog.i("SpeechMgr: ASR connecting...");
            } else {
                UpdateLog.i("SpeechMgr: ASR already ready, reuse session");
            }
        }

        mState = ST_SENDING;
        notifyWakeup();

        // BOS 帧也有音频数据，发送
        sendAudio(pcmData);
    }

    private void onVadVol(byte[] pcmData) {
        if (mState == ST_SENDING) {
            sendAudio(pcmData);
        } else {
            mSkipNotSending++;
        }
    }

    private void onVadEos(byte[] pcmData) {
        if (mState != ST_SENDING) {
            UpdateLog.d("SpeechMgr: VAD_EOS ignored, not in SENDING state");
            return;
        }

        UpdateLog.i("SpeechMgr: ★ VAD_EOS → flush ASR, end session");

        // 发送最后一帧
        sendAudio(pcmData);

        // flush 让 ASR 服务端输出最终结果
        if (mAsrWsClient != null) {
            mAsrWsClient.flush();
        }

        mState = ST_IDLE;
        mAsrFullText.setLength(0);
        notifySleep();
    }

    private void sendAudio(byte[] pcmData) {
        if (pcmData == null || pcmData.length == 0) return;
        if (mAsrWsClient != null) {
            mAsrWsClient.sendAudio(pcmData);
            mSendCount++;
        }
    }

    // ═══════════════════════════════════════════════════
    //  通知回调 (主线程)
    // ═══════════════════════════════════════════════════

    private void notifyWakeup() {
        mHandler.post(() -> {
            if (mCallback != null) mCallback.onWakeup();
        });
    }

    private void notifySleep() {
        mHandler.post(() -> {
            if (mCallback != null) mCallback.onSleep();
        });
    }

    private void notifyVadChanged(final int vad) {
        mHandler.post(() -> {
            if (mCallback != null) mCallback.onVadChanged(vad);
        });
    }

    private void notifyLog(final String tag, final String msg) {
        mHandler.post(() -> {
            if (mCallback != null) mCallback.onLog(tag, msg);
        });
    }
}
