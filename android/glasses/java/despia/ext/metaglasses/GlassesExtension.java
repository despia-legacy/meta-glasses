package despia.ext.metaglasses;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.despia.nativeext.ExtCall;
import com.despia.nativeext.ExtContext;
import com.despia.nativeext.ExtResult;
import com.despia.nativeext.NativeExtension;
import com.meta.wearable.dat.camera.Camera;
import com.meta.wearable.dat.camera.SessionCameraExtensionsKt;
import com.meta.wearable.dat.camera.Stream;
import com.meta.wearable.dat.camera.photo.Photo;
import com.meta.wearable.dat.camera.photo.types.PhotoCaptureData;
import com.meta.wearable.dat.camera.photo.types.PhotoError;
import com.meta.wearable.dat.camera.photo.types.PhotoQuality;
import com.meta.wearable.dat.camera.photo.types.PhotoResolution;
import com.meta.wearable.dat.camera.photo.types.PhotoState;
import com.meta.wearable.dat.camera.types.AudioCodec;
import com.meta.wearable.dat.camera.types.AudioFrame;
import com.meta.wearable.dat.camera.types.AudioSampleRate;
import com.meta.wearable.dat.camera.types.CaptureError;
import com.meta.wearable.dat.camera.types.PhotoData;
import com.meta.wearable.dat.camera.types.StreamConfiguration;
import com.meta.wearable.dat.camera.types.StreamError;
import com.meta.wearable.dat.camera.types.StreamState;
import com.meta.wearable.dat.camera.types.VideoFrame;
import com.meta.wearable.dat.camera.types.VideoQuality;
import com.meta.wearable.dat.core.Wearables;
import com.meta.wearable.dat.core.registration.RegistrationRequest;
import com.meta.wearable.dat.core.registration.RegistrationRequestError;
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector;
import com.meta.wearable.dat.core.selectors.DeviceSelector;
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector;
import com.meta.wearable.dat.core.session.DeviceSession;
import com.meta.wearable.dat.core.session.DeviceSessionState;
import com.meta.wearable.dat.core.types.DatError;
import com.meta.wearable.dat.core.types.DatResult;
import com.meta.wearable.dat.core.types.DeviceIdentifier;
import com.meta.wearable.dat.core.types.DeviceSessionError;
import com.meta.wearable.dat.core.types.NavigationError;
import com.meta.wearable.dat.core.types.Permission;
import com.meta.wearable.dat.core.types.PermissionError;
import com.meta.wearable.dat.core.types.PermissionStatus;
import com.meta.wearable.dat.core.types.RegistrationError;
import com.meta.wearable.dat.core.types.RegistrationState;
import com.meta.wearable.dat.core.types.ThermalLevel;
import com.meta.wearable.dat.core.types.ChargingState;
import com.meta.wearable.dat.core.types.WearablesError;
import com.meta.wearable.dat.inputs.types.ButtonType;
import com.meta.wearable.dat.inputs.types.CapturePressType;
import com.meta.wearable.dat.inputs.types.DragAction;
import com.meta.wearable.dat.inputs.types.InputSource;
import com.meta.wearable.dat.mockdevice.MockDeviceKit;
import com.meta.wearable.dat.mockdevice.api.GlassesModel;
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig;
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitError;
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface;
import com.meta.wearable.dat.mockdevice.api.MockGlasses;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlinx.coroutines.CoroutineScope;

/**
 * Meta's AI glasses through Meta's Wearables Device Access Toolkit (DAT) 1.1.0: registration with
 * the Meta AI app, devices and their state, camera and microphone permissions, one shared device
 * session, the camera stream with optional audio, in-stream and high-quality photos, clips,
 * speech, motion, inputs, voice invocations, the glasses display, Bluetooth audio routing, update
 * prompts and the Mock Device Kit. The capabilities beside the camera are in
 * {@link GlassesCapabilities}; every word and error code is the same as on iOS.
 *
 * <p>The page starts and stops the glasses' camera stream and shows it from a loopback URL
 * ({@link GlassesServer}): {@code <img src=live>} is a multipart JPEG stream that always carries
 * the newest frame. Photos and short clips are saved in the extension's directory and served from
 * the same server, with byte ranges for {@code <video>}. Nothing starts at launch: the SDK is
 * initialized by the first route call (or a registration callback intent), the stream by
 * {@code start}.
 *
 * <p>The SDK's app id and client token are the customer's: Gradle manifest placeholders the
 * customer's project fills (TEMPLATE.md); this code reads none of them.
 */
public final class GlassesExtension implements NativeExtension {
    static final String SDK_VERSION = "1.1.0";
    /** DAT 1.1.0 Android AARs declare minSdkVersion 29. */
    // Below API 29 (DAT's minSdk) the runtime never constructs this class: android.min_sdk in the manifest.
    /** BLUETOOTH_CONNECT is a runtime permission from Android 12 (API 31). */
    static final int BLUETOOTH_RUNTIME_API = 31;
    static final String BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    /** media_read returns the file in chunks of this many bytes (65536 characters of base64). */
    static final int MEDIA_CHUNK = 49152;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();
    private final GlassesMedia.FrameEncoder encoder = new GlassesMedia.FrameEncoder();
    private final GlassesCore.Session core = new GlassesCore.Session();

    private ExtContext ctx;
    private File mediaDir;
    private boolean initialized;
    private CoroutineScope appScope;

    private ComponentActivity activity;
    private ActivityResultLauncher<Permission> permissionLauncher;
    private ActivityResultLauncher<String> bluetoothLauncher;
    private Call permissionWait;

    private GlassesServer server;
    private DeviceSession session;
    private Camera camera;
    private Stream stream;
    private CoroutineScope streamScope;
    private CoroutineScope frameScope;
    /** The generation whose frames are published; 0 when nothing streams. Read on the frame thread. */
    private volatile long liveGen;
    private boolean streamSeenActive;
    private Call starting;
    private final Object startTimeout = new Object();
    private String quality = "medium";
    private int fps = 24;
    private volatile int frameWidth;
    private volatile int frameHeight;
    private volatile long frames;

    private boolean photoBusy;
    private volatile GlassesMedia.ClipRecorder recorder;
    private long recorderGen;
    private int recorderSeconds;
    private final Object clipTimer = new Object();

    private boolean mock;
    private MockGlasses mockGlasses;

    /** The one DeviceSession every capability shares (camera, speech, motion, inputs, display). */
    private CoroutineScope sessionScope;
    private DeviceSelector sessionSelector;
    private final List<Waiter> sessionWaiters = new ArrayList<>();
    private ExtResult sessionFailure;
    private String lastSessionState = "idle";
    private RegistrationRequest registrationRequest;
    private GlassesCapabilities.Devices devices;
    private GlassesCapabilities.Sensors sensors;
    private GlassesCapabilities.Screen screen;
    private boolean audio;
    private int audioRate = 16000;
    private int audioChannels = 1;
    /** The standalone still photo's own Camera and Photo (never the stream's: Meta, "Camera Capture"). */
    private Camera stillCamera;
    private Photo cameraPhoto;
    private CoroutineScope photoScope;
    private boolean stillCaptured;
    private ExtResult.Callback stillReply;
    private Object stillTimeout;
    private String stillResolution;
    private String stillQuality;
    private AudioManager audioManager;
    private AudioDeviceCallback audioCallback;
    private Integer savedAudioMode;

    /** Waits for the shared session to reach STARTED. */
    private interface Ready {
        void ready(DeviceSession s);
    }

    private static final class Waiter {
        final ExtCall call;
        final ExtResult.Callback reply;
        final Ready ready;

        Waiter(ExtCall call, ExtResult.Callback reply, Ready ready) {
            this.call = call;
            this.reply = reply;
            this.ready = ready;
        }
    }

    private static final class Call {
        final ExtCall call;
        final ExtResult.Callback reply;
        final long gen;
        boolean prompt;
        Permission asked = Permission.CAMERA;

        Call(ExtCall call, ExtResult.Callback reply, long gen) {
            this.call = call;
            this.reply = reply;
            this.gen = gen;
        }
    }

    @Override
    public void onCreate(ExtContext context) {
        ctx = context;
        mediaDir = new File(context.dataDir(), "media");
        GlassesCapabilities.Emit emit = (event, data) -> ctx.emit(event, data);
        devices = new GlassesCapabilities.Devices(emit);
        sensors = new GlassesCapabilities.Sensors(emit);
        screen = new GlassesCapabilities.Screen(emit);
    }

    @Override
    public void onActivityEvent(Activity a, ActivityEvent event) {
        if (!(a instanceof ComponentActivity)) return;
        ComponentActivity ca = (ComponentActivity) a;
        if (event == ActivityEvent.CREATED) {
            if (permissionLauncher != null) permissionLauncher.unregister();
            if (bluetoothLauncher != null) bluetoothLauncher.unregister();
            activity = ca;
            permissionLauncher = ca.getActivityResultRegistry().register(ctx.resultKey("camera"),
                    new Wearables.RequestPermissionContract(), this::onPermissionResult);
            bluetoothLauncher = ca.getActivityResultRegistry().register(ctx.resultKey("bluetooth"),
                    new ActivityResultContracts.RequestPermission(), this::onBluetoothResult);
        } else if (event == ActivityEvent.DESTROYED && activity == ca) {
            if (permissionLauncher != null) permissionLauncher.unregister();
            if (bluetoothLauncher != null) bluetoothLauncher.unregister();
            permissionLauncher = null;
            bluetoothLauncher = null;
            activity = null;
            if (permissionWait != null) {
                Call w = permissionWait;
                permissionWait = null;
                w.reply.reply(ExtResult.error("unavailable", "the activity was destroyed"));
            }
        }
    }

    /**
     * The Meta AI app returns to the app on its own scheme after registration. Wearables tracks
     * the result itself (registration flows through its service); handleIntent passes a
     * registration request on, when the intent carries one: it reaches the page as the
     * registration_request event, answered with registration_continue or registration_cancel.
     */
    @Override
    public boolean onIntent(Activity a, Intent intent) {
        if (!bluetoothGranted() || !ensureInit()) return false;
        DatResult<Boolean, ?> r = Wearables.INSTANCE.handleIntent(intent, request -> {
            registrationRequest = request;
            ctx.emit("registration_request", GlassesCapabilities.obj("flowId", request.getFlowId(),
                    "protocolVersion", request.getProtocolVersion()));
            return kotlin.Unit.INSTANCE;
        });
        Boolean handled = r.getOrNull();
        return handled != null && handled;
    }

    @Override
    public void handle(ExtCall call, ExtResult.Callback reply) {
        try {
            if (("mock".equals(call.route) || "mock_event".equals(call.route))
                    && (ctx.app().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                reply.reply(ExtResult.error("mock_unavailable", "the mock glasses run in debug builds only"));
                return;
            }
            switch (call.route) {
                case "permission":
                    // Bluetooth first, then the SDK, then the glasses camera: Meta's order.
                    permission(call, reply);
                    return;
                case "status":
                    if (bluetoothGranted()) ensureInit();
                    reply.reply(ExtResult.ok(status()));
                    return;
                case "frame":
                case "media":
                case "media_read":
                case "media_delete":
                case "stop":
                case "session_stop":
                case "speech_stop":
                case "motion_stop":
                case "inputs_stop":
                case "display_stop":
                case "display_video_stop":
                case "voice_stop":
                case "audio_route":
                    break;
                default:
                    if (!bluetoothGranted()) {
                        reply.reply(ExtResult.error("permission_required",
                                "Bluetooth access is not granted (call permission with prompt true)"));
                        return;
                    }
                    if (!ensureInit()) {
                        reply.reply(ExtResult.error("init_failed", "the glasses SDK could not start"));
                        return;
                    }
            }
            switch (call.route) {
                case "register":
                    register(reply, true);
                    return;
                case "unregister":
                    register(reply, false);
                    return;
                case "start":
                    start(call, reply);
                    return;
                case "stop":
                    teardown("stopped");
                    reply.reply(ExtResult.ok(status()));
                    return;
                case "photo":
                    if (call.params.get("resolution") != null || call.params.get("quality") != null) {
                        stillPhoto(call, reply);
                    } else {
                        photo(reply);
                    }
                    return;
                case "record_start":
                    recordStart(call, reply);
                    return;
                case "record_stop":
                    recordStop(reply);
                    return;
                case "frame":
                    frame(reply);
                    return;
                case "media":
                    reply.reply(ExtResult.ok(new JSONObject().put("items", mediaList())));
                    return;
                case "media_read":
                    mediaRead(call, reply);
                    return;
                case "media_delete":
                    mediaDelete(call, reply);
                    return;
                case "mock":
                    mock(call, reply);
                    return;
                case "mock_event":
                    mockEvent(call, reply);
                    return;
                case "devices":
                    reply.reply(ExtResult.ok(new JSONObject().put("devices", devices.list())));
                    return;
                case "session_start":
                    openSession(call, reply, str(call, "device"), s -> replyStatus(reply));
                    return;
                case "session_stop":
                    endSession("stopped");
                    reply.reply(ExtResult.ok(status()));
                    return;
                case "pause":
                case "resume":
                    pauseResume(call.route.equals("pause"), reply);
                    return;
                case "registration_continue":
                case "registration_cancel":
                    registrationAnswer(call, reply);
                    return;
                case "open_update":
                    openUpdate(call, reply);
                    return;
                case "audio_route":
                    audioRoute(call, reply);
                    return;
                case "speech_start":
                case "motion_start":
                case "inputs_start":
                case "display_start":
                    capabilityStart(call, reply);
                    return;
                case "speech_stop":
                    reply.reply(sensors.speechStop(session));
                    releaseSessionIfIdle();
                    return;
                case "motion_stop":
                    reply.reply(sensors.motionStop(session));
                    releaseSessionIfIdle();
                    return;
                case "inputs_stop":
                    reply.reply(sensors.inputsStop(session));
                    releaseSessionIfIdle();
                    return;
                case "display_stop":
                    reply.reply(screen.stop(session));
                    releaseSessionIfIdle();
                    return;
                case "display_send":
                    screen.send(str(call, "content"), reply);
                    return;
                case "display_video":
                    String url = str(call, "url");
                    if (url == null || !url.startsWith("https://")) {
                        reply.reply(ExtResult.error("bad_request", "url must be an https URL"));
                        return;
                    }
                    screen.video(url, reply);
                    return;
                case "display_video_stop":
                    reply.reply(screen.videoStop());
                    return;
                case "display_clear":
                    screen.clear(reply);
                    return;
                case "voice_start":
                    voiceStart(call, reply);
                    return;
                case "voice_stop":
                    reply.reply(sensors.voiceStop());
                    return;
                case "voice_respond":
                    sensors.voiceRespond(str(call, "id") == null ? "" : str(call, "id"), !Boolean.FALSE.equals(call.params.get("success")),
                            str(call, "output"), reply);
                    return;
                default:
                    reply.reply(ExtResult.error("bad_request", "unknown route"));
            }
        } catch (JSONException e) {
            reply.reply(ExtResult.error("handler_error", "could not build the reply"));
        }
    }

    // ------------------------------------------------------------------------- SDK and state

    /** Wearables.initialize on first use; never at launch. */
    private boolean ensureInit() {
        if (initialized) return true;
        DatResult<kotlin.Unit, WearablesError> r = Wearables.INSTANCE.initialize(ctx.app());
        if (!r.isSuccess() && r.errorOrNull() != WearablesError.ALREADY_INITIALIZED) {
            ctx.log("Wearables.initialize failed: " + describe(r.errorOrNull()));
            return false;
        }
        initialized = true;
        appScope = DatInterop.mainScope();
        devices.start(appScope);
        DatInterop.collect(appScope, Wearables.INSTANCE.getRegistrationState(), s -> {
            try {
                ctx.emit("registration", new JSONObject().put("registration", name(s)));
            } catch (JSONException ignored) {
                // A two-field object always builds.
            }
        });
        // Android reports a failed registration on this stream, not as the call's result; DAT 1.1.0
        // adds UNAUTHORIZED, emitted without opening Meta AI. It reaches the page as the
        // registration event with the same error codes iOS returns from register.
        DatInterop.collect(appScope, Wearables.INSTANCE.getRegistrationErrorStream(), e -> {
            // Already in the asked state: not an error, as on iOS.
            if (e == RegistrationError.ALREADY_REGISTERED || e == RegistrationError.ALREADY_UNREGISTERED) return;
            try {
                ctx.emit("registration", new JSONObject()
                        .put("registration", name(Wearables.INSTANCE.getRegistrationState().getValue()))
                        .put("error", registrationError(e)).put("message", describe(e)));
            } catch (JSONException ignored) {
                // A four-field object always builds.
            }
        });
        return true;
    }

    private JSONObject status() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("sdkVersion", SDK_VERSION);
        o.put("bluetooth", Build.VERSION.SDK_INT < BLUETOOTH_RUNTIME_API ? "not_required" : bluetoothGranted() ? "granted" : "denied");
        o.put("registration", initialized ? name(Wearables.INSTANCE.getRegistrationState().getValue()) : "unknown");
        o.put("devices", initialized ? Wearables.INSTANCE.getDevices().getValue().size() : 0);
        o.put("state", core.state);
        o.put("quality", quality);
        o.put("fps", fps);
        int[] asked = GlassesCore.frameSize(quality);
        o.put("width", frameWidth > 0 ? frameWidth : asked[0]);
        o.put("height", frameHeight > 0 ? frameHeight : asked[1]);
        o.put("frames", frames);
        o.put("staleDrops", core.staleDrops);
        o.put("clip", core.clip == null ? JSONObject.NULL : core.clip);
        o.put("photoBusy", photoBusy);
        o.put("mock", mock);
        o.put("session", session == null ? "none" : GlassesCapabilities.word(session.getState().getValue()));
        o.put("device", sessionDeviceId() == null ? JSONObject.NULL : sessionDeviceId());
        o.put("audio", audio);
        o.put("audioRate", audioRate);
        o.put("audioChannels", audioChannels);
        o.put("speech", sensors.speech != null);
        o.put("motion", sensors.motion != null);
        o.put("inputs", sensors.inputs != null);
        o.put("display", screen.active());
        o.put("voice", sensors.voiceState());
        if (server != null) {
            o.put("live", server.base() + "/live.mjpg");
            o.put("frame", server.base() + "/frame.jpg");
            o.put("mediaBase", server.base() + "/media/");
        } else {
            o.put("live", JSONObject.NULL);
            o.put("frame", JSONObject.NULL);
            o.put("mediaBase", JSONObject.NULL);
        }
        return o;
    }

    private void emitState(String reason) {
        try {
            JSONObject o = status();
            o.put("reason", reason == null ? JSONObject.NULL : reason);
            ctx.emit("state", o);
        } catch (JSONException ignored) {
            // status() builds from plain values.
        }
    }

    private void register(ExtResult.Callback reply, boolean on) throws JSONException {
        if (activity == null) {
            reply.reply(ExtResult.error("unavailable", "no activity is showing"));
            return;
        }
        RegistrationState s = Wearables.INSTANCE.getRegistrationState().getValue();
        if (on && s != RegistrationState.REGISTERED) Wearables.INSTANCE.startRegistration(activity);
        if (!on && s == RegistrationState.REGISTERED) Wearables.INSTANCE.startUnregistration(activity);
        // The outcome arrives as the registration event once the Meta AI app returns.
        reply.reply(ExtResult.ok(new JSONObject().put("registration", name(Wearables.INSTANCE.getRegistrationState().getValue()))));
    }

    private void permission(ExtCall call, ExtResult.Callback reply) {
        if (permissionWait != null) {
            reply.reply(ExtResult.error("busy", "a permission request is already open"));
            return;
        }
        Call w = new Call(call, reply, 0);
        w.prompt = Boolean.TRUE.equals(call.params.get("prompt"));
        w.asked = "microphone".equals(call.params.get("kind")) ? Permission.MICROPHONE : Permission.CAMERA;
        permissionWait = w;
        if (!bluetoothGranted()) {
            if (!w.prompt) {
                finishPermission(w, okPermission("denied", "unknown", "unknown"));
            } else if (bluetoothLauncher == null) {
                finishPermission(w, ExtResult.error("unavailable", "no activity is showing"));
            } else {
                // The system dialog; the answer comes back to onBluetoothResult.
                bluetoothLauncher.launch(BLUETOOTH_CONNECT);
            }
            return;
        }
        checkCamera(w);
    }

    private void onBluetoothResult(Boolean granted) {
        Call w = permissionWait;
        if (w == null) return;
        if (!Boolean.TRUE.equals(granted) || !bluetoothGranted()) {
            finishPermission(w, ExtResult.error("permission_denied", "Bluetooth access was denied"));
        } else if (!w.call.isActive()) {
            finishPermission(w, ExtResult.error("timeout", "the permission request took too long"));
        } else {
            checkCamera(w);
        }
    }

    /** True below Android 12, where Bluetooth needs no runtime grant. */
    private boolean bluetoothGranted() {
        return Build.VERSION.SDK_INT < BLUETOOTH_RUNTIME_API
                || ctx.app().checkSelfPermission(BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    /** The glasses' camera and microphone permissions (granted in the Meta AI app); kind picks the one a prompt asks for. */
    private void checkCamera(Call w) {
        if (!ensureInit()) {
            finishPermission(w, ExtResult.error("init_failed", "the glasses SDK could not start"));
            return;
        }
        if (Wearables.INSTANCE.getRegistrationState().getValue() != RegistrationState.REGISTERED) {
            // The glasses permissions belong to a registered app; Meta AI cannot answer them before.
            finishPermission(w, okPermission("granted", "unknown", "unknown"));
            return;
        }
        DatInterop.<DatResult<PermissionStatus, PermissionError>>call(
                c -> Wearables.INSTANCE.checkPermissionStatus(w.asked, c), (r, error) -> {
                    if (permissionWait != w) return;
                    if (error != null || r == null) {
                        finishPermission(w, ExtResult.error("permission_failed", "the permission check failed"));
                    } else if (!r.isSuccess()) {
                        finishPermission(w, permissionError(r.errorOrNull()));
                    } else if (r.getOrNull() instanceof PermissionStatus.Granted) {
                        finishWithOther(w, "granted");
                    } else if (!w.prompt) {
                        finishWithOther(w, "denied");
                    } else if (!w.call.isActive()) {
                        finishPermission(w, ExtResult.error("timeout", "the permission check took too long"));
                    } else if (permissionLauncher == null) {
                        finishPermission(w, ExtResult.error("unavailable", "no activity is showing"));
                    } else {
                        // Switches to the Meta AI app; the answer comes back to onPermissionResult.
                        permissionLauncher.launch(w.asked);
                    }
                });
    }

    private void onPermissionResult(DatResult<PermissionStatus, PermissionError> r) {
        Call w = permissionWait;
        if (w == null) return;
        if (r == null || !r.isSuccess()) {
            finishPermission(w, r == null ? ExtResult.error("permission_failed", "no answer") : permissionError(r.errorOrNull()));
        } else {
            finishWithOther(w, r.getOrNull() instanceof PermissionStatus.Granted ? "granted" : "denied");
        }
    }

    /** Reads the permission that was not asked, so both are reported. */
    private void finishWithOther(Call w, String askedWord) {
        Permission other = w.asked == Permission.CAMERA ? Permission.MICROPHONE : Permission.CAMERA;
        DatInterop.<DatResult<PermissionStatus, PermissionError>>call(
                c -> Wearables.INSTANCE.checkPermissionStatus(other, c), (r, error) -> {
                    String o = error != null || r == null || !r.isSuccess() ? "unknown"
                            : r.getOrNull() instanceof PermissionStatus.Granted ? "granted" : "denied";
                    boolean cam = w.asked == Permission.CAMERA;
                    finishPermission(w, okPermission("granted", cam ? askedWord : o, cam ? o : askedWord));
                });
    }

    private void finishPermission(Call w, ExtResult result) {
        if (permissionWait == w) permissionWait = null;
        w.reply.reply(result);
    }

    private static ExtResult okPermission(String bluetooth, String camera, String microphone) {
        try {
            return ExtResult.ok(new JSONObject().put("bluetooth", bluetooth).put("camera", camera).put("microphone", microphone));
        } catch (JSONException e) {
            return ExtResult.error("handler_error", "could not build the reply");
        }
    }

    private static ExtResult permissionError(PermissionError e) {
        if (e == PermissionError.META_AI_NOT_INSTALLED) return ExtResult.error("meta_ai_missing", "the Meta AI app is not installed");
        if (e == PermissionError.NO_DEVICE || e == PermissionError.NO_DEVICE_WITH_CONNECTION) {
            return ExtResult.error("no_device", describe(e));
        }
        if (e == PermissionError.REQUEST_IN_PROGRESS) return ExtResult.error("busy", describe(e));
        if (e == PermissionError.REQUEST_TIMEOUT) return ExtResult.error("timeout", describe(e));
        return ExtResult.error("permission_failed", describe(e));
    }

    // -------------------------------------------------------------------------------- stream

    private void start(ExtCall call, ExtResult.Callback reply) throws JSONException {
        if (Wearables.INSTANCE.getRegistrationState().getValue() != RegistrationState.REGISTERED) {
            reply.reply(ExtResult.error("registration_required", "connect the app to Meta AI first (register)"));
            return;
        }
        if (!core.state.equals("idle")) {
            reply.reply(ExtResult.error("busy", "the stream is already " + core.state));
            return;
        }
        if (stillReply != null) {
            reply.reply(ExtResult.error("busy", "a still photo is being taken"));
            return;
        }
        Object q = call.params.get("quality");
        Object f = call.params.get("fps");
        String wantQuality = q instanceof String ? (String) q : "medium";
        int wantFps = GlassesCore.frameRate(f instanceof Number ? ((Number) f).intValue() : 24);
        Object ch = call.params.get("audio_channels");
        try {
            if (server == null) server = GlassesServer.start(mediaDir());
        } catch (IOException e) {
            reply.reply(ExtResult.error("server_failed", "the local stream server could not start"));
            return;
        }
        long g = core.begin();
        quality = wantQuality;
        fps = wantFps;
        audio = Boolean.TRUE.equals(call.params.get("audio"));
        audioRate = "48000".equals(call.params.get("audio_rate")) ? 48000 : "44100".equals(call.params.get("audio_rate")) ? 44100 : 16000;
        audioChannels = ch instanceof Number ? Math.max(1, Math.min(2, ((Number) ch).intValue())) : 1;
        frameWidth = 0;
        frameHeight = 0;
        streamSeenActive = false;
        Call s = new Call(call, reply, g);
        starting = s;
        streamScope = DatInterop.mainScope();
        frameScope = DatInterop.backgroundScope();
        long at = call.deadlineUptimeMillis > 0 ? call.deadlineUptimeMillis - 100 : SystemClock.uptimeMillis() + 20000;
        main.postAtTime(() -> {
            if (starting == s) failStart(s, ExtResult.error("timeout", "the glasses did not start streaming in time"));
        }, startTimeout, at);
        emitState("starting");
        openSession(call, r -> failStart(s, r), str(call, "device"), sess -> attachCamera(g, sess));
    }

    /** The session is started: the camera is added (a capability needs a started session). */
    private void attachCamera(long g, DeviceSession s) {
        if (g != core.gen || core.state.equals("idle") || camera != null) {
            releaseSessionIfIdle();
            return;
        }
        VideoQuality vq = quality.equals("high") ? VideoQuality.HIGH : quality.equals("low") ? VideoQuality.LOW : VideoQuality.MEDIUM;
        // Camera audio (DAT 1.0.0, experimental): PCM at 16, 44.1 or 48 kHz beside the video, into clips.
        AudioCodec codec = audio ? new AudioCodec.PCM(audioRate == 48000 ? AudioSampleRate.RATE_48000
                : audioRate == 44100 ? AudioSampleRate.RATE_44100 : AudioSampleRate.RATE_16000, audioChannels) : null;
        // Decoded YUV frames (compressVideo false): the preview needs no decoder of its own.
        DatResult<Camera, DeviceSessionError> c = SessionCameraExtensionsKt.addCamera(s, new StreamConfiguration(codec, vq, fps, false));
        if (!c.isSuccess() || c.getOrNull() == null) {
            failStartOrEnd(g, GlassesCapabilities.session(c.errorOrNull()), "camera_failed");
            return;
        }
        camera = c.getOrNull();
        stream = camera.getStream();
        liveGen = g;
        DatInterop.collect(streamScope, stream.getState(), x -> onStreamState(g, x));
        DatInterop.collect(streamScope, stream.getErrorStream(), x -> onStreamError(g, x));
        DatInterop.collect(frameScope, stream.getVideoStream(), x -> onFrame(g, x));
        if (audio) DatInterop.collect(frameScope, stream.getAudioStream(), x -> onAudio(g, x));
        DatResult<kotlin.Unit, StreamError> sr = stream.start();
        if (!sr.isSuccess()) failStartOrEnd(g, streamError(sr.errorOrNull()), "stream_failed");
    }

    // ------------------------------------------------------------------------ device session

    private String sessionDeviceId() {
        DeviceSelector sel = sessionSelector;
        DeviceIdentifier id = sel == null ? null : sel.activeDevice();
        return id == null ? null : id.getIdentifier();
    }

    /**
     * The one DeviceSession every capability shares. ready runs once it is started; fail gets the
     * error otherwise. device picks a device by id (SpecificDeviceSelector); without it DAT's
     * AutoDeviceSelector picks one.
     */
    private void openSession(ExtCall call, ExtResult.Callback fail, String device, Ready ready) {
        if (Wearables.INSTANCE.getRegistrationState().getValue() != RegistrationState.REGISTERED) {
            fail.reply(ExtResult.error("registration_required", "connect the app to Meta AI first (register)"));
            return;
        }
        if (session != null) {
            String cur = sessionDeviceId();
            if (device != null && cur != null && !device.equals(cur)) {
                fail.reply(ExtResult.error("busy", "a session with another device is open (session_stop first)"));
                return;
            }
            DeviceSessionState st = session.getState().getValue();
            if (st == DeviceSessionState.STARTED || st == DeviceSessionState.PAUSED) {
                ready.ready(session);
            } else {
                addWaiter(call, fail, ready);
            }
            return;
        }
        DeviceSelector sel = device == null ? new AutoDeviceSelector() : new SpecificDeviceSelector(new DeviceIdentifier(device));
        DatResult<DeviceSession, DeviceSessionError> r = Wearables.INSTANCE.createSession(sel);
        if (!r.isSuccess() || r.getOrNull() == null) {
            fail.reply(GlassesCapabilities.session(r.errorOrNull()));
            return;
        }
        DeviceSession s = r.getOrNull();
        session = s;
        sessionSelector = sel;
        sessionFailure = null;
        sessionScope = DatInterop.mainScope();
        addWaiter(call, fail, ready);
        DatInterop.collect(sessionScope, s.getErrors(), e -> onSessionError(s, e));
        DatInterop.collect(sessionScope, s.getState(), st -> onSessionState(s, st));
        s.start();
    }

    private void addWaiter(ExtCall call, ExtResult.Callback fail, Ready ready) {
        Waiter w = new Waiter(call, fail, ready);
        sessionWaiters.add(w);
        long at = call.deadlineUptimeMillis > 0 ? call.deadlineUptimeMillis - 100 : SystemClock.uptimeMillis() + 20000;
        main.postAtTime(() -> {
            if (!sessionWaiters.remove(w)) return;
            w.reply.reply(ExtResult.error("timeout", "the glasses did not connect in time"));
            releaseSessionIfIdle();
        }, w, at);
    }

    private void failWaiters(ExtResult why) {
        List<Waiter> ws = new ArrayList<>(sessionWaiters);
        sessionWaiters.clear();
        for (Waiter w : ws) {
            main.removeCallbacksAndMessages(w);
            w.reply.reply(why);
        }
    }

    private void onSessionState(DeviceSession s, DeviceSessionState st) {
        if (s != session) return;
        lastSessionState = GlassesCapabilities.word(st);
        ctx.emit("session", GlassesCapabilities.obj("state", lastSessionState, "device", sessionDeviceId()));
        if (st == DeviceSessionState.STARTED) {
            List<Waiter> ws = new ArrayList<>(sessionWaiters);
            sessionWaiters.clear();
            for (Waiter w : ws) {
                main.removeCallbacksAndMessages(w);
                if (session == s) w.ready.ready(s);
            }
        } else if (st == DeviceSessionState.STOPPED) {
            // The SDK ended the session: cleared first, so the camera's end releases nothing again.
            session = null;
            ExtResult why = sessionFailure != null ? sessionFailure
                    : ExtResult.error("no_device", "the glasses session stopped before it started");
            failWaiters(why);
            long g = core.gen;
            if (!core.state.equals("idle")) {
                if (streamSeenActive) {
                    ended(g, "session_stopped");
                } else {
                    failStartOrEnd(g, sessionFailure != null ? sessionFailure
                            : ExtResult.error("no_device", "the glasses session stopped before streaming"), "start_failed");
                }
            }
            if (stillReply != null) finishStill(why);
            sensors.sessionEnded();
            screen.sessionEnded();
            sessionSelector = null;
            CoroutineScope sc = sessionScope;
            sessionScope = null;
            DatInterop.cancel(sc);
        }
    }

    private void onSessionError(DeviceSession s, DeviceSessionError e) {
        if (s != session) return;
        ctx.emit("session", GlassesCapabilities.obj("state", GlassesCapabilities.word(s.getState().getValue()),
                "device", sessionDeviceId(), "error", GlassesCapabilities.sessionCode(e), "message", describe(e)));
        // DAT 1.0.0: an app built with an SDK outside the glasses' supported range keeps working;
        // the warning only suggests an update.
        if (e == DeviceSessionError.DWA_OUT_OF_STU_RANGE) return;
        sessionFailure = GlassesCapabilities.session(e);
        if (!core.state.equals("idle")) failStartOrEnd(core.gen, sessionFailure, "session_error");
    }

    /** Ends the session and every capability on it. */
    private void endSession(String reason) {
        DeviceSession s = session;
        String device = sessionDeviceId();
        // Cleared first: the camera's teardown below then finds no session to release again.
        session = null;
        CoroutineScope sc = sessionScope;
        sessionScope = null;
        DatInterop.cancel(sc);
        teardown(reason);
        if (stillReply != null) finishStill(ExtResult.error("no_device", "the glasses session ended before the photo arrived"));
        sensors.sessionEnded();
        screen.sessionEnded();
        failWaiters(ExtResult.error("no_device", "the glasses session was stopped"));
        sessionSelector = null;
        if (s == null) return;
        s.stop();
        lastSessionState = "stopped";
        ctx.emit("session", GlassesCapabilities.obj("state", "stopped", "device", device, "reason", reason));
    }

    /** The camera, speech, motion, inputs and display are all off: the session has no work left. */
    private void releaseSessionIfIdle() {
        if (session == null || camera != null || stillCamera != null || stillReply != null || !core.state.equals("idle") || !sessionWaiters.isEmpty()
                || sensors.active() || screen.active()) {
            return;
        }
        endSession("idle");
    }

    private void pauseResume(boolean pause, ExtResult.Callback reply) throws JSONException {
        if (session == null) {
            reply.reply(ExtResult.error("not_active", "no glasses session is open"));
            return;
        }
        // DAT 1.1.0: pause() outside started and resume() outside paused do nothing.
        if (pause) session.pause(); else session.resume();
        reply.reply(ExtResult.ok(new JSONObject().put("session", GlassesCapabilities.word(session.getState().getValue()))
                .put("device", sessionDeviceId() == null ? JSONObject.NULL : sessionDeviceId())));
    }

    private void capabilityStart(ExtCall call, ExtResult.Callback reply) {
        openSession(call, reply, str(call, "device"), s -> {
            ExtResult r;
            switch (call.route) {
                case "speech_start":
                    r = sensors.speechStart(s);
                    break;
                case "motion_start":
                    int hz;
                    try {
                        hz = Integer.parseInt(str(call, "rate") == null ? "10" : str(call, "rate"));
                    } catch (NumberFormatException e) {
                        hz = 10;
                    }
                    r = sensors.motionStart(s, hz);
                    break;
                case "inputs_start":
                    r = sensors.inputsStart(s, str(call, "sources"), !Boolean.FALSE.equals(call.params.get("consume_back")));
                    break;
                default:
                    r = screen.start(s);
            }
            if (!r.isOk()) releaseSessionIfIdle();
            reply.reply(r);
        });
    }

    private void voiceStart(ExtCall call, ExtResult.Callback reply) {
        if (Wearables.INSTANCE.getRegistrationState().getValue() != RegistrationState.REGISTERED) {
            reply.reply(ExtResult.error("registration_required", "connect the app to Meta AI first (register)"));
            return;
        }
        String device = str(call, "device");
        if (device == null) device = sessionDeviceId();
        if (device == null) {
            for (DeviceIdentifier id : Wearables.INSTANCE.getDevices().getValue()) {
                device = id.getIdentifier();
                break;
            }
        }
        if (device == null) {
            reply.reply(ExtResult.error("no_device", "no glasses are connected"));
            return;
        }
        reply.reply(sensors.voiceStart(device));
    }

    // ----------------------------------------------------- registration requests and updates

    private void registrationAnswer(ExtCall call, ExtResult.Callback reply) throws JSONException {
        RegistrationRequest r = registrationRequest;
        if (r == null) {
            reply.reply(ExtResult.error("not_found", "no registration request from Meta AI is open"));
            return;
        }
        boolean go = call.route.equals("registration_continue");
        if (go && activity == null) {
            reply.reply(ExtResult.error("unavailable", "no activity is showing"));
            return;
        }
        registrationRequest = null;
        // Android's cancelRegistration has no returnToMetaAI choice (DAT 1.1.0): Meta AI decides.
        DatResult<kotlin.Unit, RegistrationRequestError> out = go ? r.continueRegistration(activity) : r.cancelRegistration();
        if (!out.isSuccess()) {
            RegistrationRequestError e = out.errorOrNull();
            String code = e == RegistrationRequestError.INVALID_REQUEST || e == RegistrationRequestError.ALREADY_HANDLED
                    ? "not_found" : "registration_failed";
            reply.reply(ExtResult.error(code, describe(e)));
            return;
        }
        reply.reply(ExtResult.ok(new JSONObject().put("flowId", r.getFlowId()).put("answer", go ? "continue" : "cancel")));
    }

    private void openUpdate(ExtCall call, ExtResult.Callback reply) throws JSONException {
        if (activity == null) {
            reply.reply(ExtResult.error("unavailable", "no activity is showing"));
            return;
        }
        String target = "glasses_app".equals(call.params.get("target")) ? "glasses_app" : "firmware";
        DatResult<kotlin.Unit, NavigationError> r = target.equals("glasses_app")
                ? Wearables.INSTANCE.openDATGlassesAppUpdate(activity) : Wearables.INSTANCE.openFirmwareUpdate(activity);
        if (!r.isSuccess()) {
            NavigationError e = r.errorOrNull();
            reply.reply(ExtResult.error(e == NavigationError.META_AI_NOT_INSTALLED ? "meta_ai_missing"
                    : e == NavigationError.NOT_REGISTERED ? "registration_required" : "unavailable", describe(e)));
            return;
        }
        reply.reply(ExtResult.ok(new JSONObject().put("opened", target)));
    }

    // -------------------------------------------------------------------------- Bluetooth audio

    /**
     * Routes the app's audio to the glasses over Bluetooth: call uses the communication device
     * (Hands-Free: the glasses' microphones and speakers, for getUserMedia and calls), media
     * leaves media on its default route (A2DP to the glasses when they are connected), default
     * puts back the mode the app had. DAT itself carries no phone audio; Meta's glasses are
     * standard Bluetooth audio devices.
     */
    @SuppressWarnings("deprecation")
    private void audioRoute(ExtCall call, ExtResult.Callback reply) throws JSONException {
        String mode = str(call, "mode") == null ? "call" : str(call, "mode");
        AudioManager am = audioManager();
        if (am == null) {
            reply.reply(ExtResult.error("audio_failed", "no audio service"));
            return;
        }
        // The Despia base app declares MODIFY_AUDIO_SETTINGS (a normal permission, granted at
        // install); a typed extension module may not add it, so check it rather than crash.
        if (!"default".equals(mode) && ctx.app().checkSelfPermission("android.permission.MODIFY_AUDIO_SETTINGS")
                != PackageManager.PERMISSION_GRANTED) {
            reply.reply(ExtResult.error("permission_denied", "the app does not declare MODIFY_AUDIO_SETTINGS"));
            return;
        }
        try {
            if (mode.equals("default") || mode.equals("media")) {
                if (Build.VERSION.SDK_INT >= 31) {
                    am.clearCommunicationDevice();
                } else {
                    am.stopBluetoothSco();
                    am.setBluetoothScoOn(false);
                }
                if (savedAudioMode != null) am.setMode(savedAudioMode);
                savedAudioMode = null;
                if (mode.equals("default") && audioCallback != null) {
                    am.unregisterAudioDeviceCallback(audioCallback);
                    audioCallback = null;
                }
            } else {
                if (savedAudioMode == null) savedAudioMode = am.getMode();
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                if (Build.VERSION.SDK_INT >= 31) {
                    for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                        int t = d.getType();
                        if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                            am.setCommunicationDevice(d);
                            break;
                        }
                    }
                } else {
                    am.startBluetoothSco();
                    am.setBluetoothScoOn(true);
                }
            }
            if (!mode.equals("default") && audioCallback == null) {
                audioCallback = new AudioDeviceCallback() {
                    @Override
                    public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                        ctx.emit("audio", audioState(am));
                    }

                    @Override
                    public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                        ctx.emit("audio", audioState(am));
                    }
                };
                am.registerAudioDeviceCallback(audioCallback, main);
            }
        } catch (RuntimeException e) {
            reply.reply(ExtResult.error("audio_failed", String.valueOf(e.getMessage())));
            return;
        }
        reply.reply(ExtResult.ok(audioState(am).put("mode", mode)));
    }

    private AudioManager audioManager() {
        if (audioManager == null) audioManager = (AudioManager) ctx.app().getSystemService(android.content.Context.AUDIO_SERVICE);
        return audioManager;
    }

    private static boolean bluetooth(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER;
    }

    /** The connected inputs and outputs, the same keys as iOS. */
    private static JSONObject audioState(AudioManager am) {
        JSONArray inputs = new JSONArray();
        JSONArray outputs = new JSONArray();
        boolean glasses = false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            inputs.put(GlassesCapabilities.obj("name", String.valueOf(d.getProductName()), "type", d.getType(), "bluetooth", bluetooth(d.getType())));
        }
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            boolean bt = bluetooth(d.getType());
            glasses |= bt;
            outputs.put(GlassesCapabilities.obj("name", String.valueOf(d.getProductName()), "type", d.getType(), "bluetooth", bt));
        }
        JSONObject o = GlassesCapabilities.obj("inputs", inputs, "outputs", outputs, "glasses", glasses);
        if (Build.VERSION.SDK_INT >= 31) {
            AudioDeviceInfo c = am.getCommunicationDevice();
            try {
                o.put("communication", c == null ? JSONObject.NULL : String.valueOf(c.getProductName()));
            } catch (JSONException ignored) {
                // A plain key always sets.
            }
        }
        return o;
    }

    private void onStreamState(long g, StreamState st) {
        if (st == StreamState.STARTING || st == StreamState.STARTED || st == StreamState.STREAMING || st == StreamState.PAUSED) {
            if (g == core.gen) streamSeenActive = true;
        }
        if (st == StreamState.STREAMING) {
            if (core.streaming(g)) emitState("streaming");
            if (starting != null && starting.gen == g) {
                Call s = starting;
                starting = null;
                main.removeCallbacksAndMessages(startTimeout);
                try {
                    s.reply.reply(ExtResult.ok(status()));
                } catch (JSONException e) {
                    s.reply.reply(ExtResult.error("handler_error", "could not build the reply"));
                }
            }
        } else if (st == StreamState.PAUSED) {
            if (core.paused(g)) emitState("paused");
        } else if ((st == StreamState.STOPPED || st == StreamState.CLOSED) && streamSeenActive && g == core.gen) {
            ended(g, "stream_stopped");
        }
    }

    private void onStreamError(long g, StreamError e) {
        if (g != core.gen) return;
        if (e == StreamError.STREAM_ERROR) {
            // Informational (Meta, "Camera Streaming (Android)"): the stream goes on.
            emitState("stream_warning");
            return;
        }
        failStartOrEnd(g, streamError(e), "stream_error");
    }

    private void failStartOrEnd(long g, ExtResult error, String reason) {
        if (starting != null && starting.gen == g) {
            failStart(starting, error);
        } else {
            ended(g, reason);
        }
    }

    private void failStart(Call s, ExtResult error) {
        if (starting != s) return;
        starting = null;
        main.removeCallbacksAndMessages(startTimeout);
        teardown("start_failed");
        s.reply.reply(error);
    }

    /** The SDK ended generation g by itself. */
    private void ended(long g, String reason) {
        String clip = core.ended(g);
        if (clip == null) return;
        stopSdk();
        if (starting != null && starting.gen == g) {
            Call s = starting;
            starting = null;
            main.removeCallbacksAndMessages(startTimeout);
            s.reply.reply(ExtResult.error("no_device", "the stream ended before it started"));
        }
        if (!clip.isEmpty()) finishClip(clip, reason, null);
        emitState(reason);
    }

    /** An explicit stop (route, failed start or mock off): later SDK callbacks are stale. */
    private void teardown(String reason) {
        boolean wasActive = !core.state.equals("idle");
        String clip = core.stop();
        stopSdk();
        if (!clip.isEmpty()) finishClip(clip, reason, null);
        if (wasActive) emitState(reason);
    }

    /** Ends the camera (its stream and photo). The session stays while another capability uses it. */
    private void stopSdk() {
        liveGen = 0;
        DatInterop.cancel(frameScope);
        DatInterop.cancel(streamScope);
        frameScope = null;
        streamScope = null;
        // Camera.stop cascades to its stream.
        if (camera != null) camera.stop();
        camera = null;
        stream = null;
        if (server != null) server.endLive();
        releaseSessionIfIdle();
    }

    /** On the frame thread: the stream's PCM goes to an open clip only. */
    private void onAudio(long g, AudioFrame f) {
        if (g != liveGen) return;
        GlassesMedia.ClipRecorder r = recorder;
        if (r != null) r.offerAudio(f.getBuffer(), f.getPresentationTimeUs());
    }

    /** On the frame thread (Dispatchers.Default), one frame at a time. */
    private void onFrame(long g, VideoFrame frame) {
        if (g != liveGen || frame.isCompressed()) return;
        ByteBuffer buf = frame.getBuffer();
        int w = frame.getWidth();
        int h = frame.getHeight();
        byte[] jpeg = encoder.jpeg(buf, w, h, GlassesMedia.PREVIEW_QUALITY);
        GlassesServer s = server;
        if (jpeg == null || s == null || g != liveGen) return;
        s.publish(jpeg);
        frameWidth = w;
        frameHeight = h;
        frames++;
        GlassesMedia.ClipRecorder r = recorder;
        if (r == null) return;
        try {
            r.offer(buf, w, h, frame.getPresentationTimeUs());
            if (r.sizeChanged) main.post(() -> clipEnded(r, "resolution_changed"));
        } catch (IOException | RuntimeException e) {
            main.post(() -> clipEnded(r, "encoder_failed"));
        }
    }

    // ------------------------------------------------------------------------ photo and clips

    private void photo(ExtResult.Callback reply) {
        if (!core.state.equals("streaming") || stream == null) {
            reply.reply(ExtResult.error("not_streaming", "start the stream first"));
            return;
        }
        if (photoBusy) {
            reply.reply(ExtResult.error("busy", "a photo is already being taken"));
            return;
        }
        photoBusy = true;
        Stream st = stream;
        DatInterop.<DatResult<PhotoData, CaptureError>>call(st::capturePhoto, (r, error) -> {
            if (error != null || r == null || !r.isSuccess()) {
                photoBusy = false;
                reply.reply(ExtResult.error("capture_failed", r == null ? "the capture failed" : describe(r.errorOrNull())));
                return;
            }
            PhotoData data = r.getOrNull();
            String id = newId("p");
            worker.execute(() -> {
                ExtResult out;
                try {
                    Bitmap bitmap;
                    if (data instanceof PhotoData.Bitmap) {
                        bitmap = ((PhotoData.Bitmap) data).getBitmap();
                    } else {
                        ByteBuffer heic = ((PhotoData.HEIC) data).getData().duplicate();
                        byte[] bytes = new byte[heic.remaining()];
                        heic.get(bytes);
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    }
                    byte[] jpeg = bitmap == null ? null : GlassesMedia.jpeg(bitmap, GlassesMedia.PHOTO_QUALITY);
                    if (jpeg == null) throw new IOException("the photo could not be encoded");
                    File file = new File(mediaDir(), id + ".jpg");
                    GlassesMedia.write(file, jpeg);
                    JSONObject m = mediaJson(id, "jpg", file);
                    m.put("width", bitmap.getWidth());
                    m.put("height", bitmap.getHeight());
                    out = ExtResult.ok(m);
                } catch (IOException | JSONException | RuntimeException e) {
                    out = ExtResult.error("capture_failed", "the photo could not be saved");
                }
                ExtResult result = out;
                main.post(() -> {
                    photoBusy = false;
                    if (result.isOk()) ctx.emit("media", result.data());
                    reply.reply(result);
                });
            });
        });
    }

    /**
     * A high-quality still through DAT's standalone photo capability (Camera.photo, experimental in
     * DAT 1.1.0): the glasses take the picture at resolution and quality and transfer it, reporting
     * photo_progress. Meta's "Camera Capture" guide (meta-wearables-dat-android, 1.1.0): the stream
     * and the standalone photo compete for the camera, so a still needs the stream stopped; Photo
     * starts when Camera.photo is first read, and a capture waits for PhotoState.STARTED. Photo is
     * terminal after stop, so each still adds its own Camera to the shared session and stops it after.
     */
    private void stillPhoto(ExtCall call, ExtResult.Callback reply) {
        if (!core.state.equals("idle")) {
            reply.reply(ExtResult.error("busy", "stop the stream first: the stream and still photos share the glasses camera"));
            return;
        }
        if (photoBusy || stillReply != null) {
            reply.reply(ExtResult.error("busy", "a photo is already being taken"));
            return;
        }
        String res = str(call, "resolution") == null ? "medium" : str(call, "resolution");
        String q = str(call, "quality") == null ? "medium" : str(call, "quality");
        PhotoResolution pr = res.equals("small") ? PhotoResolution.SMALL : res.equals("large") ? PhotoResolution.LARGE
                : res.equals("full") ? PhotoResolution.FULL : PhotoResolution.MEDIUM;
        PhotoQuality pq = q.equals("low") ? PhotoQuality.LOW : q.equals("high") ? PhotoQuality.HIGH : PhotoQuality.MEDIUM;
        photoBusy = true;
        stillReply = reply;
        stillResolution = res;
        stillQuality = q;
        stillCaptured = false;
        Object token = new Object();
        stillTimeout = token;
        long at = call.deadlineUptimeMillis > 0 ? call.deadlineUptimeMillis - 100 : SystemClock.uptimeMillis() + 20000;
        main.postAtTime(() -> {
            if (stillTimeout == token) finishStill(ExtResult.error("timeout", "no photo arrived from the glasses"));
        }, token, at);
        openSession(call, r -> {
            if (stillTimeout == token) finishStill(r);
        }, null, sess -> {
            if (stillTimeout == token) attachStill(sess, pr, pq);
        });
    }

    /** The session is started: the still's own Camera, then its Photo, then one capture once STARTED. */
    private void attachStill(DeviceSession s, PhotoResolution pr, PhotoQuality pq) {
        if (mock && mockGlasses != null) {
            // The mock kit's captured photo is the next image (Meta, "Camera Capture"): set it for each still.
            File photo = new File(new File(ctx.dataDir(), "mock"), "photo.jpg");
            if (photo.isFile()) mockGlasses.getServices().getCameraCapture().setCapturedPhoto(Uri.fromFile(photo));
        }
        // The stream settings do not apply to a still; DAT 1.1.0 has no Java no-argument StreamConfiguration.
        DatResult<Camera, DeviceSessionError> c = SessionCameraExtensionsKt.addCamera(s, new StreamConfiguration(null, VideoQuality.MEDIUM, 24, false));
        if (!c.isSuccess() || c.getOrNull() == null) {
            finishStill(GlassesCapabilities.session(c.errorOrNull()));
            return;
        }
        stillCamera = c.getOrNull();
        Photo p = stillCamera.getPhoto();
        if (p == null) {
            finishStill(ExtResult.error("capture_failed", "the camera has no photo capability"));
            return;
        }
        cameraPhoto = p;
        photoScope = DatInterop.mainScope();
        // Collected in launch order on the main thread: the photo, error and progress collectors are
        // subscribed before the state collector sees STARTED and asks for the capture.
        DatInterop.collect(photoScope, p.getPhotoStream(), this::onStill);
        DatInterop.collect(photoScope, p.getErrors(), (PhotoError e) -> {
            if (e != null && stillReply != null) finishStill(photoError(e));
        });
        DatInterop.collect(photoScope, p.getTransferProgressStream(), t -> ctx.emit("photo_progress",
                GlassesCapabilities.obj("received", t.getBytesReceived(), "total", t.getTotalBytes(), "fraction", t.getFraction())));
        DatInterop.collect(photoScope, p.getState(), (PhotoState st) -> {
            if (st == PhotoState.STARTED && !stillCaptured && stillReply != null && cameraPhoto == p) {
                stillCaptured = true;
                p.capturePhoto(pr, pq);
            } else if (st == PhotoState.STOPPED && stillCaptured && stillReply != null && cameraPhoto == p) {
                finishStill(ExtResult.error("capture_failed", "the photo stopped before it arrived"));
            }
        });
    }

    /** Ends the still's Photo and Camera; the session stays while another capability uses it. */
    private void endStill() {
        CoroutineScope sc = photoScope;
        photoScope = null;
        DatInterop.cancel(sc);
        Photo p = cameraPhoto;
        cameraPhoto = null;
        Camera c = stillCamera;
        stillCamera = null;
        if (p != null) p.stop();
        // Camera.stop cascades to its photo.
        if (c != null) c.stop();
    }

    private void finishStill(ExtResult r) {
        ExtResult.Callback reply = stillReply;
        stillReply = null;
        if (stillTimeout != null) main.removeCallbacksAndMessages(stillTimeout);
        stillTimeout = null;
        photoBusy = false;
        endStill();
        releaseSessionIfIdle();
        if (reply != null) reply.reply(r);
    }

    private void onStill(PhotoCaptureData d) {
        if (stillReply == null || d == null) return;
        ExtResult.Callback reply = stillReply;
        stillReply = null;
        if (stillTimeout != null) main.removeCallbacksAndMessages(stillTimeout);
        stillTimeout = null;
        endStill();
        releaseSessionIfIdle();
        String res = stillResolution;
        String q = stillQuality;
        String id = newId("p");
        worker.execute(() -> {
            ExtResult out;
            try {
                byte[] bytes = d.getImageData();
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                boolean isJpeg = bytes.length > 2 && (bytes[0] & 0xff) == 0xFF && (bytes[1] & 0xff) == 0xD8;
                byte[] jpeg = bytes;
                if (!isJpeg) {
                    Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    jpeg = b == null ? null : GlassesMedia.jpeg(b, GlassesMedia.PHOTO_QUALITY);
                }
                if (jpeg == null) throw new IOException("the photo could not be encoded");
                File file = new File(mediaDir(), id + ".jpg");
                GlassesMedia.write(file, jpeg);
                JSONObject m = mediaJson(id, "jpg", file);
                m.put("width", bounds.outWidth).put("height", bounds.outHeight).put("resolution", res).put("quality", q);
                out = ExtResult.ok(m);
            } catch (IOException | JSONException | RuntimeException e) {
                out = ExtResult.error("capture_failed", "the photo could not be saved");
            }
            ExtResult result = out;
            main.post(() -> {
                photoBusy = false;
                if (result.isOk()) ctx.emit("media", result.data());
                reply.reply(result);
            });
        });
    }

    private static ExtResult photoError(PhotoError e) {
        if (e == PhotoError.PERMISSION_DENIED) return ExtResult.error("permission_denied", describe(e));
        if (e == PhotoError.DEVICE_DISCONNECTED) return ExtResult.error("no_device", describe(e));
        if (e == PhotoError.BUSY) return ExtResult.error("busy", describe(e));
        if (e == PhotoError.DEVICE_HEALTH_CRITICAL) return ExtResult.error("device_limit", describe(e));
        if (e == PhotoError.SERVICE_UNAVAILABLE || e == PhotoError.NOT_READY) return ExtResult.error("unavailable", describe(e));
        return ExtResult.error("capture_failed", describe(e));
    }

    private void recordStart(ExtCall call, ExtResult.Callback reply) throws JSONException {
        Object s = call.params.get("seconds");
        int seconds = s instanceof Number ? ((Number) s).intValue() : 10;
        String id = newId("c");
        long cg = core.clipBegin(id);
        if (cg < 0) {
            reply.reply(core.clip != null ? ExtResult.error("busy", "a clip is already recording")
                    : ExtResult.error("not_streaming", "start the stream first"));
            return;
        }
        // Written as .mp4.part and renamed when finished, so the server never serves half a file.
        // With the stream's audio on, the clip carries it as AAC beside the H.264 video.
        recorder = new GlassesMedia.ClipRecorder(new File(mediaDir(), id + ".mp4.part"), fps, audio ? audioRate : 0, audioChannels);
        recorderGen = cg;
        recorderSeconds = seconds;
        main.postAtTime(() -> stopClip(cg, "limit", null), clipTimer, SystemClock.uptimeMillis() + seconds * 1000L);
        reply.reply(ExtResult.ok(new JSONObject().put("id", id).put("kind", "mp4").put("seconds", seconds).put("audio", audio)
                .put("url", server.base() + "/media/" + id + ".mp4")));
    }

    private void recordStop(ExtResult.Callback reply) {
        if (core.clip == null) {
            reply.reply(ExtResult.error("not_recording", "no clip is recording"));
            return;
        }
        stopClip(core.clipGen, "stopped", reply);
    }

    /** The frame thread saw the clip end on its own (size change, encoder error). */
    private void clipEnded(GlassesMedia.ClipRecorder r, String reason) {
        if (recorder == r) stopClip(recorderGen, reason, null);
    }

    private void stopClip(long cg, String reason, ExtResult.Callback reply) {
        String id = core.clipEnd(cg);
        if (id == null) {
            if (reply != null) reply.reply(ExtResult.error("not_recording", "no clip is recording"));
            return;
        }
        finishClip(id, reason, reply);
    }

    private void finishClip(String id, String reason, ExtResult.Callback reply) {
        GlassesMedia.ClipRecorder r = recorder;
        recorder = null;
        main.removeCallbacksAndMessages(clipTimer);
        File part = new File(mediaDir(), id + ".mp4.part");
        File file = new File(mediaDir(), id + ".mp4");
        worker.execute(() -> {
            boolean ok = r != null && r.finish() && part.renameTo(file);
            main.post(() -> {
                try {
                    if (ok) {
                        JSONObject m = mediaJson(id, "mp4", file).put("durationMs", r.durationMs()).put("reason", reason);
                        ctx.emit("media", m);
                        if (reply != null) reply.reply(ExtResult.ok(m));
                    } else {
                        part.delete();
                        ctx.emit("media", new JSONObject().put("id", id).put("kind", "mp4").put("failed", true).put("reason", reason));
                        if (reply != null) reply.reply(ExtResult.error("record_failed", "the clip has no frames or could not be written"));
                    }
                } catch (JSONException e) {
                    if (reply != null) reply.reply(ExtResult.error("handler_error", "could not build the reply"));
                }
            });
        });
    }

    // ------------------------------------------------------------------- frames and media files

    /**
     * The newest frame as base64 JPEG, for pages that cannot load the loopback URL. The page
     * compares seq with the last one it drew; polling faster than the stream's rate returns the
     * same frame again.
     */
    private void frame(ExtResult.Callback reply) throws JSONException {
        Object[] l = server == null ? new Object[] {0L, null} : server.latest();
        long seq = (Long) l[0];
        byte[] jpeg = (byte[]) l[1];
        JSONObject o = new JSONObject().put("seq", seq).put("width", frameWidth).put("height", frameHeight);
        o.put("jpeg", jpeg != null ? Base64.encodeToString(jpeg, Base64.NO_WRAP) : JSONObject.NULL);
        reply.reply(ExtResult.ok(o));
    }

    private File mediaDir() {
        if (!mediaDir.isDirectory()) mediaDir.mkdirs();
        return mediaDir;
    }

    private JSONObject mediaJson(String id, String kind, File f) throws JSONException {
        JSONObject o = new JSONObject().put("id", id).put("kind", kind).put("bytes", f.length());
        o.put("url", server == null ? JSONObject.NULL : server.base() + "/media/" + id + "." + kind);
        return o;
    }

    private JSONArray mediaList() throws JSONException {
        JSONArray items = new JSONArray();
        File[] files = mediaDir().listFiles();
        if (files == null) return items;
        for (File f : files) {
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            if (dot <= 0) continue;
            String id = n.substring(0, dot);
            String kind = n.substring(dot + 1);
            if (GlassesCore.isMediaId(id) && (kind.equals("jpg") || kind.equals("mp4")) && !id.equals(core.clip)) {
                items.put(mediaJson(id, kind, f));
            }
        }
        return items;
    }

    private File mediaFile(ExtCall call) {
        Object id = call.params.get("id");
        Object kind = call.params.get("kind");
        if (!(id instanceof String) || !GlassesCore.isMediaId((String) id) || !(kind instanceof String)) return null;
        if (id.equals(core.clip)) return null;
        File f = new File(mediaDir(), id + "." + kind);
        return f.isFile() ? f : null;
    }

    private void mediaRead(ExtCall call, ExtResult.Callback reply) throws JSONException {
        File f = mediaFile(call);
        if (f == null) {
            reply.reply(ExtResult.error("not_found", "no such photo or clip"));
            return;
        }
        Object c = call.params.get("chunk");
        long chunk = c instanceof Number ? ((Number) c).longValue() : 0;
        long offset = chunk * MEDIA_CHUNK;
        int length = MEDIA_CHUNK;
        long total = f.length();
        byte[] buf = new byte[(int) Math.max(0, Math.min(length, total - offset))];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(Math.min(offset, total));
            raf.readFully(buf);
        } catch (IOException e) {
            reply.reply(ExtResult.error("not_found", "the file could not be read"));
            return;
        }
        reply.reply(ExtResult.ok(new JSONObject().put("id", call.params.get("id")).put("kind", call.params.get("kind"))
                .put("chunk", chunk).put("offset", offset).put("length", buf.length).put("total", total)
                .put("eof", offset + buf.length >= total).put("data", Base64.encodeToString(buf, Base64.NO_WRAP))));
    }

    private void mediaDelete(ExtCall call, ExtResult.Callback reply) throws JSONException {
        File f = mediaFile(call);
        if (f == null || !f.delete()) {
            reply.reply(ExtResult.error("not_found", "no such photo or clip"));
            return;
        }
        reply.reply(ExtResult.ok(new JSONObject().put("id", call.params.get("id")).put("kind", call.params.get("kind")).put("deleted", true)));
    }

    // ----------------------------------------------------------------------- Mock Device Kit

    /**
     * Meta's Mock Device Kit: fake registration, a paired Ray-Ban Meta mock and a synthesized
     * test feed (red left, blue right, moving white bar), so the emulator streams without glasses.
     */
    private void mock(ExtCall call, ExtResult.Callback reply) throws JSONException {
        if ((ctx.app().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            reply.reply(ExtResult.error("mock_unavailable", "the mock glasses run in debug builds only"));
            return;
        }
        boolean on = Boolean.TRUE.equals(call.params.get("enabled"));
        MockDeviceKitInterface kit = MockDeviceKit.Companion.getInstance(ctx.app());
        if (!on) {
            teardown("mock_off");
            endSession("mock_off");
            if (mockGlasses != null) kit.unpairDevice(mockGlasses);
            mockGlasses = null;
            kit.disable();
            mock = false;
            reply.reply(ExtResult.ok(status()));
            return;
        }
        if (mock) {
            reply.reply(ExtResult.ok(status()));
            return;
        }
        File dir = new File(ctx.dataDir(), "mock");
        worker.execute(() -> {
            ExtResult failure = null;
            File feed = new File(dir, "feed.mp4");
            File photo = new File(dir, "photo.jpg");
            String codec = null;
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no mock directory");
                codec = GlassesMedia.mockFeed(feed);
                byte[] p = GlassesMedia.mockPhoto();
                if (p == null) throw new IOException("the mock photo could not be encoded");
                GlassesMedia.write(photo, p);
            } catch (IOException | RuntimeException e) {
                failure = ExtResult.error("mock_failed", "the mock media could not be written");
            }
            ExtResult f = failure;
            String c = codec;
            main.post(() -> {
                if (f != null) {
                    reply.reply(f);
                    return;
                }
                // DAT 1.1.0 adds isDevMode; null leaves the simulated registration mode as it is.
                kit.enable(new MockDeviceKitConfig(true, true, null));
                GlassesModel model = GlassesModel.RAYBAN_META;
                Object asked = call.params.get("model");
                for (GlassesModel m : GlassesModel.values()) {
                    if (m.name().toLowerCase(Locale.ROOT).equals(asked)) model = m;
                }
                DatResult<MockGlasses, MockDeviceKitError> g = kit.pairGlasses(model);
                if (!g.isSuccess()) {
                    kit.disable();
                    reply.reply(ExtResult.error("mock_failed", describe(g.errorOrNull())));
                    return;
                }
                mockGlasses = g.getOrNull();
                mockGlasses.powerOn();
                mockGlasses.unfold();
                mockGlasses.don();
                mockGlasses.getServices().getCamera().setCameraFeed(Uri.fromFile(feed));
                mockGlasses.getServices().getCamera().setCapturedImage(Uri.fromFile(photo));
                mockGlasses.getServices().getCameraCapture().setCapturedPhoto(Uri.fromFile(photo));
                mock = true;
                try {
                    reply.reply(ExtResult.ok(status().put("mockFeedCodec", c)));
                } catch (JSONException e) {
                    reply.reply(ExtResult.error("handler_error", "could not build the reply"));
                }
            });
        });
    }

    /**
     * Drives the Mock Device Kit's simulated glasses (debug builds, after mock enabled true), so a
     * page can exercise every event without real glasses.
     */
    private void mockEvent(ExtCall call, ExtResult.Callback reply) throws JSONException {
        if ((ctx.app().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            reply.reply(ExtResult.error("mock_unavailable", "the mock glasses run in debug builds only"));
            return;
        }
        MockGlasses g = mockGlasses;
        if (g == null) {
            reply.reply(ExtResult.error("not_active", "turn the mock glasses on first (mock enabled true)"));
            return;
        }
        String ev = str(call, "event") == null ? "" : str(call, "event");
        Object v = call.params.get("value");
        int value = v instanceof Number ? ((Number) v).intValue() : 0;
        String text = str(call, "text") == null ? "" : str(call, "text");
        InputSource source = GlassesCapabilities.Sensors.SOURCES.containsKey(text) ? GlassesCapabilities.Sensors.SOURCES.get(text) : InputSource.CAPTOUCH;
        JSONObject out = new JSONObject().put("event", ev);
        switch (ev) {
            case "power_on": g.powerOn(); break;
            case "power_off": g.powerOff(); break;
            case "don": g.don(); break;
            case "doff": g.doff(); break;
            case "fold": g.fold(); break;
            case "unfold": g.unfold(); break;
            case "battery": g.setBatteryLevel(value); break;
            case "charging": g.setChargingState(value != 0 ? ChargingState.CHARGING : ChargingState.NOT_CHARGING); break;
            case "thermal": {
                ThermalLevel[] levels = {ThermalLevel.NONE, ThermalLevel.LIGHT, ThermalLevel.MODERATE, ThermalLevel.SEVERE,
                        ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY, ThermalLevel.SHUTDOWN};
                g.setThermalLevel(levels[Math.max(0, Math.min(levels.length - 1, value))]);
                break;
            }
            case "tap": g.getServices().getCaptouch().tap(); break;
            case "tap_and_hold": g.getServices().getCaptouch().tapAndHold(); break;
            case "nav_up": g.getServices().getInput().navUp(source); break;
            case "nav_down": g.getServices().getInput().navDown(source); break;
            case "nav_left": g.getServices().getInput().navLeft(source); break;
            case "nav_right": g.getServices().getInput().navRight(source); break;
            case "select": g.getServices().getInput().select(source); break;
            case "back": g.getServices().getInput().back(source); break;
            case "capture":
                g.getServices().getInput().capture(text.equals("hold") ? CapturePressType.HOLD
                        : text.equals("double") ? CapturePressType.DOUBLE_PRESS : CapturePressType.SHORT_PRESS);
                break;
            case "action_button": g.getServices().getInput().button(ButtonType.ACTION); break;
            case "drag": g.getServices().getInput().drag(DragAction.MOVE, 0f, 0f, value, 0f); break;
            case "transcription": g.getServices().getSpeech().simulateTranscription(text, value != 0, 0.9f); break;
            case "speech_locale": g.getServices().getSpeech().setLocale(text); break;
            case "voice_launch": {
                String a = g.getServices().getVoiceInvocation().simulateLaunchAppAction();
                out.put("action", a == null ? JSONObject.NULL : a);
                break;
            }
            case "photo_failure": g.getServices().getCameraCapture().simulateCaptureFailure(); break;
            case "app_update_required": g.getServices().setDATAppUpdateRequirementEnabled(value != 0); break;
            case "firmware_update_required": g.getServices().setFirmwareUpdateRequirementEnabled(value != 0); break;
            case "camera_permission":
            case "microphone_permission":
                MockDeviceKit.Companion.getInstance(ctx.app()).getPermissions().set(
                        ev.equals("camera_permission") ? Permission.CAMERA : Permission.MICROPHONE,
                        value != 0 ? PermissionStatus.Granted.INSTANCE : PermissionStatus.Denied.INSTANCE);
                break;
            case "display_click": out.put("delivered", g.getServices().getDisplay().sendClick(text)); break;
            default:
                reply.reply(ExtResult.error("bad_request", "unknown mock event"));
                return;
        }
        reply.reply(ExtResult.ok(out));
    }

    // -------------------------------------------------------------------------------- helpers

    private static String str(ExtCall call, String key) {
        Object v = call.params.get(key);
        return v instanceof String ? (String) v : null;
    }

    private void replyStatus(ExtResult.Callback reply) {
        try {
            reply.reply(ExtResult.ok(status()));
        } catch (JSONException e) {
            reply.reply(ExtResult.error("handler_error", "could not build the reply"));
        }
    }

    private String newId(String prefix) {
        return prefix + Long.toString(System.currentTimeMillis(), 36) + String.format(Locale.ROOT, "%04x", random.nextInt(0x10000));
    }

    private static String name(RegistrationState s) {
        return s == null ? "unavailable" : s.name().toLowerCase(Locale.ROOT);
    }

    private static String describe(DatError e) {
        if (e == null) return "unknown error";
        String d = e.getDescription();
        return d == null || d.isEmpty() ? e.toString() : d;
    }

    private static ExtResult sessionError(DeviceSessionError e) {
        return GlassesCapabilities.session(e);
    }

    /** The registration error codes, the same words iOS returns from register. */
    static String registrationError(RegistrationError e) {
        if (e == RegistrationError.META_AI_NOT_INSTALLED) return "meta_ai_missing";
        if (e == RegistrationError.UNAUTHORIZED) return "registration_unauthorized";
        return "registration_failed";
    }

    private static ExtResult streamError(StreamError e) {
        if (e == StreamError.PERMISSIONS_DENIED) return ExtResult.error("permission_denied", describe(e));
        if (e == StreamError.HINGE_CLOSED) return ExtResult.error("glasses_folded", describe(e));
        if (e == StreamError.THERMAL_HOT || e == StreamError.BATTERY_LOW || e == StreamError.PEAK_POWER_LIMIT) {
            return ExtResult.error("device_limit", describe(e));
        }
        if (e == StreamError.TIMEOUT) return ExtResult.error("timeout", describe(e));
        return ExtResult.error("stream_failed", describe(e));
    }
}
