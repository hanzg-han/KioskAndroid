package com.kiosk.app;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 音频流 TCP 客户端
 * 从 AIUI 引擎接收实时 PCM 音频流
 * 默认连接 127.0.0.1:9080
 */
public class AudioClient {

    private final String mHost;
    private final int mPort;
    private final AiuiProtocol.AudioCallback mCallback;
    private final Handler mHandler;

    private Socket mSocket;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private Thread mRecvThread;

    private static final int RECONNECT_DELAY_MS = 3000;

    public AudioClient(String host, int port, AiuiProtocol.AudioCallback callback) {
        this.mHost = host;
        this.mPort = port;
        this.mCallback = callback;
        this.mHandler = new Handler(Looper.getMainLooper());
    }

    public AudioClient(AiuiProtocol.AudioCallback callback) {
        this(AiuiProtocol.DEFAULT_LOCAL_IP, AiuiProtocol.AUDIO_PORT, callback);
    }

    // ========== 连接管理 ==========

    public void connect() {
        if (mRunning.get()) return;
        mRunning.set(true);

        mRecvThread = new Thread(() -> {
            while (mRunning.get()) {
                try {
                    UpdateLog.i("AudioClient: connecting to " + mHost + ":" + mPort);
                    mSocket = new Socket();
                    mSocket.connect(new InetSocketAddress(mHost, mPort), 5000);
                    UpdateLog.i("AudioClient: connected");
                    notifyConnection(true);
                    receiveLoop();
                } catch (Exception e) {
                    UpdateLog.e("AudioClient: connect failed", e);
                    notifyConnection(false);
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ignored) {}
                } finally {
                    closeSocket();
                }
            }
        }, "AudioClient-Connect");
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
                // 读取帧头
                readFully(in, headerBuf);
                if (headerBuf[0] != AiuiProtocol.FRAME_HEADER_0
                        || headerBuf[1] != AiuiProtocol.FRAME_HEADER_1) {
                    resync(in);
                    continue;
                }

                int[] parsed = AiuiProtocol.parseAvHeader(headerBuf);
                if (parsed == null) continue;
                int dataLen = parsed[0];

                if (dataLen <= 0 || dataLen > 10 * 1024 * 1024) {
                    UpdateLog.e("AudioClient: invalid data len: " + dataLen);
                    resync(in);
                    continue;
                }

                // 读取数据
                byte[] data = new byte[dataLen];
                readFully(in, data);

                // 读取帧尾 0x00
                byte tail = (byte) in.read();

                // 解析音频帧
                if (tail == AiuiProtocol.FRAME_TAIL) {
                    AiuiProtocol.AudioFrame frame = AiuiProtocol.parseAudioFrame(data);
                    if (frame != null) {
                        dispatchAudioFrame(frame);
                    }
                }
            }
        } catch (IOException e) {
            if (mRunning.get()) {
                UpdateLog.e("AudioClient: recv error", e);
            }
        }
    }

    private void readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int n = in.read(buf, offset, buf.length - offset);
            if (n < 0) throw new IOException("AudioClient: EOF");
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
        throw new IOException("AudioClient: stream ended during resync");
    }

    private void dispatchAudioFrame(final AiuiProtocol.AudioFrame frame) {
        mHandler.post(() -> {
            if (mCallback != null) {
                mCallback.onAudioFrame(frame.vadStatus, frame.engineIndex,
                        frame.frameIndex, frame.pcmData);
            }
        });
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
