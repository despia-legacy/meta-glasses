package despia.ext.metaglasses;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Frames, clips and the Mock Device Kit's media.
 *
 * <p>Frame layout: with {@code compressVideo = false} DAT hands the app decoded YUV frames
 * (Meta, "Camera Streaming (Android)": "VideoFrame.buffer holds YUV pixel data"). The plane order
 * is not documented; this fixture reads it as I420 (Y, then U, then V, each tightly packed). The
 * mock feed is half red and half blue, so the harness sees a swapped U and V at once (red and blue
 * trade sides). Unverified on real glasses.
 */
final class GlassesMedia {
    private GlassesMedia() {
    }

    /** JPEG quality of the live preview: small frames for a smooth stream. */
    static final int PREVIEW_QUALITY = 80;
    /** JPEG quality of a saved photo. */
    static final int PHOTO_QUALITY = 92;

    /** Whether buf holds a whole I420 frame of w x h. */
    static boolean isI420(ByteBuffer buf, int w, int h) {
        return w > 0 && h > 0 && w % 2 == 0 && h % 2 == 0 && buf != null && buf.remaining() >= w * h * 3 / 2;
    }

    /** Turns I420 frames into JPEG; reuses its NV21 buffer, so one instance per frame thread. */
    static final class FrameEncoder {
        private byte[] nv21 = new byte[0];
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(128 * 1024);

        synchronized byte[] jpeg(ByteBuffer i420, int w, int h, int quality) {
            if (!isI420(i420, w, h)) return null;
            int ySize = w * h;
            int cSize = ySize / 4;
            if (nv21.length != ySize + 2 * cSize) nv21 = new byte[ySize + 2 * cSize];
            ByteBuffer b = i420.duplicate();
            int base = b.position();
            b.get(nv21, 0, ySize);
            int u = base + ySize;
            int v = u + cSize;
            for (int i = 0, o = ySize; i < cSize; i++) {
                nv21[o++] = b.get(v + i);
                nv21[o++] = b.get(u + i);
            }
            out.reset();
            if (!new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), quality, out)) {
                return null;
            }
            return out.toByteArray();
        }
    }

    static byte[] jpeg(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null;
        return out.toByteArray();
    }

    static void write(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".part");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
            o.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("could not save " + f.getName());
        }
    }

    /**
     * An H.264 MP4 clip encoded from I420 frames with the platform encoder. The size is taken
     * from the first frame; DAT may lower the resolution when Bluetooth bandwidth drops (Meta,
     * "Camera Streaming"), and a frame of another size ends the clip ({@link #sizeChanged}). Every
     * method is called with the instance's lock held, from the frame thread or a worker.
     */
    static final class ClipRecorder {
        private static final long FINISH_TIMEOUT_NS = 2_000_000_000L;
        /** Encoded samples held while the muxer waits for the other track's format. */
        private static final int MAX_PENDING = 512;
        private final File file;
        private final int fps;
        /** The stream's PCM rate and channels; 0 for a silent clip. */
        private final int audioRate;
        private final int audioChannels;
        private MediaCodec codec;
        private MediaCodec audio;
        private MediaMuxer muxer;
        private int track = -1;
        private int audioTrack = -1;
        private boolean muxing;
        private int samples;
        private int audioSamples;
        private int width;
        private int height;
        private long firstPts = -1;
        private long lastPts;
        private long lastAudioPts = -1;
        private boolean videoEnded;
        private boolean audioEnded;
        private boolean done;
        volatile boolean sizeChanged;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private final MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
        private final java.util.ArrayList<Object[]> pending = new java.util.ArrayList<>();

        ClipRecorder(File file, int fps) {
            this(file, fps, 0, 1);
        }

        ClipRecorder(File file, int fps, int audioRate, int audioChannels) {
            this.file = file;
            this.fps = fps;
            this.audioRate = audioRate;
            this.audioChannels = Math.max(1, Math.min(2, audioChannels));
        }

        /** Encodes one frame; false when the frame was not taken (no free input, or the clip ended). */
        synchronized boolean offer(ByteBuffer i420, int w, int h, long ptsUs) throws IOException {
            if (done || !isI420(i420, w, h)) return false;
            if (codec == null) open(w, h);
            if (w != width || h != height) {
                sizeChanged = true;
                return false;
            }
            int index = codec.dequeueInputBuffer(0);
            if (index < 0) {
                drain(false, 0);
                return false;
            }
            Image image = codec.getInputImage(index);
            if (image == null) {
                codec.queueInputBuffer(index, 0, 0, 0, 0);
                return false;
            }
            fill(image, i420, w, h);
            if (firstPts < 0) firstPts = ptsUs;
            lastPts = ptsUs;
            codec.queueInputBuffer(index, 0, w * h * 3 / 2, ptsUs - firstPts, 0);
            drain(false, 0);
            return true;
        }

        /**
         * Encodes the stream's 16-bit PCM as AAC beside the video, from the first video frame on
         * (earlier audio has no clip time). False when it was not taken.
         */
        synchronized boolean offerAudio(ByteBuffer pcm, long ptsUs) {
            if (done || audio == null || firstPts < 0 || ptsUs < firstPts || pcm == null) return false;
            ByteBuffer src = pcm.duplicate();
            long pts = ptsUs - firstPts;
            if (pts <= lastAudioPts) return false;
            int taken = 0;
            while (src.hasRemaining()) {
                int index = audio.dequeueInputBuffer(0);
                if (index < 0) {
                    drain(false, 0);
                    return false;
                }
                ByteBuffer in = audio.getInputBuffer(index);
                if (in == null) return false;
                in.clear();
                int n = Math.min(in.remaining(), src.remaining());
                ByteBuffer slice = src.duplicate();
                slice.limit(slice.position() + n);
                in.put(slice);
                src.position(src.position() + n);
                audio.queueInputBuffer(index, 0, n, pts, 0);
                lastAudioPts = pts;
                // The next chunk starts n bytes later: 2 bytes per sample per channel.
                pts += n * 1_000_000L / (2L * audioChannels * audioRate);
                taken += n;
            }
            drain(false, 0);
            return taken > 0;
        }

        private void open(int w, int h) throws IOException {
            width = w;
            height = h;
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            f.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1_000_000, w * h * 4));
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            if (audioRate > 0) {
                MediaFormat a = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioRate, audioChannels);
                a.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                a.setInteger(MediaFormat.KEY_BIT_RATE, 64_000 * audioChannels);
                a.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
                audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                audio.configure(a, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                audio.start();
            }
            muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        /** Drains both encoders; with end, until both reported end of stream or the deadline. */
        private boolean drain(boolean end, long deadline) {
            boolean v = videoEnded || drainOne(codec, info, false, end, deadline);
            boolean a = audio == null || audioEnded || drainOne(audio, audioInfo, true, end, deadline);
            return v && a;
        }

        private boolean drainOne(MediaCodec c, MediaCodec.BufferInfo bi, boolean isAudio, boolean end, long deadline) {
            while (true) {
                if (end && System.nanoTime() - deadline >= 0) return false;
                int out = c.dequeueOutputBuffer(bi, end ? 10_000 : 0);
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!end) return false;
                    continue;
                }
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (isAudio) {
                        audioTrack = muxer.addTrack(c.getOutputFormat());
                    } else {
                        track = muxer.addTrack(c.getOutputFormat());
                    }
                    startMuxer();
                    continue;
                }
                if (out < 0) continue;
                ByteBuffer data = c.getOutputBuffer(out);
                boolean config = (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (data != null && !config && bi.size > 0) {
                    data.position(bi.offset);
                    data.limit(bi.offset + bi.size);
                    write(isAudio, data, bi);
                }
                c.releaseOutputBuffer(out, false);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (isAudio) audioEnded = true; else videoEnded = true;
                    return true;
                }
            }
        }

        /** The muxer starts once every track it carries has its format. */
        private void startMuxer() {
            if (muxing || track < 0 || (audio != null && audioTrack < 0)) return;
            muxer.start();
            muxing = true;
            for (Object[] p : pending) {
                MediaCodec.BufferInfo bi = (MediaCodec.BufferInfo) p[2];
                ByteBuffer b = ByteBuffer.wrap((byte[]) p[1]);
                bi.offset = 0;
                writeNow((Boolean) p[0], b, bi);
            }
            pending.clear();
        }

        private void write(boolean isAudio, ByteBuffer data, MediaCodec.BufferInfo bi) {
            if (muxing) {
                writeNow(isAudio, data, bi);
                return;
            }
            if (pending.size() >= MAX_PENDING) return;
            byte[] copy = new byte[data.remaining()];
            data.duplicate().get(copy);
            MediaCodec.BufferInfo held = new MediaCodec.BufferInfo();
            held.set(0, copy.length, bi.presentationTimeUs, bi.flags);
            pending.add(new Object[] {isAudio, copy, held});
        }

        private void writeNow(boolean isAudio, ByteBuffer data, MediaCodec.BufferInfo bi) {
            muxer.writeSampleData(isAudio ? audioTrack : track, data, bi);
            if (isAudio) audioSamples++; else samples++;
        }

        /** Ends the clip and closes the file. True when the MP4 holds at least one frame. */
        synchronized boolean finish() {
            if (done) return false;
            done = true;
            if (codec == null) return false;
            boolean ok = false;
            try {
                if (muxer != null) {
                    long deadline = System.nanoTime() + FINISH_TIMEOUT_NS;
                    int index = -1;
                    while (index < 0 && System.nanoTime() - deadline < 0) index = codec.dequeueInputBuffer(10_000);
                    if (audio != null) {
                        int ai = -1;
                        while (ai < 0 && System.nanoTime() - deadline < 0) ai = audio.dequeueInputBuffer(10_000);
                        if (ai >= 0) {
                            audio.queueInputBuffer(ai, 0, 0, Math.max(0, lastAudioPts + 1), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            audioEnded = true;
                        }
                    }
                    if (index >= 0) {
                        codec.queueInputBuffer(index, 0, 0, lastPts - firstPts + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        drain(true, deadline);
                        // Audio that never produced a format leaves a silent clip, not a lost one.
                        if (!muxing && track >= 0 && audio != null && audioTrack < 0) {
                            audio = releaseQuietly(audio);
                            startMuxer();
                        }
                        ok = videoEnded && muxing && samples > 0;
                    }
                }
            } catch (RuntimeException e) {
                ok = false;
            } finally {
                // A failed open can leave only a codec. Each release must survive the other's failure.
                try {
                    codec.stop();
                } catch (RuntimeException e) {
                    ok = false;
                }
                try {
                    codec.release();
                } catch (RuntimeException e) {
                    ok = false;
                }
                if (audio != null) audio = releaseQuietly(audio);
                if (muxer != null) {
                    if (muxing) {
                        try {
                            muxer.stop();
                        } catch (RuntimeException e) {
                            ok = false;
                        }
                    }
                    try {
                        muxer.release();
                    } catch (RuntimeException e) {
                        ok = false;
                    }
                }
            }
            return ok;
        }

        private static MediaCodec releaseQuietly(MediaCodec c) {
            try {
                c.stop();
            } catch (RuntimeException ignored) {
                // Already stopped or failed; release below still frees it.
            }
            try {
                c.release();
            } catch (RuntimeException ignored) {
                // Nothing more to free.
            }
            return null;
        }

        boolean hasAudio() {
            return audioSamples > 0;
        }

        long durationMs() {
            return firstPts < 0 ? 0 : (lastPts - firstPts) / 1000 + 1000 / Math.max(1, fps);
        }
    }

    /** Copies an I420 buffer into a codec input image of any YUV_420_888 layout. */
    static void fill(Image image, ByteBuffer i420, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer src = i420.duplicate();
        int base = src.position();
        int[] offset = {base, base + w * h, base + w * h + w * h / 4};
        for (int p = 0; p < 3; p++) {
            int pw = p == 0 ? w : w / 2;
            int ph = p == 0 ? h : h / 2;
            ByteBuffer dst = planes[p].getBuffer();
            int rowStride = planes[p].getRowStride();
            int pixelStride = planes[p].getPixelStride();
            for (int y = 0; y < ph; y++) {
                int row = offset[p] + y * pw;
                for (int x = 0; x < pw; x++) dst.put(y * rowStride + x * pixelStride, src.get(row + x));
            }
        }
    }

    // ---------------------------------------------------------------------- Mock Device Kit media

    /** Mock feed and photo size: the medium stream size, portrait (Meta, "Camera Streaming"). */
    static final int MOCK_W = 504;
    static final int MOCK_H = 896;
    static final int MOCK_FPS = 24;
    static final int MOCK_FRAMES = 72;

    /**
     * The mock test pattern, frame n: left half red, right half blue, and a white bar 32 rows high
     * moving down. The harness reads the colours back from /frame.jpg and the saved media.
     */
    static void pattern(byte[] i420, int w, int h, int n) {
        int ySize = w * h;
        int cw = w / 2;
        int bar = (n * 12) % h;
        for (int y = 0; y < h; y++) {
            boolean white = y >= bar && y < bar + 32;
            for (int x = 0; x < w; x++) {
                i420[y * w + x] = (byte) (white ? 235 : (x < w / 2 ? 81 : 41));
            }
        }
        for (int y = 0; y < h / 2; y++) {
            boolean white = y * 2 >= bar && y * 2 < bar + 32;
            for (int x = 0; x < cw; x++) {
                boolean left = x < cw / 2;
                i420[ySize + y * cw + x] = (byte) (white ? 128 : (left ? 90 : 240));
                i420[ySize + ySize / 4 + y * cw + x] = (byte) (white ? 128 : (left ? 240 : 110));
            }
        }
    }

    /** The mock photo: the same red and blue halves, as a JPEG. */
    static byte[] mockPhoto() {
        Bitmap b = Bitmap.createBitmap(MOCK_W, MOCK_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setColor(Color.RED);
        c.drawRect(0, 0, MOCK_W / 2f, MOCK_H, p);
        p.setColor(Color.BLUE);
        c.drawRect(MOCK_W / 2f, 0, MOCK_W, MOCK_H, p);
        byte[] out = jpeg(b, PHOTO_QUALITY);
        b.recycle();
        return out;
    }

    /**
     * Writes the mock camera feed: MOCK_FRAMES frames of the test pattern, H.265 when the device
     * has an HEVC encoder (the format Meta's mock guide asks for) and H.264 otherwise (the mock's
     * format table also lists AVC). Returns the codec used.
     */
    static String mockFeed(File file) throws IOException {
        String mime = hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC) ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat f = MediaFormat.createVideoFormat(mime, MOCK_W, MOCK_H);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, MOCK_FPS);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec codec = MediaCodec.createEncoderByType(mime);
        MediaMuxer muxer = null;
        boolean ok = false;
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            byte[] frame = new byte[MOCK_W * MOCK_H * 3 / 2];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int track = -1;
            boolean muxing = false;
            boolean inputDone = false;
            int n = 0;
            while (true) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        long pts = n * 1_000_000L / MOCK_FPS;
                        if (n == MOCK_FRAMES) {
                            codec.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            pattern(frame, MOCK_W, MOCK_H, n);
                            Image image = codec.getInputImage(in);
                            if (image != null) fill(image, ByteBuffer.wrap(frame), MOCK_W, MOCK_H);
                            codec.queueInputBuffer(in, 0, frame.length, pts, 0);
                            n++;
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxing = true;
                } else if (out >= 0) {
                    ByteBuffer data = codec.getOutputBuffer(out);
                    if (data != null && muxing && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        muxer.writeSampleData(track, data, info);
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            muxer.stop();
            ok = true;
            return mime;
        } finally {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // Not started.
            }
            codec.release();
            if (muxer != null) muxer.release();
            if (!ok) file.delete();
        }
    }

    private static boolean hasEncoder(String mime) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return true;
        }
        return false;
    }
}
