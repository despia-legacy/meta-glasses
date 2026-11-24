#if DESPIA_NX_ENABLED
import AVFoundation
import CoreBluetooth
import Foundation
import MWDATCamera
import MWDATCore
import MWDATMockDevice
import UIKit

/// Meta glasses through Meta's Wearables Device Access Toolkit (DAT) 1.1.0: registration with the
/// Meta AI app (and Meta AI-initiated registration requests), camera and microphone permissions,
/// devices and their state, the device session (start, pause, resume, stop), the camera stream with
/// optional audio, in-stream and high-quality photos, clips, speech, motion, inputs, voice
/// invocations, the glasses display, Bluetooth audio routing, update prompts and the Mock Device Kit.
///
/// The page starts and stops the glasses' camera stream and shows it from a loopback URL
/// (`GlassesServer`): `<img src=live>` is a multipart JPEG stream that always carries the newest
/// frame. Photos and short clips are saved in the extension's directory and served from the same
/// server, with byte ranges for `<video>`. Nothing starts at launch: the SDK is configured by the
/// first route call or by the Meta AI app's return URL, the stream by `start`.
///
/// DAT 1.1.0 needs iOS 17.2; the app's deployment target is lower, so every DAT type lives in
/// `GlassesController` (available from 17.2) and an older system answers `platform_limit`.
/// The SDK reads its app id and client token from the MWDAT Info.plist dictionary the customer's
/// project fills (TEMPLATE.md); this code reads none of them.
@MainActor
final class GlassesExtension: NativeExtension {
    private var context: NativeExtContext?
    private var controllerBox: AnyObject?

    init() {}

    func didLaunch(_ context: NativeExtContext) {
        self.context = context
    }

    func handle(_ call: NativeExtCall) async -> NativeExtResult {
        guard #available(iOS 17.2, *) else {
            return .error(code: "platform_limit", message: "Meta's glasses SDK needs iOS 17.2 or later")
        }
        if call.route == "mock" || call.route == "mock_event", !GlassesController.mockAllowed {
            return .error(code: "mock_unavailable", message: "the mock glasses run in debug builds only")
        }
        guard let c = controller() else { return .error(code: "init_failed", message: "the extension has no context") }
        return await c.route(call)
    }

    /// The Meta AI app returns to the app's own scheme after registration and permission
    /// requests; only those URLs (with a metaWearablesAction query item) go to the SDK.
    func openURL(_ url: URL) -> Bool {
        guard #available(iOS 17.2, *), GlassesController.isWearablesURL(url), let c = controller() else { return false }
        c.handleURL(url)
        return true
    }

    @available(iOS 17.2, *)
    private func controller() -> GlassesController? {
        if let c = controllerBox as? GlassesController { return c }
        guard let context else { return nil }
        let c = GlassesController(context: context)
        controllerBox = c
        return c
    }
}

@available(iOS 17.2, *)
@MainActor
final class GlassesController {
    static let sdkVersion = "1.1.0"
    #if DEBUG
    static let mockAllowed = true
    #else
    static let mockAllowed = false
    #endif
    /// media_read returns the file in chunks of this many bytes (65536 characters of base64).
    static let mediaChunk = 49152

    private let context: NativeExtContext
    private let mediaDirectory: URL
    private var configured = false
    private var core = GlassesCore.Session()
    private let pipe = GlassesFramePipe()
    private let appTokens = ListenerTokenBag()

    private var server: GlassesServer?
    private var session: DeviceSession?
    private var camera: Camera?
    private var stream: MWDATCamera.Stream?
    private var streamTokens: ListenerTokenBag?
    private var streamSeenActive = false
    private var startError: NativeExtResult?
    private var quality = "medium"
    private var fps = 24

    private var photoBusy = false
    private var photoData: PhotoData?
    private var photoFailed = false
    private var clipTimer: Task<Void, Never>?

    private var mock = false
    private var mockGlasses: (any MockGlasses)?

    private var sessionTokens: ListenerTokenBag?
    private var sessionStarting = false
    private var sessionError: NativeExtResult?
    private var lastSessionState = "idle"
    private var registrationRequest: RegistrationRequest?
    private var devices: GlassesDevices?
    private lazy var sensors = GlassesSensors { [weak self] e, d in self?.context.emit(e, d) }
    private lazy var display = GlassesDisplay { [weak self] e, d in self?.context.emit(e, d) }
    private var audio = false
    private var audioRate = 16000
    private var audioChannels = 1
    /// The standalone still photo's own Camera and Photo (never the stream's: Meta, "Camera Capture").
    private var stillCamera: Camera?
    private var cameraPhoto: Photo?
    private var stillBusy = false
    private var stillGen = 0
    private var stillState: PhotoState?
    private var stillData: PhotoCaptureData?
    private var stillError: PhotoError?
    private var savedAudio: (AVAudioSession.Category, AVAudioSession.Mode, AVAudioSession.CategoryOptions)?
    private var audioRouteObserver: NSObjectProtocol?

    init(context: NativeExtContext) {
        self.context = context
        mediaDirectory = context.dataDirectory.appendingPathComponent("media", isDirectory: true)
        pipe.onClipProblem = { [weak self] reason in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.clipProblem(reason) } }
        }
    }

    nonisolated static func isWearablesURL(_ url: URL) -> Bool {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.contains { $0.name == "metaWearablesAction" } == true
    }

    /// The Meta AI app's return URL. A registration Meta AI started (the person chose this app in
    /// Meta AI) arrives as a RegistrationRequest: the page decides with registration_continue or
    /// registration_cancel after the registration_request event.
    func handleURL(_ url: URL) {
        guard configure() else { return }
        Task { @MainActor in
            do {
                _ = try await Wearables.shared.handleUrl(url) { [weak self] request in
                    DispatchQueue.main.async { MainActor.assumeIsolated { self?.registrationRequested(request) } }
                }
            } catch {
                context.log("handleUrl failed: \(error.localizedDescription)")
            }
        }
    }

    private func registrationRequested(_ request: RegistrationRequest) {
        registrationRequest = request
        context.emit("registration_request", ["flowId": request.flowID, "protocolVersion": request.protocolVersion])
    }

    func route(_ call: NativeExtCall) async -> NativeExtResult {
        guard configure() else { return .error(code: "init_failed", message: "the glasses SDK could not start") }
        switch call.route {
        case "status": return .ok(status())
        case "register": return await register(call, on: true)
        case "unregister": return await register(call, on: false)
        case "permission": return await permission(call)
        case "start": return await start(call)
        case "stop":
            teardown("stopped")
            return .ok(status())
        case "photo": return await photo(call)
        case "record_start": return recordStart(call)
        case "record_stop":
            guard core.clip != nil else { return .error(code: "not_recording", message: "no clip is recording") }
            return await stopClip(core.clipGen, reason: "stopped")
        case "frame": return frame()
        case "media": return .ok(["items": mediaList()])
        case "media_read": return mediaRead(call)
        case "media_delete": return mediaDelete(call)
        case "mock": return await mockRoute(call)
        case "mock_event": return mockEvent(call)
        case "devices": return .ok(["devices": devices?.list() ?? []])
        case "session_start": return await sessionStart(call)
        case "session_stop":
            endSession("stopped")
            return .ok(status())
        case "pause", "resume": return pauseResume(call.route == "pause")
        case "registration_continue", "registration_cancel": return await registrationAnswer(call)
        case "open_update": return await openUpdate(call)
        case "audio_route": return audioRoute(call)
        case "speech_start", "motion_start", "inputs_start", "display_start": return await capabilityStart(call)
        case "speech_stop": return sensors.speechStop(session)
        case "motion_stop": return sensors.motionStop(session)
        case "inputs_stop": return sensors.inputsStop(session)
        case "display_stop": return display.stop(session)
        case "display_send": return await display.send(call.params["content"] as? String ?? "")
        case "display_video": return await display.video(call.params["url"] as? String ?? "")
        case "display_video_stop": return await display.videoStop()
        case "display_clear": return await display.clear()
        case "voice_start": return voiceStart(call)
        case "voice_stop": return sensors.voiceStop()
        case "voice_respond":
            return await sensors.voiceRespond(id: call.params["id"] as? String ?? "", success: call.params["success"] as? Bool ?? true,
                                              output: call.params["output"] as? String)
        default: return .error(code: "bad_request", message: "unknown route")
        }
    }

    // MARK: - SDK and state

    /// Wearables.configure on first use; never at launch.
    private func configure() -> Bool {
        if configured { return true }
        do {
            try Wearables.configure()
        } catch WearablesError.alreadyConfigured {
            // Configured elsewhere in the app: use it.
        } catch {
            context.log("Wearables.configure failed: \(error.localizedDescription)")
            return false
        }
        configured = true
        Wearables.shared.addRegistrationStateListener { [weak self] s in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.context.emit("registration", ["registration": Self.name(s)]) }
            }
        }.store(in: appTokens)
        let d = GlassesDevices { [weak self] e, o in self?.context.emit(e, o) }
        devices = d
        d.start()
        return true
    }

    private static func name(_ s: RegistrationState) -> String {
        switch s {
        case .unavailable: return "unavailable"
        case .available: return "available"
        case .registering: return "registering"
        case .registered: return "registered"
        @unknown default: return "unavailable"
        }
    }

    /// The app's Bluetooth authorization. iOS asks the person itself the first time the SDK uses
    /// Bluetooth (NSBluetoothAlwaysUsageDescription); there is no separate request call.
    static func bluetooth() -> String {
        switch CBManager.authorization {
        case .allowedAlways: return "granted"
        case .notDetermined: return "unknown"
        default: return "denied"
        }
    }

    private func status() -> [String: Any] {
        let size = GlassesCore.frameSize(quality)
        let seen = pipe.stats()
        var o: [String: Any] = [
            "sdkVersion": Self.sdkVersion,
            "bluetooth": Self.bluetooth(),
            "registration": Self.name(Wearables.shared.registrationState),
            "devices": Wearables.shared.devices.count,
            "state": core.state, "quality": quality, "fps": fps,
            "width": seen.width > 0 ? seen.width : size.0, "height": seen.height > 0 ? seen.height : size.1,
            "frames": seen.frames, "staleDrops": core.staleDrops,
            "clip": core.clip ?? NSNull(), "photoBusy": photoBusy, "mock": mock,
            "session": session.map { GlassesJSON.session($0.state) } ?? "none",
            "device": session?.deviceId ?? NSNull(), "audio": audio, "audioRate": audioRate, "audioChannels": audioChannels,
            "speech": sensors.speech != nil, "motion": sensors.motion != nil, "inputs": sensors.inputs != nil,
            "display": display.active, "voice": sensors.voiceState,
        ]
        if let server {
            o["live"] = server.base + "/live.mjpg"
            o["frame"] = server.base + "/frame.jpg"
            o["mediaBase"] = server.base + "/media/"
        } else {
            o["live"] = NSNull()
            o["frame"] = NSNull()
            o["mediaBase"] = NSNull()
        }
        return o
    }

    private func emitState(_ reason: String) {
        var o = status()
        o["reason"] = reason
        context.emit("state", o)
    }

    private func register(_ call: NativeExtCall, on: Bool) async -> NativeExtResult {
        let s = Wearables.shared.registrationState
        do {
            if on && s != .registered { try await Wearables.shared.startRegistration() }
            if !on && s == .registered { try await Wearables.shared.startUnregistration() }
        } catch RegistrationError.metaAINotInstalled, UnregistrationError.metaAINotInstalled {
            return .error(code: "meta_ai_missing", message: "the Meta AI app is not installed")
        } catch RegistrationError.unauthorized {
            // DAT 1.1.0: thrown without opening Meta AI when the app may not register.
            return .error(code: "registration_unauthorized", message: "this app is not authorized to register with Meta Wearables")
        } catch RegistrationError.alreadyRegistered, UnregistrationError.alreadyUnregistered {
            // Already in the asked state.
        } catch {
            return .error(code: "registration_failed", message: error.localizedDescription)
        }
        // The outcome arrives as the registration event once the Meta AI app returns.
        return .ok(["registration": Self.name(Wearables.shared.registrationState)])
    }

    /// Bluetooth, and the glasses' camera and microphone permissions (DAT's Permission.camera and
    /// .microphone, granted in the Meta AI app). `kind` picks the one a prompt asks for; both are
    /// reported.
    private func permission(_ call: NativeExtCall) async -> NativeExtResult {
        let prompt = call.params["prompt"] as? Bool ?? false
        let asked: Permission = call.params["kind"] as? String == "microphone" ? .microphone : .camera
        guard Wearables.shared.registrationState == .registered else {
            // The glasses permissions belong to a registered app; Meta AI cannot answer them before.
            return .ok(["bluetooth": Self.bluetooth(), "camera": "unknown", "microphone": "unknown"])
        }
        do {
            var status = try await Wearables.shared.checkPermissionStatus(asked)
            if status != .granted && prompt {
                guard call.isActive else { return .error(code: "timeout", message: "the permission check took too long") }
                // Switches to the Meta AI app; returns once the person answered there.
                status = try await Wearables.shared.requestPermission(asked)
            }
            let other: Permission = asked == .camera ? .microphone : .camera
            let otherStatus = (try? await Wearables.shared.checkPermissionStatus(other)).map { $0 == .granted ? "granted" : "denied" } ?? "unknown"
            let word = status == .granted ? "granted" : "denied"
            return .ok(["bluetooth": Self.bluetooth(), "camera": asked == .camera ? word : otherStatus,
                        "microphone": asked == .microphone ? word : otherStatus])
        } catch {
            // DAT declares throws(PermissionError), so this is the only error kind.
            switch error {
            case .metaAINotInstalled: return .error(code: "meta_ai_missing", message: error.description)
            case .noDevice, .noDeviceWithConnection: return .error(code: "no_device", message: error.description)
            case .requestInProgress: return .error(code: "busy", message: error.description)
            case .requestTimeout: return .error(code: "timeout", message: error.description)
            default: return .error(code: "permission_failed", message: error.description)
            }
        }
    }

    // MARK: - Stream

    private func start(_ call: NativeExtCall) async -> NativeExtResult {
        guard Wearables.shared.registrationState == .registered else {
            return .error(code: "registration_required", message: "connect the app to Meta AI first (register)")
        }
        guard core.state == "idle" else { return .error(code: "busy", message: "the stream is already \(core.state)") }
        guard !stillBusy else { return .error(code: "busy", message: "a still photo is being taken") }
        let wantQuality = call.params["quality"] as? String ?? "medium"
        let wantFps = GlassesCore.frameRate(call.params["fps"] as? Int ?? 24)
        let wantAudio = call.params["audio"] as? Bool ?? false
        let wantRate = Int(call.params["audio_rate"] as? String ?? "16000") ?? 16000
        let wantChannels = min(2, max(1, call.params["audio_channels"] as? Int ?? 1))
        if server == nil {
            do {
                try FileManager.default.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
                server = try GlassesServer(mediaDirectory: mediaDirectory)
            } catch {
                return .error(code: "server_failed", message: "the local stream server could not start")
            }
        }
        let g = core.begin()
        quality = wantQuality
        fps = wantFps
        audio = wantAudio
        audioRate = wantRate
        audioChannels = wantChannels
        startError = nil
        streamSeenActive = false
        emitState("starting")
        if let e = await openSession(call, device: call.params["device"] as? String) {
            if core.gen == g { teardown("start_failed") }
            return e
        }
        if let e = startFailure(g) { return e }
        guard let s = session, call.isActive else {
            if core.gen == g { teardown("start_failed") }
            return .error(code: "timeout", message: "the glasses did not connect in time")
        }
        let bag = ListenerTokenBag()
        streamTokens = bag
        let resolution: StreamingResolution = wantQuality == "high" ? .high : wantQuality == "low" ? .low : .medium
        // Camera audio (DAT 1.0.0, experimental): PCM at 16, 44.1 or 48 kHz beside the video, into clips.
        let rate: AudioSampleRate = wantRate == 48000 ? .rate48000 : wantRate == 44100 ? .rate44100 : .rate16000
        let audioCodec: MWDATCamera.AudioCodec? = wantAudio ? .pcm(sampleRate: rate, numberOfChannels: UInt32(wantChannels)) : nil
        let cam: Camera?
        do {
            // Decoded frames (.raw): the preview needs no decoder of its own.
            cam = try s.addCamera(config: StreamConfiguration(videoCodec: .raw, audioCodec: audioCodec, resolution: resolution, frameRate: UInt(wantFps)))
        } catch {
            teardown("start_failed")
            return Self.sessionError(error)
        }
        guard let cam else {
            teardown("start_failed")
            return .error(code: "stream_failed", message: "the glasses offered no camera")
        }
        camera = cam
        let st = cam.stream
        stream = st
        guard let server else { return .error(code: "server_failed", message: "the local stream server stopped") }
        pipe.begin(g, server: server)
        st.statePublisher.listen { [weak self] x in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.onStreamState(g, x) } }
        }.store(in: bag)
        st.errorPublisher.listen { [weak self] e in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.onStreamError(g, e) } }
        }.store(in: bag)
        st.photoDataPublisher.listen { [weak self] p in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.onPhoto(g, p) } }
        }.store(in: bag)
        st.videoFramePublisher.listen { [pipe] f in pipe.push(f, gen: g) }.store(in: bag)
        if wantAudio {
            st.audioFramePublisher.listen { [pipe] a in pipe.pushAudio(a, gen: g) }.store(in: bag)
        }
        st.start()
        let streaming = await waitUntil(call) { [weak self] in self?.core.state == "streaming" || self?.core.gen != g }
        if let e = startFailure(g) { return e }
        guard streaming, call.isActive, core.gen == g else {
            if core.gen == g { teardown("start_failed") }
            return .error(code: "timeout", message: "the glasses did not start streaming in time")
        }
        return .ok(status())
    }

    // MARK: - Device session

    /// The one DeviceSession every capability shares (camera, speech, motion, inputs, display). nil
    /// when it is started; otherwise the error to answer. `device` picks a device by id
    /// (SpecificDeviceSelector); without it DAT's AutoDeviceSelector picks one.
    private func openSession(_ call: NativeExtCall, device: String?) async -> NativeExtResult? {
        guard Wearables.shared.registrationState == .registered else {
            return .error(code: "registration_required", message: "connect the app to Meta AI first (register)")
        }
        if let s = session {
            if let device, device != s.deviceId {
                return .error(code: "busy", message: "a session with another device is open (session_stop first)")
            }
            if s.state == .started || s.state == .paused { return nil }
            let ok = await waitUntil(call) { [weak self] in self?.session !== s || s.state == .started }
            if session === s, ok { return nil }
            return sessionError ?? .error(code: "no_device", message: "the glasses session stopped before it started")
        }
        let selector: any DeviceSelector
        if let device {
            selector = SpecificDeviceSelector(device: device)
        } else {
            // Meta, DAT 1.1.0 Display access guide (iOS): AutoDeviceSelector updates from devicesStream(); wait for
            // it to name an active device before createSession, otherwise createSession throws noEligibleDevice.
            // Bounded to 3 s and only while DAT lists a device, so no glasses still answers no_device at once.
            let auto = AutoDeviceSelector(wearables: Wearables.shared)
            let until = DispatchTime.now() + .seconds(3)
            while auto.activeDevice == nil, !Wearables.shared.devices.isEmpty, call.isActive, DispatchTime.now() < until {
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
            // Another call may have opened the session while this one waited.
            if session != nil { return await openSession(call, device: device) }
            selector = auto
        }
        let s: DeviceSession
        do {
            s = try Wearables.shared.createSession(deviceSelector: selector)
        } catch {
            return Self.sessionError(error)
        }
        session = s
        sessionError = nil
        let bag = ListenerTokenBag()
        sessionTokens = bag
        s.errorPublisher.listen { [weak self, weak s] e in
            DispatchQueue.main.async { MainActor.assumeIsolated { if let s { self?.onSessionError(s, e) } } }
        }.store(in: bag)
        s.statePublisher.listen { [weak self, weak s] st in
            DispatchQueue.main.async { MainActor.assumeIsolated { if let s { self?.onSessionState(s, st) } } }
        }.store(in: bag)
        do {
            try s.start()
        } catch {
            endSession("start_failed")
            return Self.sessionError(error)
        }
        // A capability can be added only once the session reports started (Meta, "Camera Streaming").
        let started = await waitUntil(call) { [weak self] in self?.session !== s || s.state == .started }
        guard session === s else {
            return sessionError ?? .error(code: "no_device", message: "the glasses session stopped before it started")
        }
        guard started, call.isActive else {
            endSession("start_failed")
            return .error(code: "timeout", message: "the glasses did not connect in time")
        }
        return nil
    }

    private func sessionStart(_ call: NativeExtCall) async -> NativeExtResult {
        if let e = await openSession(call, device: call.params["device"] as? String) { return e }
        return .ok(status())
    }

    private func onSessionState(_ s: DeviceSession, _ st: DeviceSessionState) {
        guard session === s else { return }
        lastSessionState = GlassesJSON.session(st)
        context.emit("session", ["state": lastSessionState, "device": s.deviceId])
        guard st == .stopped else { return }
        // The SDK ended the session: cleared first, so the camera's end releases nothing again.
        session = nil
        let g = core.gen
        if core.state != "idle" {
            if streamSeenActive {
                ended(g, "session_stopped")
            } else if core.state == "starting" {
                failStartOrEnd(g, sessionError ?? .error(code: "no_device", message: "the glasses session stopped before streaming"), "start_failed")
            }
        }
        sensors.sessionEnded()
        display.sessionEnded()
        sessionTokens?.clear()
        sessionTokens = nil
    }

    private func onSessionError(_ s: DeviceSession, _ e: DeviceSessionError) {
        guard session === s else { return }
        let code = GlassesErrors.sessionCode(e)
        context.emit("session", ["state": GlassesJSON.session(s.state), "device": s.deviceId, "error": code, "message": e.description])
        // DAT 1.0.0: an app built with an SDK outside the glasses' supported range keeps working;
        // the warning only suggests an update.
        if e == .dwaOutOfStuRange { return }
        let r = Self.sessionError(e)
        sessionError = r
        if core.state != "idle" { failStartOrEnd(core.gen, r, "session_error") }
    }

    /// Ends the session and every capability on it.
    private func endSession(_ reason: String) {
        // Cleared first: the camera's teardown below then finds no session to release again.
        let s = session
        session = nil
        sessionTokens?.clear()
        sessionTokens = nil
        teardown(reason)
        sensors.sessionEnded()
        display.sessionEnded()
        guard let s else { return }
        s.stop()
        lastSessionState = "stopped"
        context.emit("session", ["state": "stopped", "device": s.deviceId, "reason": reason])
    }

    /// The camera, speech, motion, inputs and display are all off: the session has no work left.
    private func releaseSessionIfIdle() {
        guard session != nil, camera == nil, stillCamera == nil, !stillBusy, !sensors.active, !display.active else { return }
        endSession("idle")
    }

    private func pauseResume(_ pause: Bool) -> NativeExtResult {
        guard let s = session else { return .error(code: "not_active", message: "no glasses session is open") }
        // DAT 1.1.0: pause() outside started and resume() outside paused do nothing.
        if pause { s.pause() } else { s.resume() }
        return .ok(["session": GlassesJSON.session(s.state), "device": s.deviceId])
    }

    private func capabilityStart(_ call: NativeExtCall) async -> NativeExtResult {
        if let e = await openSession(call, device: call.params["device"] as? String) { return e }
        guard let s = session else { return .error(code: "no_device", message: "the glasses session ended") }
        let r: NativeExtResult
        switch call.route {
        case "speech_start": r = sensors.speechStart(s)
        case "motion_start": r = sensors.motionStart(s, rate: Int(call.params["rate"] as? String ?? "10") ?? 10)
        case "inputs_start": r = sensors.inputsStart(s, sources: call.params["sources"] as? String, consumeBack: call.params["consume_back"] as? Bool ?? true)
        default:
            // Answers when the display is started (or failed); a failed start leaves no display behind.
            r = await display.start(s)
            if case .error = r, display.active, !display.started { _ = display.stop(session) }
        }
        if case .error = r { releaseSessionIfIdle() }
        return r
    }

    private func voiceStart(_ call: NativeExtCall) -> NativeExtResult {
        guard Wearables.shared.registrationState == .registered else {
            return .error(code: "registration_required", message: "connect the app to Meta AI first (register)")
        }
        guard let device = call.params["device"] as? String ?? session?.deviceId ?? Wearables.shared.devices.first else {
            return .error(code: "no_device", message: "no glasses are connected")
        }
        return sensors.voiceStart(device: device)
    }

    // MARK: - Registration requests and update prompts

    private func registrationAnswer(_ call: NativeExtCall) async -> NativeExtResult {
        guard let r = registrationRequest else { return .error(code: "not_found", message: "no registration request from Meta AI is open") }
        registrationRequest = nil
        do {
            if call.route == "registration_continue" {
                try await r.continueRegistration()
            } else {
                // DAT 1.1.0: true returns to Meta AI (the person declined); false declines here
                // (a setup failure the app shows itself).
                try await r.cancelRegistration(returnToMetaAI: call.params["return_to_meta_ai"] as? Bool ?? true)
            }
        } catch {
            switch error {
            case .metaAINotInstalled: return .error(code: "meta_ai_missing", message: error.description)
            case .alreadyHandled, .invalidRequest: return .error(code: "not_found", message: error.description)
            case .networkUnavailable: return .error(code: "unavailable", message: error.description)
            default: return .error(code: "registration_failed", message: error.description)
            }
        }
        return .ok(["flowId": r.flowID, "answer": call.route == "registration_continue" ? "continue" : "cancel"])
    }

    private func openUpdate(_ call: NativeExtCall) async -> NativeExtResult {
        let target = call.params["target"] as? String ?? "firmware"
        do {
            if target == "glasses_app" {
                try await Wearables.shared.openDATGlassesAppUpdate()
            } else {
                try await Wearables.shared.openFirmwareUpdate()
            }
        } catch {
            switch error {
            case .metaAINotInstalled: return .error(code: "meta_ai_missing", message: error.description)
            case .notRegistered: return .error(code: "registration_required", message: error.description)
            @unknown default: return .error(code: "unavailable", message: error.description)
            }
        }
        return .ok(["opened": target])
    }

    // MARK: - Bluetooth audio

    /// Routes the app's audio to the glasses over Bluetooth: `call` uses Hands-Free (the glasses'
    /// microphones and speakers, for getUserMedia and calls), `media` uses A2DP playback (the
    /// glasses' speakers, phone microphone), `default` puts back the category the app had before.
    /// DAT itself carries no phone audio; Meta's glasses are standard Bluetooth audio devices.
    private func audioRoute(_ call: NativeExtCall) -> NativeExtResult {
        let mode = call.params["mode"] as? String ?? "call"
        let a = AVAudioSession.sharedInstance()
        do {
            switch mode {
            case "default":
                if let (c, m, o) = savedAudio { try a.setCategory(c, mode: m, options: o) }
                savedAudio = nil
                try? a.setPreferredInput(nil)
                if let o = audioRouteObserver { NotificationCenter.default.removeObserver(o) }
                audioRouteObserver = nil
            default:
                if savedAudio == nil { savedAudio = (a.category, a.mode, a.categoryOptions) }
                if mode == "media" {
                    try a.setCategory(.playback, mode: .default, options: [])
                } else {
                    try a.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetoothHFP, .allowBluetoothA2DP])
                }
                try a.setActive(true)
                if mode == "call", let hfp = a.availableInputs?.first(where: { $0.portType == .bluetoothHFP }) {
                    try a.setPreferredInput(hfp)
                }
                if audioRouteObserver == nil {
                    audioRouteObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] _ in
                        MainActor.assumeIsolated { self?.context.emit("audio", Self.audioState()) }
                    }
                }
            }
        } catch {
            return .error(code: "audio_failed", message: error.localizedDescription)
        }
        var o = Self.audioState()
        o["mode"] = mode
        return .ok(o)
    }

    static func audioState() -> [String: Any] {
        let r = AVAudioSession.sharedInstance().currentRoute
        let port: (AVAudioSessionPortDescription) -> [String: Any] = { p in
            ["name": p.portName, "type": p.portType.rawValue,
             "bluetooth": [.bluetoothHFP, .bluetoothA2DP, .bluetoothLE].contains(p.portType)]
        }
        return ["inputs": r.inputs.map(port), "outputs": r.outputs.map(port),
                "glasses": r.outputs.contains { [.bluetoothHFP, .bluetoothA2DP, .bluetoothLE].contains($0.portType) }]
    }

    private func startFailure(_ g: Int64) -> NativeExtResult? {
        guard core.gen != g || core.state == "idle" else { return nil }
        return startError ?? .error(code: "no_device", message: "the stream ended before it started")
    }

    /// Polls cond every 50 ms until it holds, the call ends or its deadline is 100 ms away.
    private func waitUntil(_ call: NativeExtCall, _ cond: () -> Bool) async -> Bool {
        while true {
            if cond() { return true }
            if !call.isActive || DispatchTime.now() + .milliseconds(100) >= call.deadline { return false }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    private func onStreamState(_ g: Int64, _ st: StreamState) {
        switch st {
        case .waitingForDevice, .starting, .streaming, .paused:
            if g == core.gen { streamSeenActive = true }
        default:
            break
        }
        switch st {
        case .streaming:
            if core.streaming(g) { emitState("streaming") }
        case .paused:
            if core.paused(g) { emitState("paused") }
        case .stopped:
            if streamSeenActive && g == core.gen { ended(g, "stream_stopped") }
        default:
            break
        }
    }

    private func onStreamError(_ g: Int64, _ e: StreamError) {
        guard g == core.gen else { return }
        if e == .photoCaptureFailed {
            photoFailed = true
            return
        }
        failStartOrEnd(g, Self.streamError(e), "stream_error")
    }

    private func failStartOrEnd(_ g: Int64, _ error: NativeExtResult, _ reason: String) {
        guard g == core.gen else { return }
        if core.state == "starting" {
            startError = error
            teardown("start_failed")
        } else {
            ended(g, reason)
        }
    }

    /// The SDK ended generation g by itself.
    private func ended(_ g: Int64, _ reason: String) {
        guard let clip = core.ended(g) else { return }
        stopSDK()
        if !clip.isEmpty { Task { _ = await self.finishClip(clip, reason: reason) } }
        emitState(reason)
    }

    /// An explicit stop (route, failed start, mock off): later SDK callbacks are stale.
    private func teardown(_ reason: String) {
        let wasActive = core.state != "idle"
        let clip = core.stop()
        stopSDK()
        if !clip.isEmpty { Task { _ = await self.finishClip(clip, reason: reason) } }
        if wasActive { emitState(reason) }
    }

    /// Ends the camera (its stream and photo). The session stays while another capability uses it.
    private func stopSDK() {
        pipe.end()
        streamTokens?.clear()
        streamTokens = nil
        // camera.stop cascades to its stream.
        camera?.stop()
        camera = nil
        stream = nil
        server?.endLive()
        releaseSessionIfIdle()
    }

    // MARK: - Photo and clips

    private func onPhoto(_ g: Int64, _ p: PhotoData) {
        guard g == core.gen, photoBusy else { return }
        photoData = p
    }

    private func photo(_ call: NativeExtCall) async -> NativeExtResult {
        if call.params["resolution"] != nil || call.params["quality"] != nil { return await stillPhoto(call) }
        guard core.state == "streaming", let stream else { return .error(code: "not_streaming", message: "start the stream first") }
        guard !photoBusy else { return .error(code: "busy", message: "a photo is already being taken") }
        photoBusy = true
        photoData = nil
        photoFailed = false
        defer {
            photoBusy = false
            photoData = nil
        }
        // In-stream capture: false means no capture started, so no photo will arrive.
        guard stream.capturePhoto(format: .jpeg) else { return .error(code: "capture_failed", message: "the glasses did not start the capture") }
        let gen = core.gen
        let got = await waitUntil(call) { [weak self] in self?.photoData != nil || self?.photoFailed == true || self?.core.gen != gen }
        guard got, let p = photoData else {
            return .error(code: photoFailed ? "capture_failed" : "timeout", message: "no photo arrived from the glasses")
        }
        let id = Self.newId("p")
        let url = mediaDirectory.appendingPathComponent("\(id).jpg")
        let image = UIImage(data: p.data)
        let jpeg: Data? = p.format == .jpeg ? p.data : image?.jpegData(compressionQuality: GlassesMedia.photoQuality)
        guard let jpeg else { return .error(code: "capture_failed", message: "the photo could not be encoded") }
        do {
            try FileManager.default.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
            try GlassesMedia.write(jpeg, to: url)
        } catch {
            return .error(code: "capture_failed", message: "the photo could not be saved")
        }
        var m = mediaJSON(id, "jpg", url)
        m["width"] = Int(image?.size.width ?? 0) * Int(image?.scale ?? 1)
        m["height"] = Int(image?.size.height ?? 0) * Int(image?.scale ?? 1)
        context.emit("media", m)
        return .ok(m)
    }

    /// A high-quality still through DAT's standalone photo capability (Camera.photo, experimental in
    /// DAT 1.1.0): the glasses take the picture at `resolution` and `quality` and transfer it,
    /// reporting `photo_progress`. Meta's "Camera Capture" guide (meta-wearables-dat-ios, 1.1.0): the
    /// stream and the standalone photo compete for the camera, so a still needs the stream stopped;
    /// listeners go on before `start()`, and a capture before `.started` publishes `notReady`. Each
    /// still adds its own Camera to the shared session and stops it after delivery.
    private func stillPhoto(_ call: NativeExtCall) async -> NativeExtResult {
        guard core.state == "idle" else {
            return .error(code: "busy", message: "stop the stream first: the stream and still photos share the glasses camera")
        }
        guard !photoBusy, !stillBusy else { return .error(code: "busy", message: "a photo is already being taken") }
        let resWord = call.params["resolution"] as? String ?? "medium"
        let qWord = call.params["quality"] as? String ?? "medium"
        let res: PhotoResolution = ["small": .small, "large": .large, "full": .full][resWord] ?? .medium
        let q: PhotoQuality = ["low": .low, "high": .high][qWord] ?? .medium
        stillBusy = true
        photoBusy = true
        stillGen += 1
        let sg = stillGen
        stillState = nil
        stillData = nil
        stillError = nil
        let bag = ListenerTokenBag()
        defer {
            bag.clear()
            cameraPhoto?.stop()
            // camera.stop cascades to its photo.
            stillCamera?.stop()
            cameraPhoto = nil
            stillCamera = nil
            stillState = nil
            stillData = nil
            stillBusy = false
            photoBusy = false
            releaseSessionIfIdle()
        }
        if let e = await openSession(call, device: nil) { return e }
        guard let s = session, call.isActive else { return .error(code: "timeout", message: "the glasses did not connect in time") }
        if mock, let g = mockGlasses {
            // The mock kit's captured photo is the next image (Meta, "Camera Capture"): set it for each still.
            let photo = context.dataDirectory.appendingPathComponent("mock", isDirectory: true).appendingPathComponent("photo.jpg")
            if FileManager.default.fileExists(atPath: photo.path) { g.services.cameraCapture.setCapturedPhoto(fileURL: photo) }
        }
        let cam: Camera?
        do {
            // The stream settings do not apply to a still.
            cam = try s.addCamera(config: StreamConfiguration(videoCodec: .raw, resolution: .medium, frameRate: 24))
        } catch {
            return Self.sessionError(error)
        }
        guard let cam else { return .error(code: "capture_failed", message: "the glasses offered no camera") }
        stillCamera = cam
        let p = cam.photo
        cameraPhoto = p
        p.statePublisher.listen { [weak self] st in
            DispatchQueue.main.async { MainActor.assumeIsolated { if self?.stillGen == sg { self?.stillState = st } } }
        }.store(in: bag)
        p.photoDataPublisher.listen { [weak self] d in
            DispatchQueue.main.async { MainActor.assumeIsolated { if self?.stillGen == sg { self?.stillData = d } } }
        }.store(in: bag)
        p.errorPublisher.listen { [weak self] e in
            DispatchQueue.main.async { MainActor.assumeIsolated { if self?.stillGen == sg { self?.stillError = e } } }
        }.store(in: bag)
        p.transferProgressPublisher.listen { [weak self] t in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard self?.stillGen == sg else { return }
                    self?.context.emit("photo_progress", ["received": t.bytesReceived, "total": t.totalBytes, "fraction": t.fraction])
                }
            }
        }.store(in: bag)
        p.start()
        let ready = await waitUntil(call) { [weak self] in
            self?.stillState == .started || self?.stillError != nil || self?.session !== s
        }
        if let e = stillError { return Self.photoError(e) }
        guard session === s else {
            return sessionError ?? .error(code: "no_device", message: "the glasses session ended before the photo arrived")
        }
        guard ready else { return .error(code: "timeout", message: "the glasses camera was not ready in time") }
        p.capturePhoto(resolution: res, quality: q)
        let got = await waitUntil(call) { [weak self] in
            self?.stillData != nil || self?.stillError != nil || self?.session !== s || self?.stillState == .stopped
        }
        if let e = stillError { return Self.photoError(e) }
        guard got, let d = stillData else {
            if session !== s { return sessionError ?? .error(code: "no_device", message: "the glasses session ended before the photo arrived") }
            if stillState == .stopped { return .error(code: "capture_failed", message: "the photo stopped before it arrived") }
            return .error(code: "timeout", message: "no photo arrived from the glasses")
        }
        let image = UIImage(data: d.imageData)
        let isJPEG = d.imageData.count > 2 && d.imageData[0] == 0xFF && d.imageData[1] == 0xD8
        guard let jpeg = isJPEG ? d.imageData : image?.jpegData(compressionQuality: GlassesMedia.photoQuality) else {
            return .error(code: "capture_failed", message: "the photo could not be encoded")
        }
        let id = Self.newId("p")
        let url = mediaDirectory.appendingPathComponent("\(id).jpg")
        do {
            try FileManager.default.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
            try GlassesMedia.write(jpeg, to: url)
        } catch {
            return .error(code: "capture_failed", message: "the photo could not be saved")
        }
        var m = mediaJSON(id, "jpg", url)
        m["width"] = Int(image?.size.width ?? 0) * Int(image?.scale ?? 1)
        m["height"] = Int(image?.size.height ?? 0) * Int(image?.scale ?? 1)
        m["resolution"] = resWord
        m["quality"] = qWord
        context.emit("media", m)
        return .ok(m)
    }

    nonisolated private static func photoError(_ e: PhotoError) -> NativeExtResult {
        switch e {
        case .permissionDenied: return .error(code: "permission_denied", message: e.description)
        case .deviceDisconnected: return .error(code: "no_device", message: e.description)
        case .busy: return .error(code: "busy", message: e.description)
        case .deviceHealthCritical: return .error(code: "device_limit", message: e.description)
        case .serviceUnavailable, .notReady: return .error(code: "unavailable", message: e.description)
        default: return .error(code: "capture_failed", message: e.description)
        }
    }

    private func recordStart(_ call: NativeExtCall) -> NativeExtResult {
        let seconds = call.params["seconds"] as? Int ?? 10
        let id = Self.newId("c")
        let cg = core.clipBegin(id)
        guard cg >= 0, let server else {
            return core.clip != nil ? .error(code: "busy", message: "a clip is already recording")
                : .error(code: "not_streaming", message: "start the stream first")
        }
        try? FileManager.default.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
        // Written as .mp4.part and moved when finished, so the server never serves half a file.
        let part = mediaDirectory.appendingPathComponent("\(id).mp4.part")
        try? FileManager.default.removeItem(at: part)
        // With the stream's audio on, the clip carries it as AAC beside the H.264 video.
        pipe.setClip(GlassesClipWriter(url: part, fps: fps, audioRate: audio ? audioRate : nil, audioChannels: audioChannels))
        clipTimer?.cancel()
        clipTimer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds) * 1_000_000_000)
            guard !Task.isCancelled else { return }
            _ = await self?.stopClip(cg, reason: "limit")
        }
        return .ok(["id": id, "kind": "mp4", "seconds": seconds, "audio": audio, "url": server.base + "/media/\(id).mp4"])
    }

    /// The frame queue saw the clip end on its own (size change, writer failure).
    private func clipProblem(_ reason: String) {
        guard core.clip != nil else { return }
        let cg = core.clipGen
        Task { _ = await self.stopClip(cg, reason: reason) }
    }

    private func stopClip(_ cg: Int64, reason: String) async -> NativeExtResult {
        guard let id = core.clipEnd(cg) else { return .error(code: "not_recording", message: "no clip is recording") }
        return await finishClip(id, reason: reason)
    }

    private func finishClip(_ id: String, reason: String) async -> NativeExtResult {
        clipTimer?.cancel()
        clipTimer = nil
        let writer = pipe.takeClip()
        let ok: Bool
        if let writer {
            ok = await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in writer.finish { c.resume(returning: $0) } }
        } else {
            ok = false
        }
        let part = mediaDirectory.appendingPathComponent("\(id).mp4.part")
        let url = mediaDirectory.appendingPathComponent("\(id).mp4")
        if ok, (try? FileManager.default.moveItem(at: part, to: url)) != nil {
            var m = mediaJSON(id, "mp4", url)
            m["durationMs"] = writer?.durationMs ?? 0
            m["reason"] = reason
            context.emit("media", m)
            return .ok(m)
        }
        try? FileManager.default.removeItem(at: part)
        context.emit("media", ["id": id, "kind": "mp4", "failed": true, "reason": reason])
        return .error(code: "record_failed", message: "the clip has no frames or could not be written")
    }

    // MARK: - Frames and media files

    /// The newest frame as base64 JPEG, for pages that cannot load the loopback URL. The page
    /// compares seq with the last one it drew; polling faster than the stream's rate returns the
    /// same frame again.
    private func frame() -> NativeExtResult {
        let (seq, jpeg) = server?.latest() ?? (0, nil)
        let seen = pipe.stats()
        return .ok(["seq": seq, "width": seen.width, "height": seen.height,
                    "jpeg": jpeg?.base64EncodedString() ?? NSNull()])
    }

    private func mediaJSON(_ id: String, _ kind: String, _ url: URL) -> [String: Any] {
        let bytes = ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.int64Value ?? 0
        return ["id": id, "kind": kind, "bytes": bytes,
                "url": server.map { $0.base + "/media/\(id).\(kind)" } ?? NSNull()]
    }

    private func mediaList() -> [[String: Any]] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: mediaDirectory.path)) ?? []
        return names.sorted().compactMap { n in
            guard let dot = n.lastIndex(of: "."), dot != n.startIndex else { return nil }
            let id = String(n[..<dot])
            let kind = String(n[n.index(after: dot)...])
            guard GlassesCore.isMediaId(id), kind == "jpg" || kind == "mp4", id != core.clip else { return nil }
            return mediaJSON(id, kind, mediaDirectory.appendingPathComponent(n))
        }
    }

    private func mediaFile(_ call: NativeExtCall) -> URL? {
        guard let id = call.params["id"] as? String, GlassesCore.isMediaId(id), id != core.clip,
              let kind = call.params["kind"] as? String else { return nil }
        let url = mediaDirectory.appendingPathComponent("\(id).\(kind)")
        var dir: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &dir) && !dir.boolValue ? url : nil
    }

    private func mediaRead(_ call: NativeExtCall) -> NativeExtResult {
        guard let url = mediaFile(call), let h = try? FileHandle(forReadingFrom: url) else {
            return .error(code: "not_found", message: "no such photo or clip")
        }
        defer { try? h.close() }
        let chunk = Int64(call.params["chunk"] as? Int ?? 0)
        let offset = chunk * Int64(Self.mediaChunk)
        let length = Self.mediaChunk
        let total = Int64((try? h.seekToEnd()) ?? 0)
        let data: Data
        do {
            try h.seek(toOffset: UInt64(min(offset, total)))
            data = try h.read(upToCount: Int(max(0, min(Int64(length), total - offset)))) ?? Data()
        } catch {
            return .error(code: "not_found", message: "the file could not be read")
        }
        return .ok(["id": call.params["id"] ?? "", "kind": call.params["kind"] ?? "", "chunk": chunk, "offset": offset,
                    "length": data.count, "total": total, "eof": offset + Int64(data.count) >= total,
                    "data": data.base64EncodedString()])
    }

    private func mediaDelete(_ call: NativeExtCall) -> NativeExtResult {
        guard let url = mediaFile(call), (try? FileManager.default.removeItem(at: url)) != nil else {
            return .error(code: "not_found", message: "no such photo or clip")
        }
        return .ok(["id": call.params["id"] ?? "", "kind": call.params["kind"] ?? "", "deleted": true])
    }

    // MARK: - Mock Device Kit

    /// Meta's Mock Device Kit: fake registration, a paired Ray-Ban Meta mock and a synthesized
    /// test feed (red left, blue right, moving white bar), so the simulator streams without glasses.
    private func mockRoute(_ call: NativeExtCall) async -> NativeExtResult {
        guard Self.mockAllowed else { return .error(code: "mock_unavailable", message: "the mock glasses run in debug builds only") }
        let on = call.params["enabled"] as? Bool ?? false
        let kit = MockDeviceKit.shared
        if !on {
            teardown("mock_off")
            endSession("mock_off")
            if let g = mockGlasses { await kit.unpairDevice(g) }
            mockGlasses = nil
            await kit.disable()
            mock = false
            return .ok(status())
        }
        if mock { return .ok(status()) }
        let dir = context.dataDirectory.appendingPathComponent("mock", isDirectory: true)
        let feed = dir.appendingPathComponent("feed.mp4")
        let photo = dir.appendingPathComponent("photo.jpg")
        let made: Result<String, Error> = await Task.detached {
            do {
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let codec = try GlassesMedia.writeMockFeed(to: feed)
                guard let p = GlassesMedia.mockPhoto() else { throw CocoaError(.fileWriteUnknown) }
                try GlassesMedia.write(p, to: photo)
                return .success(codec)
            } catch {
                return .failure(error)
            }
        }.value
        guard case .success(let codec) = made else { return .error(code: "mock_failed", message: "the mock media could not be written") }
        guard call.isActive else { return .error(code: "timeout", message: "the mock media took too long") }
        kit.enable(config: MockDeviceKitConfig(initiallyRegistered: true, initialPermissionsGranted: true))
        do {
            let models: [String: GlassesModel] = [
                "rayban_meta": .rayBanMeta, "meta_rayban_display": .metaRayBanDisplay, "oakley_meta_hstn": .oakleyMetaHSTN,
                "oakley_meta_vanguard": .oakleyMetaVanguard, "rayban_meta_optics": .rayBanMetaOptics, "meta_glasses": .metaGlasses,
            ]
            let g = try kit.pairGlasses(model: models[call.params["model"] as? String ?? ""] ?? .rayBanMeta)
            g.powerOn()
            g.unfold()
            g.don()
            g.services.camera.setCameraFeed(fileURL: feed)
            g.services.camera.setCapturedImage(fileURL: photo)
            g.services.cameraCapture.setCapturedPhoto(fileURL: photo)
            mockGlasses = g
        } catch {
            await kit.disable()
            return .error(code: "mock_failed", message: error.localizedDescription)
        }
        mock = true
        var o = status()
        o["mockFeedCodec"] = codec
        return .ok(o)
    }

    /// Drives the Mock Device Kit's simulated glasses (debug builds, after mock enabled true), so a
    /// page can exercise every event without real glasses.
    private func mockEvent(_ call: NativeExtCall) -> NativeExtResult {
        guard Self.mockAllowed else { return .error(code: "mock_unavailable", message: "the mock glasses run in debug builds only") }
        guard let g = mockGlasses else { return .error(code: "not_active", message: "turn the mock glasses on first (mock enabled true)") }
        let ev = call.params["event"] as? String ?? ""
        let value = call.params["value"] as? Int ?? 0
        let text = call.params["text"] as? String ?? ""
        var out: [String: Any] = ["event": ev]
        switch ev {
        case "power_on": g.powerOn()
        case "power_off": g.powerOff()
        case "don": g.don()
        case "doff": g.doff()
        case "fold": g.fold()
        case "unfold": g.unfold()
        case "battery": g.setBatteryLevel(value)
        case "charging": g.setChargingState(value != 0 ? .charging : .notCharging)
        case "thermal":
            let levels: [ThermalLevel] = [.none, .light, .moderate, .severe, .critical, .emergency, .shutdown]
            g.setThermalLevel(levels[max(0, min(levels.count - 1, value))])
        case "tap": g.services.captouch.tap()
        case "tap_and_hold": g.services.captouch.tapAndHold()
        case "nav_up": g.services.input.navUp()
        case "nav_down": g.services.input.navDown()
        case "nav_left": g.services.input.navLeft()
        case "nav_right": g.services.input.navRight()
        case "select": g.services.input.select()
        case "back": g.services.input.back()
        case "capture": g.services.input.capture(pressType: text == "hold" ? .hold : text == "double" ? .doublePress : .shortPress)
        case "action_button": g.services.input.button()
        case "drag": g.services.input.drag(action: .move, x: 0, y: 0, dx: Float(value), dy: 0)
        case "transcription":
            g.services.speech.simulateTranscription(text: text, isFinal: value != 0, confidence: 0.9)
        case "speech_locale": g.services.speech.setLocale(text)
        case "voice_launch": out["action"] = g.services.voiceInvocation.simulateLaunchAppAction() ?? NSNull()
        case "photo_failure": g.services.cameraCapture.simulateCaptureFailure()
        case "app_update_required": g.services.setDATAppUpdateRequirementEnabled(value != 0)
        case "firmware_update_required": g.services.setFirmwareUpdateRequirementEnabled(value != 0)
        case "camera_permission":
            MockDeviceKit.shared.permissions.set(.camera, value != 0 ? .granted : .denied)
        case "microphone_permission":
            MockDeviceKit.shared.permissions.set(.microphone, value != 0 ? .granted : .denied)
        case "display_click":
            out["delivered"] = g.services.display.sendClick(identifier: text)
        default:
            return .error(code: "bad_request", message: "unknown mock event")
        }
        return .ok(out)
    }

    // MARK: - Helpers

    private static func newId(_ prefix: String) -> String {
        let ms = UInt64(Date().timeIntervalSince1970 * 1000)
        return prefix + String(ms, radix: 36) + String(format: "%04x", UInt16.random(in: 0...UInt16.max))
    }

    nonisolated private static func sessionError(_ error: Error) -> NativeExtResult {
        guard let e = error as? DeviceSessionError else { return .error(code: "session_failed", message: error.localizedDescription) }
        switch e {
        case .noEligibleDevice: return .error(code: "no_device", message: e.localizedDescription)
        case .notRegistered: return .error(code: "registration_required", message: e.localizedDescription)
        case .sessionAlreadyExists, .capabilityAlreadyActive: return .error(code: "busy", message: e.localizedDescription)
        case .thermalCritical, .thermalEmergency, .peakPowerShutdown, .batteryCritical:
            return .error(code: "device_limit", message: e.localizedDescription)
        default: return .error(code: "session_failed", message: e.localizedDescription)
        }
    }

    nonisolated private static func streamError(_ e: StreamError) -> NativeExtResult {
        switch e {
        case .permissionDenied: return .error(code: "permission_denied", message: e.localizedDescription)
        case .deviceNotFound, .deviceNotConnected: return .error(code: "no_device", message: e.localizedDescription)
        case .hingesClosed: return .error(code: "glasses_folded", message: e.localizedDescription)
        case .thermalHot, .batteryLow, .peakPowerLimit: return .error(code: "device_limit", message: e.localizedDescription)
        case .timeout: return .error(code: "timeout", message: e.localizedDescription)
        default: return .error(code: "stream_failed", message: e.localizedDescription)
        }
    }
}

/// Moves frames off the SDK's thread. The newest frame wins for the preview (a frame still
/// waiting is replaced, so the page never lags); every frame goes to an open clip.
final class GlassesFramePipe: @unchecked Sendable {
    private let queue = DispatchQueue(label: "nx.glasses.frames", qos: .userInitiated)
    private let lock = NSLock()
    private var gen: Int64 = 0
    private var pending: VideoFrame?
    private var scheduled = false
    private var server: GlassesServer?
    private var clip: GlassesClipWriter?
    private var clipReported = false
    private var width = 0
    private var height = 0
    private var frames: Int64 = 0
    var onClipProblem: (@Sendable (String) -> Void)?

    func begin(_ g: Int64, server: GlassesServer) {
        lock.lock()
        gen = g
        self.server = server
        width = 0
        height = 0
        lock.unlock()
    }

    func end() {
        lock.lock()
        gen = 0
        pending = nil
        lock.unlock()
    }

    func stats() -> (width: Int, height: Int, frames: Int64) {
        lock.lock()
        defer { lock.unlock() }
        return (width, height, frames)
    }

    func setClip(_ writer: GlassesClipWriter) {
        lock.lock()
        clip = writer
        clipReported = false
        lock.unlock()
    }

    func takeClip() -> GlassesClipWriter? {
        lock.lock()
        defer { lock.unlock() }
        let c = clip
        clip = nil
        return c
    }

    /// On the SDK's frame thread.
    func push(_ f: VideoFrame, gen g: Int64) {
        lock.lock()
        guard g == gen else {
            lock.unlock()
            return
        }
        if let clip {
            clip.append(f.sampleBuffer)
            if (clip.sizeChanged || clip.failed) && !clipReported {
                clipReported = true
                onClipProblem?(clip.sizeChanged ? "resolution_changed" : "writer_failed")
            }
        }
        pending = f
        let go = !scheduled
        scheduled = true
        lock.unlock()
        if go { queue.async { self.drain() } }
    }

    /// On the SDK's audio thread: the stream's PCM goes to an open clip only.
    func pushAudio(_ a: AudioFrame, gen g: Int64) {
        lock.lock()
        defer { lock.unlock() }
        guard g == gen, let clip else { return }
        clip.appendAudio(a.pcmBuffer, at: a.presentationTimeStamp)
    }

    private func drain() {
        while true {
            lock.lock()
            guard let f = pending, gen != 0 else {
                pending = nil
                scheduled = false
                lock.unlock()
                return
            }
            pending = nil
            let s = server
            let g = gen
            lock.unlock()
            guard let image = f.makeUIImage(), let jpeg = image.jpegData(compressionQuality: GlassesMedia.previewQuality) else { continue }
            lock.lock()
            let current = g == gen
            if current {
                width = Int(image.size.width * image.scale)
                height = Int(image.size.height * image.scale)
                frames += 1
            }
            lock.unlock()
            if current { s?.publish(jpeg) }
        }
    }
}
#endif
