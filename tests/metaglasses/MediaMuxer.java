package android.media;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Test double with independent stop and release failures. */
public final class MediaMuxer {
    public static int stops;
    public static int releases;
    public static int samples;
    public MediaMuxer(String path, int format) throws IOException {
        if (System.getProperty("codec.mode").equals("partial_open")) throw new IOException("muxer open failed");
    }
    public int addTrack(MediaFormat format) { return 0; }
    public void start() { }
    public void writeSampleData(int track, ByteBuffer bytes, MediaCodec.BufferInfo head) { samples++; }
    public void stop() {
        stops++;
        if (System.getProperty("codec.mode").equals("muxer_stop_error")) throw new IllegalStateException("muxer stop failed");
    }
    public void release() {
        releases++;
        if (System.getProperty("codec.mode").equals("muxer_release_error")) throw new IllegalStateException("muxer release failed");
    }
}
