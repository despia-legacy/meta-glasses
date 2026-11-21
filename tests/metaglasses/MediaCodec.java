package android.media;

import android.view.Surface;
import java.nio.ByteBuffer;

/** Deterministic encoder double, not Android codec or device proof. */
public final class MediaCodec {
    public static int stops;
    public static int releases;
    private int outputStep;
    private boolean eosQueued;
    private static String mode() { return System.getProperty("codec.mode"); }
    public static final class BufferInfo {
        public int offset;
        public int size;
        public long presentationTimeUs;
        public int flags;
    }
    public static MediaCodec createEncoderByType(String mime) { return new MediaCodec(); }
    public void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) { }
    public void start() { }
    public int dequeueInputBuffer(long timeoutUs) {
        if (!mode().equals("no_input")) return 0;
        pause();
        return -1;
    }
    public Image getInputImage(int index) { return null; }
    public void queueInputBuffer(int index, int offset, int size, long pts, int flags) {
        if ((flags & 4) != 0) eosQueued = true;
    }
    public int dequeueOutputBuffer(BufferInfo head, long timeoutUs) {
        head.flags = 0;
        head.size = 0;
        if (outputStep++ == 0) return -2;
        if (outputStep == 2 && !mode().equals("empty_eos")) {
            head.size = 1;
            return 0;
        }
        if (eosQueued && !mode().equals("no_eos")) { head.flags = 4; return 0; }
        pause();
        return -1;
    }
    private static void pause() {
        try { Thread.sleep(10); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    public MediaFormat getOutputFormat() { return new MediaFormat(); }
    public ByteBuffer getOutputBuffer(int index) { return ByteBuffer.wrap(new byte[] {1}); }
    public void releaseOutputBuffer(int index, boolean render) { }
    public void stop() { stops++; }
    public void release() {
        releases++;
        if (mode().equals("codec_release_error")) throw new IllegalStateException("codec release failed");
    }
}
