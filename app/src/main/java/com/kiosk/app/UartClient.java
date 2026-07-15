package com.kiosk.app;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UART 通道 TCP 客户端
 * 连接本地 AIUI 引擎，收发控制命令和识别结果
 * 默认连接 127.0.0.1:19199
 */
public class UartClient {

    private final String mHost;
    private final int mPort;
    private final AiuiProtocol.UartCallback mCallback;
    private final Handler mHandler;

    private Socket mSocket;
    private OutputStream mOut;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final AtomicInteger mSeqId = new AtomicInteger(0);
    private Thread mRecvThread;
    private Thread mHeartbeatThread;

    // 重连
    private static final int RECONNECT_DELAY_MS = 3000;
    private static final int HEARTBEAT_INTERVAL_MS = 5000;

    public UartClient(String host, int port, AiuiProtocol.UartCallback callback) {
        this.mHost = host;
        this.mPort = port;
        this.mCallback = callback;
        this.mHandler = new Handler(Looper.getMainLooper());
    }

    public UartClient(AiuiProtocol.UartCallback callback) {
        this(AiuiProtocol.DEFAULT_LOCAL_IP, AiuiProtocol.UART_PORT, callback);
    }

    // ========== 连接管理 ==========

    public void connect() {
        if (mRunning.get()) return;
        mRunning.set(true);

        new Thread(() -> {
            while (mRunning.get()) {
                try {
                    UpdateLog.i("UartClient: connecting to " + mHost + ":" + mPort);
                    mSocket = new Socket();
                    mSocket.connect(new InetSocketAddress(mHost, mPort), 5000);
                    mOut = mSocket.getOutputStream();

                    // 发送握手包
                    sendHandshake();

                    notifyConnection(true);
                    UpdateLog.i("UartClient: connected");

                    // 启动心跳
                    startHeartbeat();
                    // 接收循环
                    receiveLoop();
                } catch (Exception e) {
                    UpdateLog.e("UartClient: connect failed", e);
                    notifyConnection(false);
                    sleepReconnect();
                } finally {
                    closeSocket();
                }
            }
        }, "UartClient-Connect").start();
    }

    public void disconnect() {
        mRunning.set(false);
        closeSocket();
        notifyConnection(false);
    }

    public boolean isConnected() {
        return mSocket != null && mSocket.isConnected() && !mSocket.isClosed();
    }

    // ========== 发送命令 ==========

    public void sendWakeup() {
        sendPacket(() -> AiuiProtocol.buildWakeupCommand(nextSeqId()));
    }

    public void sendTts(String text) {
        sendPacket(() -> AiuiProtocol.buildTtsCommand(nextSeqId(), text));
    }

    public void sendCommand(int cmd, String jsonContent) {
        sendPacket(() -> AiuiProtocol.buildControlCommand(cmd, nextSeqId(), jsonContent));
    }

    private void sendHandshake() throws IOException {
        byte[] handshake = {AiuiProtocol.FRAME_HEADER_0, AiuiProtocol.FRAME_HEADER_1,
                            AiuiProtocol.TYPE_AIUI, 4, 0, 0, 0,
                            (byte) 0xA5, 0, 0, 0};
        mOut.write(handshake);
        mOut.flush();
        UpdateLog.i("UartClient: handshake sent");
    }

    private interface PacketBuilder {
        byte[] build() throws IOException;
    }

    private void sendPacket(PacketBuilder builder) {
        if (!isConnected()) {
            UpdateLog.e("UartClient: send failed, not connected");
            return;
        }
        try {
            byte[] data = builder.build();
            synchronized (mOut) {
                mOut.write(data);
                mOut.flush();
            }
            UpdateLog.d("UartClient: packet sent, len=" + data.length);
        } catch (Exception e) {
            UpdateLog.e("UartClient: send error", e);
        }
    }

    private int nextSeqId() {
        return mSeqId.incrementAndGet() & 0xFFFF;
    }

    // ========== 接收循环 ==========

    private void receiveLoop() {
        try {
            InputStream in = mSocket.getInputStream();
            byte[] headerBuf = new byte[AiuiProtocol.UART_HEADER_SIZE];

            while (mRunning.get() && !Thread.currentThread().isInterrupted()) {
                // 读取帧头
                readFully(in, headerBuf);
                if (headerBuf[0] != AiuiProtocol.FRAME_HEADER_0
                        || headerBuf[1] != AiuiProtocol.FRAME_HEADER_1) {
                    // 帧头不对齐，尝试同步
                    UpdateLog.d("UartClient: desync, re-syncing...");
                    resync(in);
                    continue;
                }

                int[] parsed = AiuiProtocol.parseUartHeader(headerBuf);
                if (parsed == null) continue;
                int dataLen = parsed[0];
                int seqId = parsed[1];

                if (dataLen <= 0 || dataLen > 1024 * 1024) {
                    UpdateLog.e("UartClient: invalid data len: " + dataLen);
                    continue;
                }

                // 读取 Gzip 压缩的 JSON 数据
                byte[] data = new byte[dataLen];
                readFully(in, data);

                // 解压并解析
                try {
                    String json = AiuiProtocol.gzipDecompress(data);
                    UpdateLog.d("UartClient: recv seqId=" + seqId + ", jsonLen=" + json.length());

                    // 解析事件类型并回调
                    parseAndDispatch(json, seqId);
                } catch (Exception e) {
                    UpdateLog.e("UartClient: decompress failed", e);
                }
            }
        } catch (IOException e) {
            if (mRunning.get()) {
                UpdateLog.e("UartClient: recv error", e);
            }
        }
    }

    private void readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int n = in.read(buf, offset, buf.length - offset);
            if (n < 0) throw new IOException("EOF");
            offset += n;
        }
    }

    private void resync(InputStream in) throws IOException {
        // 查找下一个 0xA5 0x01
        int b;
        while ((b = in.read()) != -1) {
            if (b == (AiuiProtocol.FRAME_HEADER_0 & 0xFF)) {
                int next = in.read();
                if (next == (AiuiProtocol.FRAME_HEADER_1 & 0xFF)) {
                    UpdateLog.d("UartClient: re-synced");
                    return;
                }
            }
        }
        throw new IOException("Stream ended during resync");
    }

    private void parseAndDispatch(String json, int seqId) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);

            String msgType = obj.optString("type", "");
            String eventType = "";
            String content = obj.optString("content", "");

            if ("aiui_event".equals(msgType)) {
                // 事件类型在 content 的 JSON 中或直接在顶层
                try {
                    org.json.JSONObject contentObj = new org.json.JSONObject(content);
                    eventType = contentObj.optString("eventType", "");
                } catch (Exception ignored) {}
            }

            final String jsonContent = json;
            final String evtType = eventType;
            final int sid = seqId;

            mHandler.post(() -> {
                if (mCallback != null) {
                    mCallback.onEvent(evtType, sid, jsonContent);
                }
            });
        } catch (Exception e) {
            UpdateLog.e("UartClient: parse JSON failed", e);
        }
    }

    // ========== 心跳 ==========

    private void startHeartbeat() {
        if (mHeartbeatThread != null) {
            mHeartbeatThread.interrupt();
        }
        mHeartbeatThread = new Thread(() -> {
            while (mRunning.get() && isConnected()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    sendPacket(() -> AiuiProtocol.buildHeartbeat(nextSeqId()));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "UartClient-Heartbeat");
        mHeartbeatThread.start();
    }

    // ========== 辅助方法 ==========

    private void closeSocket() {
        try {
            if (mSocket != null) mSocket.close();
        } catch (Exception ignored) {}
        mSocket = null;
        mOut = null;
    }

    private void sleepReconnect() {
        try {
            Thread.sleep(RECONNECT_DELAY_MS);
        } catch (InterruptedException ignored) {}
    }

    private void notifyConnection(final boolean connected) {
        mHandler.post(() -> {
            if (mCallback != null) mCallback.onConnectionState(connected);
        });
    }

    public void notifyError(final String msg) {
        mHandler.post(() -> {
            if (mCallback != null) mCallback.onError(msg);
        });
    }
}
