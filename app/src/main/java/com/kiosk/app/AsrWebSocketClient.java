package com.kiosk.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Qwen3-ASR WebSocket 客户端
 * 将 PCM int16 音频转为 float32，流式发送到 Qwen3-ASR 服务端获取识别文本
 *
 * 协议:
 *   1. 连接 ws://host:8765/ws/transcribe
 *   2. 发送握手: {"model":"1.7B","language":"Chinese"}
 *   3. 接收 {"status":"ready"}
 *   4. 流式发送 PCM float32 binary frames
 *   5. 接收 {"type":"result","text":"...","is_final":false}
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
     * 发送 PCM int16 音频数据，内部转换为 float32
     * @param pcmInt16 PCM int16 格式原始字节
     */
    public void sendAudio(byte[] pcmInt16) {
        if (!mReady.get() || mWebSocket == null || pcmInt16 == null || pcmInt16.length == 0) {
            if (!mReady.get()) mWsDropNotReady++;
            return;
        }
        try {
            byte[] float32Data = convertInt16ToFloat32(pcmInt16);
            mWebSocket.send(ByteString.of(float32Data));
            mWsSentCount++;
            if (!mFirstFrameLogged) {
                mFirstFrameLogged = true;
                UpdateLog.i(String.format("AsrWS: FIRST frame sent! pcm=%d float32=%d",
                        pcmInt16.length, float32Data.length));
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

    public boolean isReady() {
        return mReady.get();
    }

    // ========== 消息处理 ==========

    private void handleTextMessage(String text) {
        try {
            JSONObject obj = new JSONObject(text);

            String type = obj.optString("type", "");
            String status = obj.optString("status", "");

            if ("ready".equals(status)) {
                // 握手成功
                mReady.set(true);
                mFirstFrameLogged = false;
                UpdateLog.i("AsrWS: handshake OK, ready to send audio");
                mHandler.post(() -> {
                    notifyConnectionState(true);
                    if (mCallback != null) mCallback.onReady();
                });
            } else if ("result".equals(type)) {
                // 识别结果
                String resultText = obj.optString("text", "");
                boolean isFinal = obj.optBoolean("is_final", false);
                double elapsed = obj.optDouble("elapsed", 0);
                UpdateLog.i("AsrWS: result text=" + resultText + " elapsed=" + elapsed + "s");
                final String textOut = resultText;
                final boolean finalOut = isFinal;
                mHandler.post(() -> {
                    if (mCallback != null) mCallback.onResult(textOut, finalOut);
                });
            } else if ("heartbeat".equals(type)) {
                // 忽略心跳
            } else if ("error".equals(type)) {
                String msg = obj.optString("message", "unknown error");
                UpdateLog.e("AsrWS: server error: " + msg);
                mHandler.post(() -> {
                    if (mCallback != null) mCallback.onError("Qwen3-ASR: " + msg);
                });
            } else if ("flushed".equals(status)) {
                UpdateLog.i("AsrWS: flushed OK");
            }
        } catch (Exception e) {
            UpdateLog.e("AsrWS: parse message error", e);
        }
    }

    // ========== 音频格式转换 ==========

    /**
     * PCM int16 → float32 (little-endian)
     * int16 范围 [-32768, 32767] → float32 [-1.0, 1.0]
     */
    private static byte[] convertInt16ToFloat32(byte[] int16Data) {
        int numSamples = int16Data.length / 2;
        ByteBuffer floatBuf = ByteBuffer.allocate(numSamples * 4);
        floatBuf.order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < int16Data.length - 1; i += 2) {
            short sample = (short) ((int16Data[i] & 0xFF) | ((int16Data[i + 1] & 0xFF) << 8));
            float f = sample / 32768.0f;
            floatBuf.putFloat(f);
        }

        return floatBuf.array();
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
