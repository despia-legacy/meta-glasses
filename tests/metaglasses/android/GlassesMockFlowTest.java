package despia.ext.metaglasses.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.despia.nativeext.ExtCall;
import com.despia.nativeext.ExtContext;
import com.despia.nativeext.ExtResult;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import despia.ext.metaglasses.GlassesExtension;

/**
 * The debug mock flow, in order, through the extension's public NativeExtension surface on a real Android
 * runtime: the same calls a page makes (values typed as the runtime's validator types them: int as Long,
 * bool as Boolean, enum and string as String), each on the main thread, each waiting for its one reply.
 * Events go to this test's ExtContext. Copied into a composed Despia V3 workspace's app/src/androidTest by
 * the proof script; it needs a debuggable build (mock routes refuse in release builds by design).
 */
public class GlassesMockFlowTest {
    private static final String TAG = "GlassesMockFlow";
    private final List<JSONObject> events = Collections.synchronizedList(new ArrayList<>());
    private GlassesExtension ext;
    private int step;

    private ExtResult call(String route, Object... kv) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        ExtCall c = new ExtCall("metaglasses", route, p, null, SystemClock.uptimeMillis() + 25000, () -> true);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ExtResult> out = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ext.handle(c, r -> {
            if (out.compareAndSet(null, r)) done.countDown();
        }));
        boolean answered = done.await(30, TimeUnit.SECONDS);
        ExtResult r = out.get();
        JSONObject row = new JSONObject().put("step", step++).put("route", route).put("params", new JSONObject(p))
                .put("answered", answered).put("result", r == null ? JSONObject.NULL : new JSONObject(r.toJson()));
        Log.i(TAG, "CALL " + row);
        assertTrue(route + " did not answer", answered);
        return r;
    }

    private JSONObject ok(String route, Object... kv) throws Exception {
        ExtResult r = call(route, kv);
        assertTrue(route + " failed: " + r.code() + " " + r.message(), r.isOk());
        return r.data();
    }

    private static void pause(long ms) {
        SystemClock.sleep(ms);
    }

    private boolean sawEvent(String name, String key) {
        synchronized (events) {
            for (JSONObject e : events) if (name.equals(e.optString("event")) && (key == null || e.optJSONObject("data").has(key))) return true;
        }
        return false;
    }

    @Test
    public void mockFlowInOrder() throws Exception {
        Instrumentation in = InstrumentationRegistry.getInstrumentation();
        Context app = in.getTargetContext().getApplicationContext();
        File dir = new File(app.getFilesDir(), "nx-flow-test");
        dir.mkdirs();
        ext = new GlassesExtension();
        ExtContext ctx = new ExtContext() {
            @Override public String extensionId() { return "metaglasses"; }
            @Override public Context app() { return app; }
            @Override public File dataDir() { return dir; }
            @Override public void emit(String event, JSONObject data) {
                try {
                    JSONObject e = new JSONObject().put("event", event).put("data", data);
                    events.add(e);
                    Log.i(TAG, "EVENT " + e);
                } catch (Exception ignored) {
                    // A plain object from the extension.
                }
            }
            @Override public void log(String message) { Log.i(TAG, "LOG " + message); }
            @Override public boolean isApprovedUrl(String url, String route, String param) { return url.startsWith("https://"); }
            @Override public String resultKey(String name) { return "metaglasses:" + name; }
        };
        in.runOnMainSync(() -> ext.onCreate(ctx));

        String tree = "{\"type\":\"box\",\"direction\":\"column\",\"spacing\":8,\"padding\":16,\"children\":["
                + "{\"type\":\"text\",\"text\":\"Next stop\",\"style\":\"heading\"},"
                + "{\"type\":\"buttons\",\"children\":[{\"type\":\"button\",\"label\":\"Done\",\"id\":\"done\",\"primary\":true}]}]}";

        JSONObject mock = ok("mock", "enabled", true, "model", "meta_rayban_display");
        pause(1500);
        JSONObject st = ok("status");
        assertTrue("mock is on", st.getBoolean("mock"));
        JSONObject devs = ok("devices");
        assertTrue("one simulated device", devs.getJSONArray("devices").length() >= 1);
        ok("permission");
        ok("session_start");
        ok("start", "quality", "low", "fps", 15L, "audio", true, "audio_rate", "16000", "audio_channels", 1L);
        pause(3000);
        JSONObject live = ok("status");
        assertEquals("streaming", live.getString("state"));
        ok("frame");
        JSONObject photo = ok("photo");
        assertEquals("jpg", photo.getString("kind"));
        // A still needs the stream stopped: the stream and the standalone photo share the camera (Meta, "Camera Capture").
        assertEquals("busy", call("photo", "resolution", "medium", "quality", "medium").code());
        ok("record_start", "seconds", 2L);
        pause(3500);
        call("record_stop");
        JSONObject media = ok("media");
        assertTrue("saved media", media.getJSONArray("items").length() >= 2);

        ok("speech_start");
        ok("mock_event", "event", "transcription", "text", "hello glasses", "value", 1L);
        pause(800);
        ok("speech_stop");
        ok("motion_start", "rate", "10");
        pause(1000);
        ok("motion_stop");
        ok("inputs_start");
        ok("mock_event", "event", "tap");
        ok("mock_event", "event", "nav_up");
        ok("mock_event", "event", "capture", "text", "hold");
        pause(800);
        ok("inputs_stop");
        ok("voice_start");
        ok("mock_event", "event", "voice_launch");
        pause(800);
        ok("voice_stop");
        ok("display_start");
        ok("display_send", "content", tree);
        ok("mock_event", "event", "display_click", "text", "done");
        pause(800);
        ExtResult bad = call("display_send", "content", "{\"type\":\"buttons\",\"children\":[{\"type\":\"text\",\"text\":\"x\"}]}");
        assertEquals("bad_request", bad.code());
        ok("display_clear");
        ok("display_stop");
        ok("pause");
        ok("resume");
        ok("mock_event", "event", "battery", "value", 42L);
        ok("mock_event", "event", "thermal", "value", 4L);
        pause(800);
        ok("devices");
        ok("stop");
        JSONObject still = ok("photo", "resolution", "medium", "quality", "medium");
        assertEquals("medium", still.getString("resolution"));
        assertTrue("still saved", still.getLong("bytes") > 0);
        ok("session_stop");
        ok("mock", "enabled", false);
        JSONObject end = ok("status");
        assertTrue("mock is off", !end.getBoolean("mock"));

        Log.i(TAG, "EVENTS " + events.size() + " media=" + sawEvent("media", null) + " session=" + sawEvent("session", null)
                + " speech=" + sawEvent("speech", "text") + " motion=" + sawEvent("motion", "samples")
                + " input=" + sawEvent("input", "kind") + " voice=" + sawEvent("voice", "id")
                + " display_click=" + sawEvent("display", "click") + " photo_progress=" + sawEvent("photo_progress", null) + " device=" + sawEvent("device", null)
                + " mock=" + mock);
    }
}
