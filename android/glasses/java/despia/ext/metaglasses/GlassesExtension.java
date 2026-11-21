package despia.ext.metaglasses;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import com.meta.wearable.dat.camera.types.CaptureError;
import com.meta.wearable.dat.camera.types.PhotoData;
import com.meta.wearable.dat.camera.types.StreamConfiguration;
import com.meta.wearable.dat.camera.types.StreamError;
import com.meta.wearable.dat.camera.types.StreamState;
import com.meta.wearable.dat.camera.types.VideoFrame;
import com.meta.wearable.dat.camera.types.VideoQuality;
import com.meta.wearable.dat.core.Wearables;
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector;
import com.meta.wearable.dat.core.session.DeviceSession;
import com.meta.wearable.dat.core.session.DeviceSessionState;
import com.meta.wearable.dat.core.types.DatError;
import com.meta.wearable.dat.core.types.DatResult;
import com.meta.wearable.dat.core.types.DeviceSessionError;
import com.meta.wearable.dat.core.types.Permission;
import com.meta.wearable.dat.core.types.PermissionError;
import com.meta.wearable.dat.core.types.PermissionStatus;
import com.meta.wearable.dat.core.types.RegistrationError;
import com.meta.wearable.dat.core.types.RegistrationState;
import com.meta.wearable.dat.core.types.WearablesError;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlinx.coroutines.CoroutineScope;

/**
 * Meta Ray-Ban glasses camera through Meta's Wearables Device Access Toolkit (DAT) 1.1.0.
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

    private static final class Call {
        final ExtCall call;
        final ExtResult.Callback reply;
        final long gen;
        boolean prompt;

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
     * registration request on, when the intent carries one.
     */
    @Override
    public boolean onIntent(Activity a, Intent intent) {
        if (!bluetoothGranted() || !ensureInit()) return false;
        DatResult<Boolean, ?> r = Wearables.INSTANCE.handleIntent(intent, request -> kotlin.Unit.INSTANCE);
        Boolean handled = r.getOrNull();
        return handled != null && handled;
    }

    @Override
    public void handle(ExtCall call, ExtResult.Callback reply) {
        try {
            if ("mock".equals(call.route) && (ctx.app().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
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
                    photo(reply);
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
        permissionWait = w;
        if (!bluetoothGranted()) {
            if (!w.prompt) {
                finishPermission(w, okPermission("denied", "unknown"));
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

    private void checkCamera(Call w) {
        if (!ensureInit()) {
            finishPermission(w, ExtResult.error("init_failed", "the glasses SDK could not start"));
            return;
        }
        if (Wearables.INSTANCE.getRegistrationState().getValue() != RegistrationState.REGISTERED) {
            // The camera permission belongs to a registered app; Meta AI cannot answer it before.
            finishPermission(w, okPermission("granted", "unknown"));
            return;
        }
        DatInterop.<DatResult<PermissionStatus, PermissionError>>call(
                c -> Wearables.INSTANCE.checkPermissionStatus(Permission.CAMERA, c), (r, error) -> {
                    if (permissionWait != w) return;
                    if (error != null || r == null) {
                        finishPermission(w, ExtResult.error("permission_failed", "the permission check failed"));
                    } else if (!r.isSuccess()) {
                        finishPermission(w, permissionError(r.errorOrNull()));
                    } else if (r.getOrNull() instanceof PermissionStatus.Granted) {
                        finishPermission(w, okPermission("granted", "granted"));
                    } else if (!w.prompt) {
                        finishPermission(w, okPermission("granted", "denied"));
                    } else if (!w.call.isActive()) {
                        finishPermission(w, ExtResult.error("timeout", "the permission check took too long"));
                    } else if (permissionLauncher == null) {
                        finishPermission(w, ExtResult.error("unavailable", "no activity is showing"));
                    } else {
                        // Switches to the Meta AI app; the answer comes back to onPermissionResult.
                        permissionLauncher.launch(Permission.CAMERA);
                    }
                });
    }

    private void onPermissionResult(DatResult<PermissionStatus, PermissionError> r) {
        Call w = permissionWait;
        if (w == null) return;
        if (r == null || !r.isSuccess()) {
            finishPermission(w, r == null ? ExtResult.error("permission_failed", "no answer") : permissionError(r.errorOrNull()));
        } else {
            finishPermission(w, okPermission("granted", r.getOrNull() instanceof PermissionStatus.Granted ? "granted" : "denied"));
        }
    }

    private void finishPermission(Call w, ExtResult result) {
        if (permissionWait == w) permissionWait = null;
        w.reply.reply(result);
    }

    private static ExtResult okPermission(String bluetooth, String camera) {
        try {
            return ExtResult.ok(new JSONObject().put("bluetooth", bluetooth).put("camera", camera));
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
        Object q = call.params.get("quality");
        Object f = call.params.get("fps");
        String wantQuality = q instanceof String ? (String) q : "medium";
        int wantFps = GlassesCore.frameRate(f instanceof Number ? ((Number) f).intValue() : 24);
        try {
            if (server == null) server = GlassesServer.start(mediaDir());
        } catch (IOException e) {
            reply.reply(ExtResult.error("server_failed", "the local stream server could not start"));
            return;
        }
        DatResult<DeviceSession, DeviceSessionError> r = Wearables.INSTANCE.createSession(new AutoDeviceSelector());
        if (!r.isSuccess()) {
            reply.reply(sessionError(r.errorOrNull()));
            return;
        }
        long g = core.begin();
        quality = wantQuality;
        fps = wantFps;
        frameWidth = 0;
        frameHeight = 0;
        session = r.getOrNull();
        streamSeenActive = false;
        Call s = new Call(call, reply, g);
        starting = s;
        streamScope = DatInterop.mainScope();
        frameScope = DatInterop.backgroundScope();
        DatInterop.collect(streamScope, session.getErrors(), e -> onSessionError(g, e));
        DatInterop.collect(streamScope, session.getState(), st -> onSessionState(g, st));
        session.start();
        long at = call.deadlineUptimeMillis > 0 ? call.deadlineUptimeMillis - 100 : SystemClock.uptimeMillis() + 20000;
        main.postAtTime(() -> {
            if (starting == s) failStart(s, ExtResult.error("timeout", "the glasses did not start streaming in time"));
        }, startTimeout, at);
        emitState("starting");
    }

    private void onSessionState(long g, DeviceSessionState st) {
        if (g != core.gen || session == null) return;
        if (st == DeviceSessionState.STARTED && camera == null) {
            VideoQuality vq = quality.equals("high") ? VideoQuality.HIGH : quality.equals("low") ? VideoQuality.LOW : VideoQuality.MEDIUM;
            // Decoded YUV frames (compressVideo false): the preview needs no decoder of its own.
            DatResult<Camera, DeviceSessionError> c = SessionCameraExtensionsKt.addCamera(session,
                    new StreamConfiguration(null, vq, fps, false));
            if (!c.isSuccess()) {
                failStartOrEnd(g, sessionError(c.errorOrNull()), "camera_failed");
                return;
            }
            camera = c.getOrNull();
            stream = camera.getStream();
            liveGen = g;
            DatInterop.collect(streamScope, stream.getState(), x -> onStreamState(g, x));
            DatInterop.collect(streamScope, stream.getErrorStream(), x -> onStreamError(g, x));
            DatInterop.collect(frameScope, stream.getVideoStream(), x -> onFrame(g, x));
            DatResult<kotlin.Unit, StreamError> sr = stream.start();
            if (!sr.isSuccess()) failStartOrEnd(g, streamError(sr.errorOrNull()), "stream_failed");
        } else if (st == DeviceSessionState.STOPPED && streamSeenActive) {
            ended(g, "session_stopped");
        } else if (st == DeviceSessionState.STOPPED && starting != null && starting.gen == g) {
            failStart(starting, ExtResult.error("no_device", "the glasses session stopped before streaming"));
        }
    }

    private void onSessionError(long g, DeviceSessionError e) {
        if (g != core.gen) return;
        failStartOrEnd(g, sessionError(e), "session_error");
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

    private void stopSdk() {
        liveGen = 0;
        DatInterop.cancel(frameScope);
        DatInterop.cancel(streamScope);
        frameScope = null;
        streamScope = null;
        // Camera.stop cascades to its stream; DeviceSession.stop to every capability.
        if (camera != null) camera.stop();
        if (session != null) session.stop();
        camera = null;
        stream = null;
        session = null;
        if (server != null) server.endLive();
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
        recorder = new GlassesMedia.ClipRecorder(new File(mediaDir(), id + ".mp4.part"), fps);
        recorderGen = cg;
        recorderSeconds = seconds;
        main.postAtTime(() -> stopClip(cg, "limit", null), clipTimer, SystemClock.uptimeMillis() + seconds * 1000L);
        reply.reply(ExtResult.ok(new JSONObject().put("id", id).put("kind", "mp4").put("seconds", seconds)
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
                DatResult<MockGlasses, MockDeviceKitError> g = kit.pairGlasses(GlassesModel.RAYBAN_META);
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
                mock = true;
                try {
                    reply.reply(ExtResult.ok(status().put("mockFeedCodec", c)));
                } catch (JSONException e) {
                    reply.reply(ExtResult.error("handler_error", "could not build the reply"));
                }
            });
        });
    }

    // -------------------------------------------------------------------------------- helpers

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
        if (e == DeviceSessionError.NO_ELIGIBLE_DEVICE || e == DeviceSessionError.DEVICE_DISCONNECTED) {
            return ExtResult.error("no_device", describe(e));
        }
        if (e == DeviceSessionError.CAPABILITY_DENIED) return ExtResult.error("permission_denied", describe(e));
        if (e == DeviceSessionError.SESSION_ALREADY_EXISTS) return ExtResult.error("busy", describe(e));
        if (e == DeviceSessionError.NOT_REGISTERED) return ExtResult.error("registration_required", describe(e));
        return ExtResult.error("session_failed", describe(e));
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
