package com.kiosk.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 讯飞 AIUI 引擎通信协议
 * 参照 C# XunfeiBox 项目实现
 *
 * UART 帧格式 (端口 19199):
 *   | 0xA5 | 0x01 | type(1B) | dataLen(2B LE) | seqId(2B LE) | gzip_compressed_json |
 *
 * 音频/视频帧格式 (端口 9080/9090):
 *   | 0xA5 | 0x01 | type(1B) | dataLen(4B LE) | seqId(2B LE) | data | 0x00 |
 */
public class AiuiProtocol {

    // ========== 帧常量 ==========
    public static final byte FRAME_HEADER_0 = (byte) 0xA5;
    public static final byte FRAME_HEADER_1 = (byte) 0x01;
    public static final byte FRAME_TAIL = 0x00;

    // UART 帧头长度: 0xA5 + 0x01 + type(1) + dataLen(2) + seqId(2) = 7
    public static final int UART_HEADER_SIZE = 7;
    // 音视频帧头长度: 0xA5 + 0x01 + type(1) + dataLen(4) + seqId(2) = 9
    public static final int AV_HEADER_SIZE = 9;
    // 音视频帧尾长度: 1
    public static final int AV_TAIL_SIZE = 1;

    // ========== 消息类型 ==========
    /** AIUI 数据包类型 */
    public static final byte TYPE_AIUI = 0x01;

    /** 音频 VAD 帧 */
    public static final byte TYPE_AUDIO_VAD = 0x0A;
    /** 录制音频帧 */
    public static final byte TYPE_AUDIO_REC = 0x0C;

    // ========== AIUI 命令 ==========
    public static final int CMD_WRITE = 2;
    public static final int CMD_WAKEUP = 7;
    public static final int CMD_RESET_WAKEUP = 8;
    public static final int CMD_TTS = 27;
    public static final int CMD_SET_PARAMS = 10;
    public static final int CMD_QUERY_PARAMS = 25;
    public static final int CMD_START_RECORD = 22;
    public static final int CMD_STOP_RECORD = 23;

    // ========== AIUI 事件类型 ==========
    public static final int EVENT_ERROR = 2;
    public static final int EVENT_RESULT = 3;
    public static final int EVENT_WAKEUP = 4;
    public static final int EVENT_SLEEP = 5;
    public static final int EVENT_VAD = 6;
    public static final int EVENT_TTS = 15;

    // ========== AIUI 状态 ==========
    public static final int STATE_IDLE = 0;
    public static final int STATE_WORKING = 1;

    // ========== VAD 状态 ==========
    /** 静音 */
    public static final int VAD_SILENCE = 0;
    /** 开始说话 (BOS) */
    public static final int VAD_BOS = 1;
    /** 持续说话 (VOL) */
    public static final int VAD_VOL = 2;
    /** 结束说话 (EOS) */
    public static final int VAD_EOS = 3;

    // ========== 默认地址 ==========
    /** ASR 服务器默认地址 */
    public static final String DEFAULT_ASR_IP = "192.168.0.119";
    /** 视频/音频流默认使用本机 */
    public static final String DEFAULT_LOCAL_IP = "127.0.0.1";
    /** UART 控制端口 */
    public static final int UART_PORT = 19199;
    /** 音频流端口 */
    public static final int AUDIO_PORT = 9080;
    /** 视频流端口 */
    public static final int VIDEO_PORT = 9090;

    // ========== 回调接口 ==========

    public interface UartCallback {
        /** 收到 AIUI 事件（解压后的 JSON 字符串） */
        void onEvent(String eventType, int seqId, String jsonContent);
        /** 连接状态变化 */
        void onConnectionState(boolean connected);
        /** 错误 */
        void onError(String message);
    }

    public interface AudioCallback {
        /** 收到音频帧 (PCM 数据, VAD 状态) */
        void onAudioFrame(int vadStatus, int engineIndex, int frameIndex, byte[] pcmData);
        void onConnectionState(boolean connected);
        void onError(String message);
    }

    public interface VideoCallback {
        /** 收到视频帧 */
        void onVideoFrame(int frameIndex, byte[] frameData);
        void onConnectionState(boolean connected);
        void onError(String message);
    }

    // ========== UART 帧构建/解析 ==========

    /**
     * 构建 AIUI 控制包
     */
    public static byte[] buildUartPacket(byte type, int seqId, byte[] gzipData) throws IOException {
        int dataLen = gzipData.length;
        ByteBuffer buf = ByteBuffer.allocate(7 + dataLen);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(FRAME_HEADER_0);
        buf.put(FRAME_HEADER_1);
        buf.put(type);
        buf.putShort((short) dataLen);
        buf.putShort((short) seqId);
        buf.put(gzipData);
        return buf.array();
    }

    /**
     * 构建 AIUI 控制命令
     */
    public static byte[] buildControlCommand(int cmd, int seqId, String jsonContent) throws IOException {
        String json = "{\"type\":\"aiui_ctrl\",\"cmd\":" + cmd +
                      ",\"content\":" + jsonContent + ",\"seq\":" + seqId + "}";
        return buildUartPacket(TYPE_AIUI, seqId, gzipCompress(json));
    }

    /**
     * 构建心跳包
     */
    public static byte[] buildHeartbeat(int seqId) throws IOException {
        String json = "{\"type\":\"speech_service\",\"cmd\":0,\"seq\":" + seqId + "}";
        return buildUartPacket(TYPE_AIUI, seqId, gzipCompress(json));
    }

    /**
     * 构建唤醒命令
     */
    public static byte[] buildWakeupCommand(int seqId) throws IOException {
        return buildControlCommand(CMD_WAKEUP, seqId, "{}");
    }

    /**
     * 构建 TTS 命令
     */
    public static byte[] buildTtsCommand(int seqId, String text) throws IOException {
        String content = "{\"text\":\"" + escapeJson(text) + "\"}";
        return buildControlCommand(CMD_TTS, seqId, content);
    }

    /**
     * 解析 UART 帧头，返回 [dataLen, seqId]
     * 返回 null 表示帧头不完整
     */
    public static int[] parseUartHeader(byte[] header) {
        if (header.length < UART_HEADER_SIZE) return null;
        if (header[0] != FRAME_HEADER_0 || header[1] != FRAME_HEADER_1) return null;

        ByteBuffer buf = ByteBuffer.wrap(header, 3, 4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        int dataLen = buf.getShort() & 0xFFFF;
        int seqId = buf.getShort() & 0xFFFF;
        return new int[]{dataLen, seqId};
    }

    /**
     * 解析音视频帧头，返回 [dataLen, seqId]
     */
    public static int[] parseAvHeader(byte[] header) {
        if (header.length < AV_HEADER_SIZE) return null;
        if (header[0] != FRAME_HEADER_0 || header[1] != FRAME_HEADER_1) return null;

        ByteBuffer buf = ByteBuffer.wrap(header, 3, 6);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        int dataLen = buf.getInt();
        int seqId = buf.getShort() & 0xFFFF;
        return new int[]{dataLen, seqId};
    }

    /**
     * 解析音频帧数据
     * 格式: status(1B) | engineIndex(1B) | reserved(2B) | frameIndex(4B BE) | pcm_data
     */
    public static AudioFrame parseAudioFrame(byte[] data) {
        if (data == null || data.length < 8) return null;

        int vadStatus = data[0] & 0xFF;
        int engineIndex = data[1] & 0xFF;
        // reserved = data[2..3]
        int frameIndex = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16)
                       | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);

        int pcmLen = data.length - 8;
        byte[] pcmData = new byte[pcmLen];
        System.arraycopy(data, 8, pcmData, 0, pcmLen);

        return new AudioFrame(vadStatus, engineIndex, frameIndex, pcmData);
    }

    // ========== 工具方法 ==========

    public static byte[] gzipCompress(String str) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(str.getBytes("UTF-8"));
            gzip.finish();
        }
        return bos.toByteArray();
    }

    public static String gzipDecompress(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = gzip.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
        }
        return bos.toString("UTF-8");
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    // ========== 数据类 ==========

    public static class AudioFrame {
        public final int vadStatus;
        public final int engineIndex;
        public final int frameIndex;
        public final byte[] pcmData;

        public AudioFrame(int vadStatus, int engineIndex, int frameIndex, byte[] pcmData) {
            this.vadStatus = vadStatus;
            this.engineIndex = engineIndex;
            this.frameIndex = frameIndex;
            this.pcmData = pcmData;
        }

        public boolean isBos() { return vadStatus == VAD_BOS; }
        public boolean isEos() { return vadStatus == VAD_EOS; }
    }
}
