package br.com.cinemajunto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The same capture clock is carried with both audio and video. */
public final class StreamPacket {
    public static final int HEADER_SIZE = 13;
    public static final int PCM = 4;
    public static final int AVC_CONFIG = 5;
    public static final int AVC_FRAME = 6;
    public static final int MAX_PACKET_SIZE = 512 * 1024;

    private StreamPacket() {}

    public static final class Packet {
        public final int type;
        public final long timestampUs;
        public final int flags;
        public final byte[] payload;
        Packet(int type, long timestampUs, int flags, byte[] payload) {
            this.type = type; this.timestampUs = timestampUs;
            this.flags = flags; this.payload = payload;
        }
    }

    public static final class VideoConfig {
        public final int width, height;
        public final byte[] csd0, csd1;
        VideoConfig(int width, int height, byte[] csd0, byte[] csd1) {
            this.width = width; this.height = height;
            this.csd0 = csd0; this.csd1 = csd1;
        }
    }

    public static byte[] pack(int type, long timestampUs, int flags, byte[] payload) {
        if (type < 1 || type > 255 || timestampUs < 0 || payload == null
                || payload.length > MAX_PACKET_SIZE - HEADER_SIZE) throw new IllegalArgumentException("Pacote inválido");
        return ByteBuffer.allocate(HEADER_SIZE + payload.length).order(ByteOrder.BIG_ENDIAN)
            .put((byte) type).putLong(timestampUs).putInt(flags).put(payload).array();
    }

    public static Packet unpack(byte[] data) {
        if (data == null || data.length < HEADER_SIZE || data.length > MAX_PACKET_SIZE)
            throw new IllegalArgumentException("Pacote inválido");
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int type = buffer.get() & 255;
        long timestampUs = buffer.getLong();
        int flags = buffer.getInt();
        if (type == 0 || timestampUs < 0) throw new IllegalArgumentException("Pacote inválido");
        byte[] payload = new byte[buffer.remaining()]; buffer.get(payload);
        return new Packet(type, timestampUs, flags, payload);
    }

    public static byte[] videoConfig(int width, int height, byte[] csd0, byte[] csd1) {
        if (csd1 == null) csd1 = new byte[0];
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                || csd0 == null || csd0.length == 0
                || (long) csd0.length + csd1.length > MAX_PACKET_SIZE - HEADER_SIZE - 16)
            throw new IllegalArgumentException("Configuração de vídeo inválida");
        return ByteBuffer.allocate(16 + csd0.length + csd1.length).order(ByteOrder.BIG_ENDIAN)
            .putInt(width).putInt(height).putInt(csd0.length).putInt(csd1.length).put(csd0).put(csd1).array();
    }

    public static VideoConfig parseVideoConfig(byte[] data) {
        if (data == null || data.length < 16 || data.length > MAX_PACKET_SIZE - HEADER_SIZE)
            throw new IllegalArgumentException("Configuração de vídeo inválida");
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int width = buffer.getInt(), height = buffer.getInt();
        int length0 = buffer.getInt(), length1 = buffer.getInt();
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096 || length0 <= 0
                || length1 < 0 || (long) length0 + length1 != buffer.remaining())
            throw new IllegalArgumentException("Configuração de vídeo inválida");
        byte[] csd0 = new byte[length0], csd1 = new byte[length1]; buffer.get(csd0); buffer.get(csd1);
        return new VideoConfig(width, height, csd0, csd1);
    }
}
