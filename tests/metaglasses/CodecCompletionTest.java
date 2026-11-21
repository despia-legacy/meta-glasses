package despia.ext.metaglasses;

import android.media.MediaCodec;
import android.media.MediaMuxer;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Exercises the actual ClipRecorder with fake codec/muxer collaborators. */
public final class CodecCompletionTest {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        System.setProperty("codec.mode", mode);
        GlassesMedia.ClipRecorder recorder = new GlassesMedia.ClipRecorder(new File(args[1]), 24);
        Method open = recorder.getClass().getDeclaredMethod("open", int.class, int.class);
        open.setAccessible(true);
        try { open.invoke(recorder, 2, 2); }
        catch (InvocationTargetException e) {
            if (!mode.equals("partial_open") || !(e.getCause() instanceof java.io.IOException)) throw e;
        }
        if (!mode.equals("partial_open")) {
            for (Method method : recorder.getClass().getDeclaredMethods()) {
                if (!method.getName().equals("drain")) continue;
                method.setAccessible(true);
                if (method.getParameterCount() == 1) method.invoke(recorder, false);
                else method.invoke(recorder, false, 0L);
            }
        }
        long started = System.nanoTime();
        boolean ok = recorder.finish();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        if (ok != mode.equals("eos")) throw new AssertionError(mode + " completion=" + ok);
        if (elapsedMs > 3500) throw new AssertionError("completion exceeded deadline: " + elapsedMs);
        if (MediaCodec.stops != 1 || MediaCodec.releases != 1) throw new AssertionError("codec cleanup was not exactly once");
        int expectedMuxer = mode.equals("partial_open") ? 0 : 1;
        if (MediaMuxer.releases != expectedMuxer) throw new AssertionError("muxer release bypassed");
        if (!mode.equals("partial_open") && MediaMuxer.stops != 1) throw new AssertionError("started muxer was not stopped");
        if (mode.equals("eos") && MediaMuxer.samples != 1) throw new AssertionError("successful clip needs a sample");
        if (recorder.finish()) throw new AssertionError("second completion succeeded");
        if (MediaCodec.releases != 1 || MediaMuxer.releases != expectedMuxer) throw new AssertionError("second completion released resources twice");
        System.out.println("PASS fake codec " + mode + " elapsedMs=" + elapsedMs);
    }
}
