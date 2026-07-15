package com.kiosk.app;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 视频流 TCP 客户端
 * 从 AIUI 引擎接收视频帧
 * 默认连接 127.0.0.1:9090
 */
public class VideoClient {

    private final String mHost;
    private final int mPort;
    private final AiuiProtocol.VideoCallback mCallback;
    private final Handler mHandler;

    private Socket mSocket;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private Thread mRecvThread;

    private static final int RECONNECT_DELAY_MS = 3000;

    public VideoClient(String host, int port, AiuiProtocol.VideoCallback callback) {
        this.mHost = host;
        this.mPort = port;
        this.mCallback = callback;
        this.mHandler = new Handler(Looper.getMainLooper());
    }

    public VideoClient(AiuiProtocol.VideoCallback callback) {
        this(AiuiProtocol.DEFAULT_LOCAL_IP, AiuiProtocol.VIDEO_PORT, callback);
    }

    // ========== 连接管理 ==========

    public void connect() {
        if (mRunning.get()) return;
        mRunning.set(true);

        mRecvThread = new Thread(() -> {
            while (mRunning.get()) {
                try {
                    UpdateLog.i("VideoClient: connecting to " + mHost + ":" + mPort);
                    mSocket = new Socket();
                    mSocket.connect(new InetSocketAddress(mHost, mPort), 5000);
                    UpdateLog.i("VideoClient: connected");
                    notifyConnection(true);
                    receiveLoop();
                } catch (Exception e) {
                    UpdateLog.e("VideoClient: connect failed", e);
                    notifyConnection(false);
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ignored) {}
                } finally {
                    closeSocket();
                }
            }
        }, "VideoClient-Connect");
        mRecvThread.start();
    }

    public void disconnect() {
        mRunning.set(false);
        closeSocket();
        notifyConnection(false);
    }

    // ========== 接收循环 ==========

    private void receiveLoop() {
        try {
            InputStream in = mSocket.getInputStream();
            byte[] headerBuf = new byte[AiuiProtocol.AV_HEADER_SIZE];

            while (mRunning.get() && !Thread.currentThread().isInterrupted()) {
                readFully(in, headerBuf);
                if (headerBuf[0] != AiuiProtocol.FRAME_HEADER_0
                        || headerBuf[1] != AiuiProtocol.FRAME_HEADER_1) {
                    resync(in);
                    continue;
                }

                int[] parsed = AiuiProtocol.parseAvHeader(headerBuf);
                if (parsed == null) continue;
                int dataLen = parsed[0];
                int seqId = parsed[1];

                if (dataLen <= 0 || dataLen > 50 * 1024 * 1024) {
                    UpdateLog.e("VideoClient: invalid data len: " + dataLen);
                    resync(in);
                    continue;
                }

                byte[] data = new byte[dataLen];
                readFully(in, data);

                byte tail = (byte) in.read();
                if (tail == AiuiProtocol.FRAME_TAIL) {
                    final byte[] frameData = data;
                    final int fid = seqId;
                    mHandler.post(() -> {
                        if (mCallback != null) {
                            mCallback.onVideoFrame(fid, frameData);
                        }
                    });
                }
            }
        } catch (IOException e) {
            if (mRunning.get()) {
                UpdateLog.e("VideoClient: recv error", e);
            }
        }
    }

    private void readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int n = in.read(buf, offset, buf.length - offset);
            if (n < 0) throw new IOException("VideoClient: EOF");
            offset += n;
        }
    }

    private void resync(InputStream in) throws IOException {
        int b;
        while (mRunning.get() && (b = in.read()) != -1) {
            if (b == (AiuiProtocol.FRAME_HEADER_0 & 0xFF)) {
                int next = in.read();
                if (next == (AiuiProtocol.FRAME_HEADER_1 & 0xFF)) {
                    return;
                }
            }
        }
        throw new IOException("VideoClient: stream ended during resync");
    }

    private void closeSocket() {
        try {
            if (mSocket != null) mSocket.close();
        } catch (Exception ignored) {}
        mSocket = null;
    }

    private void notifyConnection(final boolean connected) {
        mHandler.post(() -> {
            if (mCallback != null) mCallback.onConnectionState(connected);
        });
    }
}
