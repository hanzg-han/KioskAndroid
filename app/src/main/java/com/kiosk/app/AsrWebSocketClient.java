package com.kiosk.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Qwen3-ASR WebSocket 客户端 (协议 V1.0)
 * 将 PCM int16 音频流式发送到 Qwen3-ASR 服务端获取识别文本
 *
 * 协议:
 *   1. 连接 ws://host:8765/ws/transcribe
 *   2. 发送握手: {"model":"1.7B","language":"Chinese","clinic_mode":true}
 *   3. 接收 {"type":"status","value":"ready","chunk_sec":2.0,"session_id":"...","clinic_mode":...}
 *   4. 流式发送 PCM int16 binary frames
 *   5. 接收 {"type":"result","text":"...","is_final":false,...}
 *   6. 发送 {"action":"chunk_boundary"} 或 {"action":"flush"}
 *   7. 接收 {"type":"status","value":"flushed","total_chunks":...,"total_kb":...}
 *   8. 接收 {"type":"heartbeat","msg":"...","gap_sec":...} (30s 无数据)
 *   9. 接收 {"type":"error","message":"...","stage":"..."}
 */
public class AsrWebSocketClient {

    /** Qwen3-ASR 服务默认地址 */
    public static final String DEFAULT_ASR_WS_URL = "ws://192.168.0.119:8765/ws/transcribe";

    private static final int SAMPLE_RATE = 16000;
    private static final int RECONNECT_DELAY_MS = 3000;
    private static final int HANDSHAKE_TIMEOUT_MS = 10000;

    private final String mWsUrl;
    private final AsrCallback mCallback;
    private final Handler mHandler;

    private OkHttpClient mHttpClient;
    private WebSocket mWebSocket;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final AtomicBoolean mReady = new AtomicBoolean(false);
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private Thread mReconnectThread;
    private final Object mDisconnectLock = new Object();

    public interface AsrCallback {
        /** 收到识别结果文本 */
        void onResult(String text, boolean isFinal);
        /** WebSocket 就绪，可以发送音频 */
        void onReady();
        /** 连接/断开状态变化 */
        void onConnectionState(boolean connected);
        /** 错误 */
        void onError(String message);
    }

    public AsrWebSocketClient(String wsUrl, AsrCallback callback) {
        this.mWsUrl = (wsUrl != null && !wsUrl.isEmpty()) ? wsUrl : DEFAULT_ASR_WS_URL;
        this.mCallback = callback;
        this.mHandler = new Handler(Looper.getMainLooper());
    }

    public AsrWebSocketClient(AsrCallback callback) {
        this(DEFAULT_ASR_WS_URL, callback);
    }

    // ========== 连接管理 ==========

    public void connect() {
        if (mRunning.get()) {
            UpdateLog.i("AsrWS: connect() called but already running (mRunning=true)");
            return;
        }
        UpdateLog.i("AsrWS: connect() starting...");
        mRunning.set(true);
        mFirstFrameLogged = false;

        mReconnectThread = new Thread(() -> {
            while (mRunning.get()) {
                try {
                    UpdateLog.i("AsrWS: connecting to " + mWsUrl);
                    notifyConnectionState(false);
                    mReady.set(false);

                    mHttpClient = new OkHttpClient.Builder()
                            .readTimeout(0, TimeUnit.MILLISECONDS) // 无超时，流式连接
                            .build();

                    Request request = new Request.Builder()
                            .url(mWsUrl)
                            .build();

                    final Object lock = new Object();
                    final boolean[] connected = {false};

                    mWebSocket = mHttpClient.newWebSocket(request, new WebSocketListener() {
                        @Override
                        public void onOpen(WebSocket webSocket, Response response) {
                            UpdateLog.i("AsrWS: connected, sending handshake...");
                            // 发送握手
                            try {
                                JSONObject cfg = new JSONObject();
                                cfg.put("model", "1.7B");
                                cfg.put("language", "Chinese");
                                cfg.put("clinic_mode", true);
                                webSocket.send(cfg.toString());
                                UpdateLog.i("AsrWS: handshake sent: " + cfg);
                            } catch (Exception e) {
                                UpdateLog.e("AsrWS: handshake error", e);
                            }
                            synchronized (lock) {
                                connected[0] = true;
                                lock.notifyAll();
                            }
                        }

                        @Override
                        public void onMessage(WebSocket webSocket, String text) {
                            handleTextMessage(text);
                        }

                        @Override
                        public void onMessage(WebSocket webSocket, ByteString bytes) {
                            // Qwen3-ASR 不发送二进制消息
                        }

                        @Override
                        public void onClosing(WebSocket webSocket, int code, String reason) {
                            UpdateLog.i("AsrWS: closing, code=" + code + " reason=" + reason);
                            webSocket.close(1000, null);
                        }

                        @Override
                        public void onClosed(WebSocket webSocket, int code, String reason) {
                            UpdateLog.i("AsrWS: closed, code=" + code + " reason=" + reason);
                            mReady.set(false);
                            mConnected.set(false);
                            notifyConnectionState(false);
                            // 唤醒重连线程
                            synchronized (mDisconnectLock) {
                                mDisconnectLock.notifyAll();
                            }
                        }

                        @Override
                        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                            UpdateLog.e("AsrWS: failure", t);
                            mReady.set(false);
                            mConnected.set(false);
                            notifyConnectionState(false);
                            // 唤醒重连线程
                            synchronized (mDisconnectLock) {
                                mDisconnectLock.notifyAll();
                            }
                            synchronized (lock) {
                                lock.notifyAll();
                            }
                        }
                    });

                    // 等待连接打开（最多 10 秒）
                    synchronized (lock) {
                        if (!connected[0]) {
                            try {
                                lock.wait(HANDSHAKE_TIMEOUT_MS);
                            } catch (InterruptedException ignored) {}
                        }
                    }

                    if (!connected[0]) {
                        UpdateLog.e("AsrWS: connect timeout, will retry...");
                        closeWebSocket();
                        sleepReconnect();
                        continue;
                    }

                    // 连接成功，等待断开信号后再重连
                    mConnected.set(true);
                    synchronized (mDisconnectLock) {
                        while (mRunning.get() && mConnected.get()) {
                            try {
                                mDisconnectLock.wait();
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                    }

                } catch (Exception e) {
                    UpdateLog.e("AsrWS: connect error", e);
                    notifyConnectionState(false);
                    sleepReconnect();
                }
            }
        }, "AsrWS-Connect");
        mReconnectThread.start();
    }

    public void disconnect() {
        UpdateLog.i("AsrWS: disconnect() called, wasRunning=" + mRunning.get());
        mRunning.set(false);
        mReady.set(false);
        mConnected.set(false);
        closeWebSocket();
        // 唤醒重连线程使其退出
        synchronized (mDisconnectLock) {
            mDisconnectLock.notifyAll();
        }
        if (mReconnectThread != null) {
            mReconnectThread.interrupt();
        }
    }

    // ========== 音频发送 ==========

    /** ASR 发送统计（调试用） */
    private long mWsSentCount = 0;
    private long mWsDropNotReady = 0;
    private long mWsLastStatTime = 0;
    private boolean mFirstFrameLogged = false;
    private static final int WS_STAT_INTERVAL_MS = 10000;

    /**
     * 发送 PCM int16 音频数据（服务端已改为 int16 格式）
     * @param pcmInt16 PCM int16 格式原始字节
     */
    public void sendAudio(byte[] pcmInt16) {
        if (!mReady.get() || mWebSocket == null || pcmInt16 == null || pcmInt16.length == 0) {
            if (!mReady.get()) mWsDropNotReady++;
            return;
        }
        try {
            mWebSocket.send(ByteString.of(pcmInt16));
            mWsSentCount++;
            if (!mFirstFrameLogged) {
                mFirstFrameLogged = true;
                UpdateLog.i(String.format("AsrWS: FIRST frame sent! pcmLen=%d", pcmInt16.length));
            }
            // 每10秒输出发送统计
            long now = System.currentTimeMillis();
            if (mWsLastStatTime == 0) mWsLastStatTime = now;
            if (now - mWsLastStatTime >= WS_STAT_INTERVAL_MS) {
                UpdateLog.i(String.format("AsrWS send: sent=%d dropNotReady=%d ready=%s",
                        mWsSentCount, mWsDropNotReady, mReady.get()));
                mWsLastStatTime = now;
                mWsSentCount = 0;
                mWsDropNotReady = 0;
            }
        } catch (Exception e) {
            UpdateLog.e("AsrWS: sendAudio error", e);
        }
    }

    /**
     * Flush 缓冲区中不满 2 秒的剩余音频
     */
    public void flush() {
        if (mReady.get() && mWebSocket != null) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("action", "flush");
                mWebSocket.send(msg.toString());
                UpdateLog.i("AsrWS: flush sent");
            } catch (Exception e) {
                UpdateLog.e("AsrWS: flush error", e);
            }
        }
    }

    /**
     * 强制分片指令（V1.0），指示服务端立即对当前累积音频执行推理
     * 用于 VAD 检测到停顿（连续静音 ~500ms）时主动分片
     */
    public void sendChunkBoundary() {
        if (mReady.get() && mWebSocket != null) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("action", "chunk_boundary");
                mWebSocket.send(msg.toString());
                UpdateLog.d("AsrWS: chunk_boundary sent");
            } catch (Exception e) {
                UpdateLog.e("AsrWS: chunk_boundary error", e);
            }
        }
    }

    public boolean isReady() {
        return mReady.get();
    }

    // ========== 消息处理 ==========

    private void handleTextMessage(String text) {
        try {
            JSONObject obj = new JSONObject(text);
            String type = obj.optString("type", "");

            if ("status".equals(type)) {
                // V1.0: {"type":"status","value":"ready"|"flushed",...}
                String value = obj.optString("value", "");
                if ("ready".equals(value)) {
                    // 握手成功
                    mReady.set(true);
                    mFirstFrameLogged = false;
                    String sessionId = obj.optString("session_id", "");
                    double chunkSec = obj.optDouble("chunk_sec", 2.0);
                    boolean clinic = obj.optBoolean("clinic_mode", false);
                    UpdateLog.i(String.format("AsrWS: ready (session=%s chunk_sec=%.1f clinic_mode=%s)",
                            sessionId, chunkSec, clinic));
                    mHandler.post(() -> {
                        notifyConnectionState(true);
                        if (mCallback != null) mCallback.onReady();
                    });
                } else if ("flushed".equals(value)) {
                    int totalChunks = obj.optInt("total_chunks", 0);
                    int totalPackets = obj.optInt("total_packets", 0);
                    double totalKb = obj.optDouble("total_kb", 0);
                    UpdateLog.i(String.format("AsrWS: flushed OK (chunks=%d packets=%d kb=%.1f)",
                            totalChunks, totalPackets, totalKb));
                } else {
                    UpdateLog.d("AsrWS: status value=" + value);
                }
            } else if ("result".equals(type)) {
                // V1.0: {"type":"result","text":"...","is_final":...,"chunk_idx":...,...}
                String resultText = obj.optString("text", "");
                boolean isFinal = obj.optBoolean("is_final", false);
                int chunkIdx = obj.optInt("chunk_idx", 0);
                double elapsed = obj.optDouble("elapsed", 0);
                double inferTime = obj.optDouble("infer_time", 0);
                double chunkDuration = obj.optDouble("chunk_duration", 0);
                double audioRms = obj.optDouble("audio_rms", 0);
                double audioPeak = obj.optDouble("audio_peak", 0);
                String debugWav = obj.optString("debug_wav", "");
                UpdateLog.i(String.format(
                        "AsrWS: result #%d text=\"%s\" final=%s dur=%.2fs elapsed=%.2fs infer=%.2fs rms=%.4f peak=%.4f wav=%s",
                        chunkIdx, resultText, isFinal, chunkDuration, elapsed, inferTime,
                        audioRms, audioPeak, debugWav));
                final String textOut = resultText;
                final boolean finalOut = isFinal;
                mHandler.post(() -> {
                    if (mCallback != null) mCallback.onResult(textOut, finalOut);
                });
            } else if ("heartbeat".equals(type)) {
                // V1.0: {"type":"heartbeat","msg":"...","gap_sec":...,"received_packets":...,...}
                String msg = obj.optString("msg", "");
                double gapSec = obj.optDouble("gap_sec", 0);
                int receivedPackets = obj.optInt("received_packets", 0);
                UpdateLog.d(String.format("AsrWS: heartbeat gap=%.1fs packets=%d msg=%s",
                        gapSec, receivedPackets, msg));
            } else if ("error".equals(type)) {
                // V1.0: {"type":"error","message":"...","chunk_idx":...,"stage":"..."}
                String msg = obj.optString("message", "unknown error");
                int chunkIdx = obj.optInt("chunk_idx", 0);
                String stage = obj.optString("stage", "");
                UpdateLog.e(String.format("AsrWS: server error (chunk=%d stage=%s): %s",
                        chunkIdx, stage, msg));
                mHandler.post(() -> {
                    if (mCallback != null) mCallback.onError("Qwen3-ASR: " + msg);
                });
            } else {
                // 未知消息类型，防御性日志
                UpdateLog.d("AsrWS: unknown message type=\"" + type + "\": " + text);
            }
        } catch (Exception e) {
            UpdateLog.e("AsrWS: parse message error", e);
        }
    }


    // ========== 内部方法 ==========

    private void closeWebSocket() {
        try {
            if (mWebSocket != null) {
                mWebSocket.close(1000, "Client disconnect");
            }
        } catch (Exception ignored) {}
        mWebSocket = null;

        try {
            if (mHttpClient != null) {
                mHttpClient.dispatcher().executorService().shutdown();
            }
        } catch (Exception ignored) {}
        mHttpClient = null;
    }

    private void sleepReconnect() {
        try {
            Thread.sleep(RECONNECT_DELAY_MS);
        } catch (InterruptedException ignored) {}
    }

    private void notifyConnectionState(final boolean connected) {
        mHandler.post(() -> {
            if (mCallback != null) mCallback.onConnectionState(connected);
        });
    }
}
