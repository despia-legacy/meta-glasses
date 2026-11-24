package despia.ext.metaglasses;

import android.os.Handler;
import android.os.Looper;

import com.despia.nativeext.ExtResult;
import com.meta.wearable.dat.core.Wearables;
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector;
import com.meta.wearable.dat.core.session.DeviceSession;
import com.meta.wearable.dat.core.types.DatResult;
import com.meta.wearable.dat.core.types.Device;
import com.meta.wearable.dat.core.types.DeviceIdentifier;
import com.meta.wearable.dat.core.types.DeviceSessionError;
import com.meta.wearable.dat.core.voiceinvocations.VoiceInvocationsStream;
import com.meta.wearable.dat.core.voiceinvocations.VoiceInvocationsStreamKt;
import com.meta.wearable.dat.core.voiceinvocations.types.VoiceInvocationError;
import com.meta.wearable.dat.core.voiceinvocations.types.actions.LaunchApp;
import com.meta.wearable.dat.core.voiceinvocations.types.actions.ResponseHandle;
import com.meta.wearable.dat.display.Display;
import com.meta.wearable.dat.display.SessionDisplayExtensionsKt;
import com.meta.wearable.dat.display.types.DisplayConfiguration;
import com.meta.wearable.dat.display.types.DisplayError;
import com.meta.wearable.dat.display.types.VideoCodec;
import com.meta.wearable.dat.display.types.VideoPlayerError;
import com.meta.wearable.dat.display.types.VideoPlayerState;
import com.meta.wearable.dat.display.types.VideoSource;
import com.meta.wearable.dat.display.views.ActionRole;
import com.meta.wearable.dat.display.views.Alignment;
import com.meta.wearable.dat.display.views.ButtonGroupAlignment;
import com.meta.wearable.dat.display.views.ButtonGroupScope;
import com.meta.wearable.dat.display.views.ButtonStyle;
import com.meta.wearable.dat.display.views.ContentScope;
import com.meta.wearable.dat.display.views.CornerRadius;
import com.meta.wearable.dat.display.views.Direction;
import com.meta.wearable.dat.display.views.FlexBoxBackground;
import com.meta.wearable.dat.display.views.FlexBoxScope;
import com.meta.wearable.dat.display.views.IconName;
import com.meta.wearable.dat.display.views.IconStyle;
import com.meta.wearable.dat.display.views.ImageSize;
import com.meta.wearable.dat.display.views.TextColor;
import com.meta.wearable.dat.display.views.TextStyle;
import com.meta.wearable.dat.display.views.VideoPlayer;
import com.meta.wearable.dat.inputs.Inputs;
import com.meta.wearable.dat.inputs.SessionInputsExtensionsKt;
import com.meta.wearable.dat.inputs.types.InputEvent;
import com.meta.wearable.dat.inputs.types.InputSource;
import com.meta.wearable.dat.inputs.types.InputsConfiguration;
import com.meta.wearable.dat.inputs.types.InputsError;
import com.meta.wearable.dat.motion.Motion;
import com.meta.wearable.dat.motion.SessionMotionExtensionsKt;
import com.meta.wearable.dat.motion.types.MotionConfiguration;
import com.meta.wearable.dat.motion.types.MotionError;
import com.meta.wearable.dat.motion.types.MotionSample;
import com.meta.wearable.dat.motion.types.MotionSamplingRate;
import com.meta.wearable.dat.motion.types.Quaternion;
import com.meta.wearable.dat.motion.types.Vector3;
import com.meta.wearable.dat.speech.SessionSpeechExtensionsKt;
import com.meta.wearable.dat.speech.Speech;
import com.meta.wearable.dat.speech.SpeechConfiguration;
import com.meta.wearable.dat.speech.types.SpeechError;
import com.meta.wearable.dat.speech.types.TranscriptionResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.meta.wearable.dat.display.types.DisplayState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import kotlin.Unit;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.flow.StateFlow;

/**
 * The DAT capabilities beside the camera, the Android twin of ios/app/GlassesCapabilities.swift and
 * GlassesDisplay.swift: device state, speech, motion, inputs, voice invocations and the display.
 * Each one attaches to the shared DeviceSession (GlassesExtension.openSession), starts only on its
 * route and ends on its stop route or with the session. Every word and error code is the same as
 * on iOS. Speech, motion, inputs and voice invocations are experimental in DAT 1.1.0 (Meta's
 * changelog): they build and run, and Meta does not yet allow apps that use them to be published.
 * Everything here runs on the main thread except the motion batcher.
 */
final class GlassesCapabilities {
    private GlassesCapabilities() {
    }

    interface Emit {
        void emit(String event, JSONObject data);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** DAT's enum names, lower case: the same words iOS reports. */
    static String word(Enum<?> e) {
        return e == null ? "unknown" : e.name().toLowerCase(Locale.ROOT);
    }

    static String describe(com.meta.wearable.dat.core.types.DatError e) {
        if (e == null) return "unknown error";
        String d = e.getDescription();
        return d == null || d.isEmpty() ? e.toString() : d;
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]);
        } catch (JSONException ignored) {
            // Plain keys and values always build.
        }
        return o;
    }

    /** One device, the same keys as iOS; Android adds the firmware version (Device.firmwareInfo). */
    static JSONObject device(String id, Device d) {
        int battery = d.getBatteryLevel();
        return obj("id", id, "name", d.getName(), "type", word(d.getDeviceType()), "link", word(d.getLinkState()),
                "compatibility", word(d.getCompatibility()), "battery", battery < 0 ? null : battery,
                "charging", word(d.getChargingState()), "worn", word(d.getDonState()), "hinge", word(d.getHingeState()),
                "thermal", word(d.getThermalLevel()), "display", d.isDisplayCapable(), "firmware", d.getFirmwareInfo());
    }

    // ------------------------------------------------------------------------------- errors

    static String sessionCode(DeviceSessionError e) {
        if (e == null) return "session_failed";
        switch (e) {
            case NO_ELIGIBLE_DEVICE:
            case DEVICE_DISCONNECTED:
                return "no_device";
            case NOT_REGISTERED:
                return "registration_required";
            case CAPABILITY_DENIED:
                return "permission_denied";
            case SESSION_ALREADY_EXISTS:
            case CAPABILITY_ALREADY_ADDED:
                return "busy";
            case THERMAL_CRITICAL:
            case THERMAL_EMERGENCY:
            case PEAK_POWER_SHUTDOWN:
            case BATTERY_CRITICAL:
                return "device_limit";
            case DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED:
                return "glasses_app_update_required";
            case INSUFFICIENT_SDK_VERSION:
                return "sdk_update_required";
            case DWA_UNAVAILABLE:
                return "unavailable";
            case DWA_OUT_OF_STU_RANGE:
                return "update_suggested";
            case SESSION_IDLE:
            case SESSION_ALREADY_STOPPED:
            case SESSION_ENDED_BY_DEVICE:
                return "session_stopped";
            case CAPABILITY_NOT_FOUND:
                return "not_active";
            default:
                return "session_failed";
        }
    }

    static ExtResult session(DeviceSessionError e) {
        return ExtResult.error(sessionCode(e), describe(e));
    }

    static String speechCode(SpeechError e) {
        if (e == SpeechError.DEVICE_DISCONNECTED) return "no_device";
        if (e == SpeechError.ALREADY_LISTENING) return "busy";
        if (e == SpeechError.UNAVAILABLE) return "unavailable";
        return "speech_failed";
    }

    static String motionCode(MotionError e) {
        if (e == MotionError.DEVICE_DISCONNECTED) return "no_device";
        if (e == MotionError.SENSOR_UNAVAILABLE) return "unavailable";
        return "motion_failed";
    }

    static String inputsCode(InputsError e) {
        if (e == InputsError.PERMISSION_DENIED) return "permission_denied";
        if (e == InputsError.DEVICE_DISCONNECTED) return "no_device";
        if (e == InputsError.ACTIVATION_TIMEOUT) return "timeout";
        return "inputs_failed";
    }

    static String voiceCode(VoiceInvocationError e) {
        if (e == VoiceInvocationError.CHANNEL_NOT_CONNECTED) return "no_device";
        return "voice_failed";
    }

    // ------------------------------------------------------------------------------ devices

    /** Watches the device list and every device's metadata; each change is the `device` event. */
    static final class Devices {
        private final Emit emit;
        private final Map<String, Job> perDevice = new HashMap<>();
        private final Set<String> announced = new HashSet<>();
        private CoroutineScope scope;

        Devices(Emit emit) {
            this.emit = emit;
        }

        void start(CoroutineScope s) {
            scope = s;
            DatInterop.collect(s, Wearables.INSTANCE.getDevices(), this::changed);
        }

        JSONArray list() {
            JSONArray a = new JSONArray();
            Map<DeviceIdentifier, StateFlow<Device>> meta = Wearables.INSTANCE.getDevicesMetadata();
            for (DeviceIdentifier id : Wearables.INSTANCE.getDevices().getValue()) {
                StateFlow<Device> f = meta.get(id);
                Device d = f == null ? null : f.getValue();
                if (d != null) a.put(device(id.getIdentifier(), d));
            }
            return a;
        }

        private void changed(Set<DeviceIdentifier> ids) {
            Set<String> now = new HashSet<>();
            for (DeviceIdentifier id : ids) now.add(id.getIdentifier());
            for (String id : new ArrayList<>(perDevice.keySet())) {
                if (now.contains(id)) continue;
                DatInterop.cancel(perDevice.remove(id));
                announced.remove(id);
                emit.emit("device", obj("id", id, "change", "removed", "devices", list()));
            }
            Map<DeviceIdentifier, StateFlow<Device>> meta = Wearables.INSTANCE.getDevicesMetadata();
            for (DeviceIdentifier id : ids) {
                String key = id.getIdentifier();
                StateFlow<Device> f = meta.get(id);
                if (perDevice.containsKey(key) || f == null) continue;
                perDevice.put(key, DatInterop.collect(scope, f, d -> {
                    if (d == null) return;
                    JSONObject o = device(key, d);
                    try {
                        o.put("change", announced.add(key) ? "added" : "state");
                    } catch (JSONException ignored) {
                        // A plain key always sets.
                    }
                    emit.emit("device", o);
                }));
            }
        }
    }

    // ------------------------------------------------------------------------------ sensors

    /** Speech, motion and inputs on the shared session, and voice invocations on the device. */
    static final class Sensors {
        /** Motion samples reach the page batched, one `motion` event per BATCH_MS. */
        static final long BATCH_MS = 100;
        static final Map<String, InputSource> SOURCES = new LinkedHashMap<>();

        static {
            SOURCES.put("captouch", InputSource.CAPTOUCH);
            SOURCES.put("neural_band", InputSource.NEURAL_BAND);
            SOURCES.put("capture_button", InputSource.CAPTURE_BUTTON);
            SOURCES.put("action_button", InputSource.ACTION_BUTTON);
            SOURCES.put("neural_band_drag", InputSource.NEURAL_BAND_DRAG);
        }

        private final Emit emit;
        Speech speech;
        Motion motion;
        Inputs inputs;
        private CoroutineScope speechScope;
        private CoroutineScope motionScope;
        private CoroutineScope motionBatchScope;
        private CoroutineScope inputsScope;
        private VoiceInvocationsStream voice;
        private CoroutineScope voiceScope;
        private String voiceDevice;
        private final Map<String, ResponseHandle> pending = new LinkedHashMap<>();
        private int voiceSeq;
        private final List<JSONObject> batch = new ArrayList<>();

        Sensors(Emit emit) {
            this.emit = emit;
        }

        boolean active() {
            return speech != null || motion != null || inputs != null;
        }

        // speech

        ExtResult speechStart(DeviceSession s) {
            if (speech != null) return ExtResult.error("busy", "speech is already listening");
            DatResult<Speech, DeviceSessionError> r = SessionSpeechExtensionsKt.addSpeech(s, new SpeechConfiguration());
            if (!r.isSuccess() || r.getOrNull() == null) return session(r.errorOrNull());
            Speech sp = r.getOrNull();
            CoroutineScope scope = DatInterop.mainScope();
            DatInterop.collect(scope, sp.getState(), st -> emit.emit("speech", obj("state", word(st))));
            DatInterop.collect(scope, sp.getTranscriptions(), (TranscriptionResult t) -> {
                if (t == null) return;
                emit.emit("speech", obj("state", "started", "text", t.getText(), "final", t.isFinal(), "confidence", (double) t.getConfidence()));
            });
            DatInterop.collect(scope, sp.getLocale(), l -> {
                if (l != null) emit.emit("speech", obj("locale", l));
            });
            DatInterop.collect(scope, sp.getErrors(), (SpeechError e) -> {
                if (e != null) emit.emit("speech", obj("error", speechCode(e), "message", describe(e)));
            });
            speech = sp;
            speechScope = scope;
            DatResult<Unit, SpeechError> started = sp.start();
            if (!started.isSuccess()) {
                SpeechError e = started.errorOrNull();
                speechStop(s);
                return ExtResult.error(speechCode(e), describe(e));
            }
            return ExtResult.ok(obj("state", word(sp.getState().getValue()), "locale", sp.getLocale().getValue()));
        }

        ExtResult speechStop(DeviceSession s) {
            if (speech == null) return ExtResult.error("not_listening", "speech is not listening");
            speech.stop();
            if (s != null) SessionSpeechExtensionsKt.removeSpeech(s);
            DatInterop.cancel(speechScope);
            speechScope = null;
            speech = null;
            return ExtResult.ok(obj("state", "stopped"));
        }

        // motion

        static MotionSamplingRate rate(int hz) {
            switch (hz) {
                case 5: return MotionSamplingRate.HZ_5;
                case 15: return MotionSamplingRate.HZ_15;
                case 24: return MotionSamplingRate.HZ_24;
                case 30: return MotionSamplingRate.HZ_30;
                case 60: return MotionSamplingRate.HZ_60;
                default: return MotionSamplingRate.HZ_10;
            }
        }

        ExtResult motionStart(DeviceSession s, int hz) {
            if (motion != null) return ExtResult.error("busy", "motion already streams");
            DatResult<Motion, DeviceSessionError> r = SessionMotionExtensionsKt.addMotion(s, new MotionConfiguration(rate(hz)));
            if (!r.isSuccess() || r.getOrNull() == null) return session(r.errorOrNull());
            Motion m = r.getOrNull();
            CoroutineScope scope = DatInterop.mainScope();
            CoroutineScope samples = DatInterop.backgroundScope();
            DatInterop.collect(scope, m.getState(), st -> emit.emit("motion", obj("state", word(st))));
            DatInterop.collect(scope, m.getErrors(), (MotionError e) -> {
                if (e != null) emit.emit("motion", obj("error", motionCode(e), "message", describe(e)));
            });
            long[] last = {android.os.SystemClock.uptimeMillis()};
            DatInterop.collect(samples, m.getSamples(), (MotionSample x) -> {
                JSONArray out = null;
                synchronized (batch) {
                    batch.add(sample(x));
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - last[0] >= BATCH_MS) {
                        out = new JSONArray(batch);
                        batch.clear();
                        last[0] = now;
                    }
                }
                if (out != null) {
                    JSONArray o = out;
                    MAIN.post(() -> emit.emit("motion", obj("samples", o)));
                }
            });
            motion = m;
            motionScope = scope;
            motionBatchScope = samples;
            m.start();
            return ExtResult.ok(obj("state", word(m.getState().getValue()), "rate", hz));
        }

        static JSONObject sample(MotionSample s) {
            String source = s.getSource() == null ? "unknown" : word(s.getSource());
            Quaternion q = s.getOrientation();
            return obj("t", s.getTimestampNs(), "accel", vec(s.getAccelerometer()), "gyro", vec(s.getGyroscope()),
                    "mag", vec(s.getMagnetometer()),
                    "orientation", q == null ? null : arr(q.getX(), q.getY(), q.getZ(), q.getW()), "source", source);
        }

        private static Object vec(Vector3 v) {
            return v == null ? null : arr(v.getX(), v.getY(), v.getZ());
        }

        private static JSONArray arr(float... f) {
            JSONArray a = new JSONArray();
            try {
                for (float x : f) a.put((double) x);
            } catch (JSONException ignored) {
                // A finite float always puts; a NaN from the sensor is dropped.
            }
            return a;
        }

        ExtResult motionStop(DeviceSession s) {
            if (motion == null) return ExtResult.error("not_streaming", "motion does not stream");
            motion.stop();
            if (s != null) SessionMotionExtensionsKt.removeMotion(s);
            DatInterop.cancel(motionScope);
            DatInterop.cancel(motionBatchScope);
            motionScope = null;
            motionBatchScope = null;
            motion = null;
            synchronized (batch) {
                batch.clear();
            }
            return ExtResult.ok(obj("state", "stopped"));
        }

        // inputs

        ExtResult inputsStart(DeviceSession s, String sources, boolean consumeBack) {
            if (inputs != null) return ExtResult.error("busy", "inputs are already on");
            Set<InputSource> set = new HashSet<>();
            for (String name : (sources == null ? "" : sources).split(",")) {
                String n = name.trim();
                if (n.isEmpty()) continue;
                InputSource src = SOURCES.get(n);
                if (src == null) return ExtResult.error("bad_request", "unknown input source " + n);
                set.add(src);
            }
            // DAT's default set when none is named, as on iOS.
            if (set.isEmpty()) set.addAll(new InputsConfiguration().getSources());
            DatResult<Inputs, DeviceSessionError> r = SessionInputsExtensionsKt.addInputs(s, new InputsConfiguration(set, consumeBack));
            if (!r.isSuccess() || r.getOrNull() == null) return session(r.errorOrNull());
            Inputs i = r.getOrNull();
            CoroutineScope scope = DatInterop.mainScope();
            DatInterop.collect(scope, i.getState(), st -> emit.emit("input", obj("state", word(st))));
            DatInterop.collect(scope, i.getErrors(), (InputsError e) -> {
                if (e != null) emit.emit("input", obj("error", inputsCode(e), "message", describe(e)));
            });
            DatInterop.collect(scope, i.getEvents(), e -> emit.emit("input", inputEvent(e)));
            inputs = i;
            inputsScope = scope;
            return ExtResult.ok(obj("state", word(i.getState().getValue()), "consumeBack", consumeBack));
        }

        static JSONObject inputEvent(InputEvent e) {
            JSONObject o = obj("source", e.getSource() == null ? "unknown" : word(e.getSource()), "t", e.getTimestampMs());
            try {
                if (e instanceof InputEvent.Nav) {
                    o.put("kind", "nav").put("direction", word(((InputEvent.Nav) e).getDirection()));
                } else if (e instanceof InputEvent.Select) {
                    o.put("kind", "select");
                } else if (e instanceof InputEvent.Back) {
                    o.put("kind", "back");
                } else if (e instanceof InputEvent.Button) {
                    o.put("kind", "button").put("button", word(((InputEvent.Button) e).getButton()));
                } else if (e instanceof InputEvent.Capture) {
                    String p = word(((InputEvent.Capture) e).getPressType());
                    o.put("kind", "capture").put("press", p.equals("short_press") ? "short" : p.equals("double_press") ? "double" : p);
                } else if (e instanceof InputEvent.Drag) {
                    InputEvent.Drag d = (InputEvent.Drag) e;
                    o.put("kind", "drag").put("action", word(d.getAction())).put("x", (double) d.getX()).put("y", (double) d.getY())
                            .put("dx", (double) d.getDx()).put("dy", (double) d.getDy());
                } else {
                    o.put("kind", "unknown");
                }
            } catch (JSONException ignored) {
                // Finite values from DAT always put.
            }
            return o;
        }

        ExtResult inputsStop(DeviceSession s) {
            if (inputs == null) return ExtResult.error("not_active", "inputs are off");
            if (s != null) SessionInputsExtensionsKt.removeInputs(s);
            DatInterop.cancel(inputsScope);
            inputsScope = null;
            inputs = null;
            return ExtResult.ok(obj("state", "inactive"));
        }

        /** The session ended: every capability on it is gone. */
        void sessionEnded() {
            if (speech != null) {
                speechStop(null);
                emit.emit("speech", obj("state", "stopped", "reason", "session_stopped"));
            }
            if (motion != null) {
                motionStop(null);
                emit.emit("motion", obj("state", "stopped", "reason", "session_stopped"));
            }
            if (inputs != null) {
                inputsStop(null);
                emit.emit("input", obj("state", "inactive", "reason", "session_stopped"));
            }
        }

        // voice invocations ("Hey Meta, open <app>")

        ExtResult voiceStart(String device) {
            if (voice != null) return ExtResult.ok(obj("state", "started", "device", voiceDevice));
            VoiceInvocationsStream v;
            try {
                v = VoiceInvocationsStreamKt.startVoiceInvocationsStream(Wearables.INSTANCE,
                        new SpecificDeviceSelector(new DeviceIdentifier(device)), Dispatchers.getDefault());
            } catch (RuntimeException e) {
                return ExtResult.error("voice_failed", String.valueOf(e.getMessage()));
            }
            CoroutineScope scope = DatInterop.mainScope();
            DatInterop.collect(scope, v.getInvocations(), inv -> {
                if (!(inv instanceof LaunchApp)) {
                    emit.emit("voice", obj("kind", "unknown"));
                    return;
                }
                voiceSeq++;
                String id = "v" + voiceSeq;
                pending.put(id, ((LaunchApp) inv).getResponseHandle());
                emit.emit("voice", obj("kind", "launch_app", "id", id, "device", device));
            });
            DatInterop.collect(scope, v.getErrors(), (VoiceInvocationError e) -> {
                if (e != null) emit.emit("voice", obj("error", voiceCode(e), "message", describe(e)));
            });
            DatInterop.collect(scope, v.getState(), st -> emit.emit("voice", obj("state", word(st))));
            voice = v;
            voiceScope = scope;
            voiceDevice = device;
            return ExtResult.ok(obj("state", "started", "device", device));
        }

        void voiceRespond(String id, boolean success, String output, ExtResult.Callback reply) {
            ResponseHandle h = pending.remove(id);
            if (h == null) {
                reply.reply(ExtResult.error("not_found", "no open voice invocation " + id));
                return;
            }
            DatInterop.<Boolean>call(c -> success ? h.sendSuccess(output, c) : h.sendFailure(output, c), (sent, error) ->
                    reply.reply(ExtResult.ok(obj("id", id, "sent", error == null && Boolean.TRUE.equals(sent)))));
        }

        ExtResult voiceStop() {
            if (voice == null) return ExtResult.error("not_active", "voice invocations are off");
            voice.close();
            DatInterop.cancel(voiceScope);
            voiceScope = null;
            voice = null;
            voiceDevice = null;
            pending.clear();
            return ExtResult.ok(obj("state", "stopped"));
        }

        JSONObject voiceState() {
            return obj("on", voice != null, "device", voiceDevice, "pending", new JSONArray(new TreeSet<>(pending.keySet())));
        }
    }

    // ------------------------------------------------------------------------------ display

    /**
     * The glasses' display (Meta Ray-Ban Display). The page sends a GlassesCore.DisplayTree as
     * JSON; taps on buttons and boxes with an id come back as the `display` event.
     */
    static final class Screen {
        private final Emit emit;
        Display display;
        private CoroutineScope scope;
        private final List<ExtResult.Callback> waiting = new ArrayList<>();
        private Runnable waitTimeout;
        private boolean begun;
        private VideoPlayer player;
        private CoroutineScope playerScope;

        Screen(Emit emit) {
            this.emit = emit;
        }

        boolean active() {
            return display != null;
        }

        /**
         * Answers once the display reports STARTED: Meta's Display guide (DAT 1.1.0, display-access) sends content
         * only after DisplayState.STARTED, and sendContent before it fails ("Invalid display state for operation").
         * Answers display_failed when the display stops before it starts, timeout after 20 s.
         */
        void start(DeviceSession s, ExtResult.Callback reply) {
            if (display == null) {
                Device d = s.getDeviceInfo().getValue();
                if (d != null && !d.isDisplayCapable()) {
                    reply.reply(ExtResult.error("unsupported_device", "these glasses have no display"));
                    return;
                }
                DatResult<Display, DeviceSessionError> r = SessionDisplayExtensionsKt.addDisplay(s, new DisplayConfiguration());
                if (!r.isSuccess() || r.getOrNull() == null) {
                    reply.reply(session(r.errorOrNull()));
                    return;
                }
                display = r.getOrNull();
                begun = false;
                scope = DatInterop.mainScope();
                // CLOSED is Android's end state after STOPPED; the page sees stopped, as on iOS.
                DatInterop.collect(scope, display.getState(), st -> {
                    emit.emit("display", obj("state", st == DisplayState.CLOSED ? "stopped" : word(st)));
                    if (st == DisplayState.STARTING || st == DisplayState.STARTED) begun = true;
                    if (st == DisplayState.STARTED) settle(ExtResult.ok(obj("state", "started")));
                    else if (begun && (st == DisplayState.STOPPED || st == DisplayState.CLOSED)) {
                        settle(ExtResult.error("display_failed", "the display stopped before it started"));
                    }
                });
            }
            if (started()) {
                reply.reply(ExtResult.ok(obj("state", "started")));
                return;
            }
            waiting.add(reply);
            if (waitTimeout == null) {
                waitTimeout = () -> {
                    waitTimeout = null;
                    settle(ExtResult.error("timeout", "the display did not start within 20 s"));
                };
                MAIN.postDelayed(waitTimeout, 20000);
            }
        }

        boolean started() {
            return display != null && display.getState().getValue() == DisplayState.STARTED;
        }

        private void settle(ExtResult r) {
            if (waitTimeout != null) MAIN.removeCallbacks(waitTimeout);
            waitTimeout = null;
            List<ExtResult.Callback> w = new ArrayList<>(waiting);
            waiting.clear();
            for (ExtResult.Callback c : w) c.reply(r);
        }

        private ExtResult notStarted() {
            return display == null ? ExtResult.error("not_active", "start the display first (display_start)")
                    : ExtResult.error("not_active", "the display is not started yet (display_start answers once it is)");
        }

        void send(String json, ExtResult.Callback reply) {
            if (!started()) {
                reply.reply(notStarted());
                return;
            }
            GlassesCore.DisplayTree.Node tree;
            try {
                tree = GlassesCore.DisplayTree.parse(json);
            } catch (GlassesCore.DisplayTree.Invalid e) {
                reply.reply(ExtResult.error("bad_request", e.getMessage()));
                return;
            }
            if (!tree.kind.equals("box")) {
                reply.reply(ExtResult.error("bad_request", "the root must be a box"));
                return;
            }
            Display d = display;
            DatInterop.<DatResult<Boolean, DisplayError>>call(c -> d.sendContent(scope -> {
                root(scope, tree);
                return Unit.INSTANCE;
            }, c), (r, error) -> reply.reply(displayResult(r, error, obj("sent", true))));
        }

        void video(String url, ExtResult.Callback reply) {
            if (!started()) {
                reply.reply(notStarted());
                return;
            }
            closePlayer();
            VideoPlayer p = new VideoPlayer(new VideoSource.Url(url), VideoCodec.MP4);
            player = p;
            playerScope = DatInterop.mainScope();
            DatInterop.collect(playerScope, p.getState(), (VideoPlayerState st) -> {
                if (st == null) return;
                String v = st == VideoPlayerState.PLAYING ? "started" : st == VideoPlayerState.ENDED ? "ended"
                        : st == VideoPlayerState.PAUSE ? "paused" : st == VideoPlayerState.STARTING ? "starting" : "idle";
                emit.emit("display", obj("video", v));
            });
            DatInterop.collect(playerScope, p.getError(), (VideoPlayerError e) -> {
                if (e == null) return;
                String code = e == VideoPlayerError.INVALID_URL ? "invalid_url" : e == VideoPlayerError.ALREADY_PLAYING ? "busy" : "playback_failed";
                emit.emit("display", obj("video", "error", "error", code, "message", describe(e)));
            });
            Display d = display;
            DatInterop.<DatResult<Boolean, DisplayError>>call(c -> d.sendContent(scope -> {
                scope.video(p, v -> Unit.INSTANCE);
                return Unit.INSTANCE;
            }, c), (r, error) -> {
                ExtResult out = displayResult(r, error, obj("playing", url));
                if (out.isOk() && player == p && p.getState().getValue() == VideoPlayerState.IDLE) p.play();
                reply.reply(out);
            });
        }

        ExtResult videoStop() {
            if (display == null) return ExtResult.error("not_active", "the display is off");
            closePlayer();
            return ExtResult.ok(obj("stopped", true));
        }

        private void closePlayer() {
            if (player != null) player.close();
            DatInterop.cancel(playerScope);
            player = null;
            playerScope = null;
        }

        void clear(ExtResult.Callback reply) {
            if (display == null) {
                reply.reply(ExtResult.error("not_active", "the display is off"));
                return;
            }
            if (!started()) {
                reply.reply(notStarted());
                return;
            }
            Display d = display;
            DatInterop.<DatResult<Boolean, DisplayError>>call(d::clearDisplay, (r, error) -> reply.reply(displayResult(r, error, obj("cleared", true))));
        }

        ExtResult stop(DeviceSession s) {
            if (display == null) return ExtResult.error("not_active", "the display is off");
            settle(ExtResult.error("display_failed", "the display was stopped before it started"));
            closePlayer();
            display.stop();
            if (s != null) SessionDisplayExtensionsKt.removeDisplay(s);
            DatInterop.cancel(scope);
            scope = null;
            display = null;
            return ExtResult.ok(obj("state", "stopped"));
        }

        void sessionEnded() {
            if (display == null) return;
            settle(ExtResult.error("session_failed", "the glasses session ended before the display started"));
            closePlayer();
            DatInterop.cancel(scope);
            scope = null;
            display = null;
            emit.emit("display", obj("state", "stopped", "reason", "session_stopped"));
        }

        private static ExtResult displayResult(DatResult<Boolean, DisplayError> r, Throwable error, JSONObject ok) {
            if (error != null || r == null) return ExtResult.error("display_failed", error == null ? "no answer" : String.valueOf(error.getMessage()));
            if (!r.isSuccess()) return ExtResult.error("display_failed", describe(r.errorOrNull()));
            return ExtResult.ok(ok);
        }

        // tree to DAT views

        private kotlin.jvm.functions.Function0<Unit> click(String id) {
            if (id == null) return null;
            return () -> {
                MAIN.post(() -> emit.emit("display", obj("click", id)));
                return Unit.INSTANCE;
            };
        }

        private void root(ContentScope scope, GlassesCore.DisplayTree.Node n) {
            Integer[] edges = edges(n);
            scope.flexBox(direction(n.get("direction")), (int) num(n, "spacing", 0), align(n.get("align"), Alignment.START),
                    align(n.get("cross"), Alignment.START), Boolean.TRUE.equals(n.bool("wrap")), (int) num(n, "padding", 0),
                    edges[0], edges[1], edges[2], edges[3], background(n), click(n.get("id")), b -> {
                        children(b, n);
                        return Unit.INSTANCE;
                    });
        }

        private void children(FlexBoxScope b, GlassesCore.DisplayTree.Node parent) {
            for (GlassesCore.DisplayTree.Node c : parent.children) component(b, c);
        }

        private void component(FlexBoxScope b, GlassesCore.DisplayTree.Node n) {
            float grow = (float) num(n, "grow", 0);
            float shrink = (float) num(n, "shrink", 0);
            Alignment self = align(n.get("self"), null);
            switch (n.kind) {
                case "box": {
                    Integer[] edges = edges(n);
                    b.flexBox(direction(n.get("direction")), (int) num(n, "spacing", 0), align(n.get("align"), Alignment.START),
                            align(n.get("cross"), Alignment.START), Boolean.TRUE.equals(n.bool("wrap")), (int) num(n, "padding", 0),
                            edges[0], edges[1], edges[2], edges[3], background(n), click(n.get("id")), grow, shrink, self, inner -> {
                                children(inner, n);
                                return Unit.INSTANCE;
                            });
                    break;
                }
                case "text":
                    b.text(n.get("text") == null ? "" : n.get("text"),
                            "heading".equals(n.get("style")) ? TextStyle.HEADING : "meta".equals(n.get("style")) ? TextStyle.META : TextStyle.BODY,
                            "secondary".equals(n.get("color")) ? TextColor.SECONDARY : TextColor.PRIMARY, grow, shrink, self);
                    break;
                case "button":
                    b.button(label(n), buttonStyle(n), icon(n.get("icon")), click(n.get("id")), grow, shrink, self,
                            Boolean.TRUE.equals(n.bool("primary")) ? ActionRole.PRIMARY : null);
                    break;
                case "buttons": {
                    ButtonGroupAlignment a = "start".equals(n.get("align")) ? ButtonGroupAlignment.START
                            : "end".equals(n.get("align")) ? ButtonGroupAlignment.END : ButtonGroupAlignment.CENTER;
                    b.buttonGroup(a, grow, shrink, self, (ButtonGroupScope g) -> {
                        for (GlassesCore.DisplayTree.Node c : n.children) {
                            g.button(label(c), buttonStyle(c), icon(c.get("icon")), click(c.get("id")),
                                    Boolean.TRUE.equals(c.bool("primary")) ? ActionRole.PRIMARY : null);
                        }
                        return Unit.INSTANCE;
                    });
                    break;
                }
                case "icon": {
                    IconName i = icon(n.get("name"));
                    b.icon(i == null ? IconName.SMART_GLASSES : i, "outline".equals(n.get("style")) ? IconStyle.OUTLINE : IconStyle.FILLED,
                            grow, shrink, self);
                    break;
                }
                default:
                    b.image(n.get("src"), null, "fill".equals(n.get("size")) ? ImageSize.FILL : ImageSize.ICON,
                            "small".equals(n.get("radius")) ? CornerRadius.SMALL : "medium".equals(n.get("radius")) ? CornerRadius.MEDIUM : CornerRadius.NONE,
                            grow, shrink, self);
            }
        }

        private static String label(GlassesCore.DisplayTree.Node n) {
            return n.get("label") == null ? "" : n.get("label");
        }

        private static ButtonStyle buttonStyle(GlassesCore.DisplayTree.Node n) {
            String s = n.get("style");
            return "secondary".equals(s) ? ButtonStyle.SECONDARY : "outline".equals(s) ? ButtonStyle.OUTLINE : ButtonStyle.PRIMARY;
        }

        /** DAT's icon names: snake case on iOS (the raw values), the same words upper case here. */
        static IconName icon(String name) {
            if (name == null) return null;
            try {
                return IconName.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private static double num(GlassesCore.DisplayTree.Node n, String k, double fallback) {
            Double d = n.num(k);
            return d == null ? fallback : d;
        }

        /** padding_top, padding_bottom, padding_start and padding_end: DAT's argument order. */
        private static Integer[] edges(GlassesCore.DisplayTree.Node n) {
            String[] keys = {"padding_top", "padding_bottom", "padding_start", "padding_end"};
            Integer[] out = new Integer[4];
            for (int i = 0; i < 4; i++) {
                Double d = n.num(keys[i]);
                out[i] = d == null ? null : (int) Math.round(d);
            }
            return out;
        }

        private static FlexBoxBackground background(GlassesCore.DisplayTree.Node n) {
            return "card".equals(n.get("background")) ? FlexBoxBackground.CARD : FlexBoxBackground.NONE;
        }

        private static Direction direction(String s) {
            if ("row".equals(s)) return Direction.ROW;
            if ("row_reverse".equals(s)) return Direction.ROW_REVERSE;
            if ("column_reverse".equals(s)) return Direction.COLUMN_REVERSE;
            return Direction.COLUMN;
        }

        private static Alignment align(String s, Alignment fallback) {
            if ("start".equals(s)) return Alignment.START;
            if ("center".equals(s)) return Alignment.CENTER;
            if ("end".equals(s)) return Alignment.END;
            if ("stretch".equals(s)) return Alignment.STRETCH;
            return fallback;
        }
    }
}
