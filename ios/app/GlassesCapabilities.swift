#if DESPIA_NX_ENABLED
import Foundation
import MWDATCore
import MWDATInputs
import MWDATMotion
import MWDATSpeech

/// The DAT capabilities beside the camera: device state, speech, motion, inputs and voice
/// invocations. Each one attaches to the shared `DeviceSession` (`GlassesController.openSession`),
/// starts only on its route and ends on its stop route, on session end or on teardown. Speech,
/// motion, inputs and voice invocations are marked experimental in DAT 1.1.0 (Meta's changelog):
/// they build and run, and Meta does not yet allow apps that use them to be published.
@available(iOS 17.2, *)
enum GlassesJSON {
    static func link(_ s: LinkState) -> String {
        switch s {
        case .connected: return "connected"
        case .connecting: return "connecting"
        default: return "disconnected"
        }
    }

    static func compatibility(_ c: Compatibility) -> String {
        switch c {
        case .compatible: return "compatible"
        case .deviceUpdateRequired: return "device_update_required"
        case .sdkUpdateRequired: return "sdk_update_required"
        default: return "undefined"
        }
    }

    static func charging(_ c: ChargingState) -> String {
        switch c {
        case .charging: return "charging"
        case .notCharging: return "not_charging"
        default: return "unknown"
        }
    }

    static func don(_ d: DonState) -> String {
        switch d {
        case .donned: return "donned"
        case .doffed: return "doffed"
        default: return "unknown"
        }
    }

    static func hinge(_ h: HingeState) -> String {
        switch h {
        case .open: return "open"
        case .closed: return "closed"
        default: return "unknown"
        }
    }

    static func thermal(_ t: ThermalLevel) -> String {
        switch t {
        case .none: return "none"
        case .light: return "light"
        case .moderate: return "moderate"
        case .severe: return "severe"
        case .critical: return "critical"
        case .emergency: return "emergency"
        case .shutdown: return "shutdown"
        default: return "unknown"
        }
    }

    /// The same words as Android's DeviceType enum, lower case.
    static func type(_ t: DeviceType) -> String {
        switch t {
        case .rayBanMeta: return "rayban_meta"
        case .oakleyMetaHSTN: return "oakley_meta_hstn"
        case .oakleyMetaVanguard: return "oakley_meta_vanguard"
        case .metaRayBanDisplay: return "meta_rayban_display"
        case .rayBanMetaOptics: return "rayban_meta_optics"
        case .metaGlasses: return "meta_glasses"
        default: return "unknown"
        }
    }

    static func session(_ s: DeviceSessionState) -> String {
        switch s {
        case .idle: return "idle"
        case .starting: return "starting"
        case .started: return "started"
        case .paused: return "paused"
        case .stopping: return "stopping"
        case .stopped: return "stopped"
        @unknown default: return "stopped"
        }
    }

    /// One device. iOS's Device has no firmware accessor (DAT 1.1.0); firmware is null here and set
    /// on Android, which exposes Device.firmwareInfo.
    static func device(_ d: Device) -> [String: Any] {
        [
            "id": d.identifier, "name": d.nameOrId(), "type": type(d.deviceType()),
            "link": link(d.linkState), "compatibility": compatibility(d.compatibility()),
            "battery": d.batteryLevel.map { $0 as Any } ?? NSNull(), "charging": charging(d.chargingState),
            "worn": don(d.donState), "hinge": hinge(d.hingeState), "thermal": thermal(d.thermalLevel),
            "display": d.supportsDisplay(), "firmware": NSNull(),
        ]
    }

    static func device(_ id: String, _ s: DeviceState) -> [String: Any] {
        [
            "id": id, "link": link(s.linkState), "compatibility": compatibility(s.compatibility),
            "battery": s.batteryLevel.map { $0 as Any } ?? NSNull(), "charging": charging(s.chargingState),
            "worn": don(s.donState), "hinge": hinge(s.hingeState), "thermal": thermal(s.thermalLevel),
        ]
    }
}

/// Watches the device list and every device's state, link and compatibility, and reports each
/// change as the `device` event. Started with the SDK (configure), never at launch.
@available(iOS 17.2, *)
@MainActor
final class GlassesDevices {
    private let emit: (String, [String: Any]) -> Void
    private let tokens = ListenerTokenBag()
    private var perDevice: [String: ListenerTokenBag] = [:]

    init(emit: @escaping (String, [String: Any]) -> Void) {
        self.emit = emit
    }

    func start() {
        Wearables.shared.addDevicesListener { [weak self] ids in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.devicesChanged(ids) } }
        }.store(in: tokens)
        devicesChanged(Wearables.shared.devices)
    }

    func list() -> [[String: Any]] {
        Wearables.shared.devices.compactMap { Wearables.shared.deviceForIdentifier($0).map(GlassesJSON.device) }
    }

    private func devicesChanged(_ ids: [DeviceIdentifier]) {
        let now = Set(ids)
        for (id, bag) in perDevice where !now.contains(id) {
            bag.clear()
            perDevice[id] = nil
            emit("device", ["id": id, "change": "removed", "devices": list()])
        }
        for id in ids where perDevice[id] == nil {
            guard let d = Wearables.shared.deviceForIdentifier(id) else { continue }
            let bag = ListenerTokenBag()
            perDevice[id] = bag
            d.addDeviceStateListener { [weak self] s in
                DispatchQueue.main.async {
                    MainActor.assumeIsolated {
                        var o = GlassesJSON.device(id, s)
                        o["change"] = "state"
                        self?.emit("device", o)
                    }
                }
            }.store(in: bag)
            d.addCompatibilityListener { [weak self] c in
                DispatchQueue.main.async {
                    MainActor.assumeIsolated { self?.emit("device", ["id": id, "change": "compatibility", "compatibility": GlassesJSON.compatibility(c)]) }
                }
            }.store(in: bag)
            var o = GlassesJSON.device(d)
            o["change"] = "added"
            emit("device", o)
        }
    }
}

/// Speech, motion and inputs on the shared session, and voice invocations on the device.
@available(iOS 17.2, *)
@MainActor
final class GlassesSensors {
    // The same state words on Android.
    nonisolated static func word(_ s: SpeechState) -> String {
        switch s {
        case .starting: return "starting"
        case .started: return "started"
        case .stopping: return "stopping"
        case .stopped: return "stopped"
        }
    }

    nonisolated static func word(_ s: MotionState) -> String {
        switch s {
        case .starting: return "starting"
        case .started: return "started"
        case .stopping: return "stopping"
        case .stopped: return "stopped"
        @unknown default: return "stopped"
        }
    }

    nonisolated static func word(_ s: InputsState) -> String {
        switch s {
        case .inactive: return "inactive"
        case .activating: return "activating"
        case .active: return "active"
        case .deactivating: return "deactivating"
        @unknown default: return "inactive"
        }
    }

    private let emit: (String, [String: Any]) -> Void
    private(set) var speech: Speech?
    private(set) var motion: Motion?
    private(set) var inputs: Inputs?
    private var speechTokens: ListenerTokenBag?
    private var motionTokens: ListenerTokenBag?
    private var inputsTokens: ListenerTokenBag?
    private var motionTask: Task<Void, Never>?
    private var inputsTask: Task<Void, Never>?
    private var voice: VoiceInvocationsStream?
    private var voiceTokens: ListenerTokenBag?
    private var voiceDevice: String?
    private var pending: [String: any ResponseHandle] = [:]
    private var voiceSeq = 0

    init(emit: @escaping (String, [String: Any]) -> Void) {
        self.emit = emit
    }

    var active: Bool { speech != nil || motion != nil || inputs != nil }

    // MARK: speech

    func speechStart(_ s: DeviceSession) -> NativeExtResult {
        if speech != nil { return .error(code: "busy", message: "speech is already listening") }
        let sp: Speech?
        do {
            sp = try s.addSpeech()
        } catch {
            return GlassesErrors.session(error)
        }
        guard let sp else { return .error(code: "unavailable", message: "the glasses offered no speech capability") }
        let bag = ListenerTokenBag()
        sp.statePublisher.listen { [weak self] st in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.emit("speech", ["state": Self.word(st)]) } }
        }.store(in: bag)
        sp.transcriptionPublisher.listen { [weak self] r in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    self?.emit("speech", ["state": "started", "text": r.text, "final": r.isFinal, "confidence": Double(r.confidence)])
                }
            }
        }.store(in: bag)
        sp.localePublisher.listen { [weak self] l in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.emit("speech", ["locale": l]) } }
        }.store(in: bag)
        sp.errorPublisher.listen { [weak self] e in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.emit("speech", ["error": GlassesErrors.speechCode(e), "message": e.description]) }
            }
        }.store(in: bag)
        speech = sp
        speechTokens = bag
        sp.start()
        return .ok(["state": Self.word(sp.state), "locale": sp.locale ?? NSNull()])
    }

    func speechStop(_ s: DeviceSession?) -> NativeExtResult {
        guard let sp = speech else { return .error(code: "not_listening", message: "speech is not listening") }
        sp.stop()
        try? s?.removeSpeech()
        speechTokens?.clear()
        speechTokens = nil
        speech = nil
        return .ok(["state": "stopped"])
    }

    // MARK: motion

    /// Samples arrive at up to 60 Hz; they reach the page batched, one `motion` event per
    /// `batchMs`, so the bridge is never flooded.
    static let batchMs = 100

    func motionStart(_ s: DeviceSession, rate: Int) -> NativeExtResult {
        if motion != nil { return .error(code: "busy", message: "motion already streams") }
        let r: MotionSamplingRate
        switch rate {
        case 5: r = .hz5
        case 15: r = .hz15
        case 24: r = .hz24
        case 30: r = .hz30
        case 60: r = .hz60
        default: r = .hz10
        }
        let m: Motion?
        do {
            m = try s.addMotion(configuration: MotionConfiguration(samplingRate: r))
        } catch {
            return GlassesErrors.session(error)
        }
        guard let m else { return .error(code: "unavailable", message: "the glasses offered no motion sensors") }
        let bag = ListenerTokenBag()
        m.statePublisher.listen { [weak self] st in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.emit("motion", ["state": Self.word(st)]) } }
        }.store(in: bag)
        m.errorPublisher.listen { [weak self] e in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.emit("motion", ["error": GlassesErrors.motionCode(e), "message": e.description]) }
            }
        }.store(in: bag)
        motion = m
        motionTokens = bag
        let samples = m.samples
        motionTask = Task { [weak self] in
            var batch: [[String: Any]] = []
            var last = DispatchTime.now()
            for await x in samples {
                batch.append(Self.sample(x))
                let now = DispatchTime.now()
                if now.uptimeNanoseconds - last.uptimeNanoseconds >= UInt64(Self.batchMs) * 1_000_000 {
                    let out = batch
                    batch = []
                    last = now
                    await MainActor.run { self?.emit("motion", ["samples": out]) }
                }
            }
        }
        m.start()
        return .ok(["state": Self.word(m.state), "rate": rate])
    }

    nonisolated static func sample(_ s: MotionSample) -> [String: Any] {
        func v(_ x: MWDATMotion.Vector3?) -> Any { x.map { [Double($0.x), Double($0.y), Double($0.z)] } ?? NSNull() }
        let source: String
        switch s.source {
        case .glasses: source = "glasses"
        case .neuralBand: source = "neural_band"
        default: source = "unknown"
        }
        return ["t": s.timestampNs, "accel": v(s.accelerometer), "gyro": v(s.gyroscope), "mag": v(s.magnetometer),
                "orientation": s.orientation.map { [Double($0.x), Double($0.y), Double($0.z), Double($0.w)] as Any } ?? NSNull(),
                "source": source]
    }

    func motionStop(_ s: DeviceSession?) -> NativeExtResult {
        guard let m = motion else { return .error(code: "not_streaming", message: "motion does not stream") }
        motionTask?.cancel()
        motionTask = nil
        m.stop()
        try? s?.removeMotion()
        motionTokens?.clear()
        motionTokens = nil
        motion = nil
        return .ok(["state": "stopped"])
    }

    // MARK: inputs

    static let sourceNames: [String: InputSource] = [
        "captouch": .captouch, "neural_band": .neuralBand, "capture_button": .captureButton,
        "action_button": .actionButton, "neural_band_drag": .neuralBandDrag,
    ]

    /// sources: a comma list of the names above; empty for DAT's default set (all of them).
    func inputsStart(_ s: DeviceSession, sources: String?, consumeBack: Bool) -> NativeExtResult {
        if inputs != nil { return .error(code: "busy", message: "inputs are already on") }
        var set = Set<InputSource>()
        for name in (sources ?? "").split(separator: ",").map({ $0.trimmingCharacters(in: .whitespaces) }) where !name.isEmpty {
            guard let src = Self.sourceNames[name] else { return .error(code: "bad_request", message: "unknown input source \(name)") }
            set.insert(src)
        }
        let config = set.isEmpty ? InputsConfiguration(consumeBack: consumeBack) : InputsConfiguration(sources: set, consumeBack: consumeBack)
        let i: Inputs?
        do {
            i = try s.addInputs(configuration: config)
        } catch {
            return GlassesErrors.session(error)
        }
        guard let i else { return .error(code: "unavailable", message: "the glasses offered no inputs") }
        let bag = ListenerTokenBag()
        i.statePublisher.listen { [weak self] st in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.emit("input", ["state": Self.word(st)]) } }
        }.store(in: bag)
        i.errorPublisher.listen { [weak self] e in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.emit("input", ["error": GlassesErrors.inputsCode(e), "message": e.description]) }
            }
        }.store(in: bag)
        inputs = i
        inputsTokens = bag
        let events = i.events
        inputsTask = Task { [weak self] in
            for await e in events {
                let o = Self.inputEvent(e)
                await MainActor.run { self?.emit("input", o) }
            }
        }
        return .ok(["state": Self.word(i.state), "consumeBack": consumeBack])
    }

    nonisolated static func source(_ s: InputSource) -> String {
        switch s {
        case .captouch: return "captouch"
        case .neuralBand: return "neural_band"
        case .captureButton: return "capture_button"
        case .actionButton: return "action_button"
        case .neuralBandDrag: return "neural_band_drag"
        default: return "unknown"
        }
    }

    nonisolated static func inputEvent(_ e: InputEvent) -> [String: Any] {
        var o: [String: Any] = ["source": source(e.source), "t": e.timestampMs]
        switch e {
        case .nav(let d, _, _):
            o["kind"] = "nav"
            switch d {
            case .up: o["direction"] = "up"
            case .down: o["direction"] = "down"
            case .left: o["direction"] = "left"
            case .right: o["direction"] = "right"
            @unknown default: o["direction"] = "unknown"
            }
        case .select: o["kind"] = "select"
        case .back: o["kind"] = "back"
        case .button: o["kind"] = "button"; o["button"] = "action"
        case .capture(let p, _, _):
            o["kind"] = "capture"
            switch p {
            case .shortPress: o["press"] = "short"
            case .hold: o["press"] = "hold"
            case .doublePress: o["press"] = "double"
            @unknown default: o["press"] = "unknown"
            }
        case .drag(let a, let x, let y, let dx, let dy, _, _):
            o["kind"] = "drag"
            switch a {
            case .down: o["action"] = "down"
            case .move: o["action"] = "move"
            case .up: o["action"] = "up"
            @unknown default: o["action"] = "unknown"
            }
            o["x"] = Double(x); o["y"] = Double(y); o["dx"] = Double(dx); o["dy"] = Double(dy)
        @unknown default:
            o["kind"] = "unknown"
        }
        return o
    }

    func inputsStop(_ s: DeviceSession?) -> NativeExtResult {
        guard inputs != nil else { return .error(code: "not_active", message: "inputs are off") }
        inputsTask?.cancel()
        inputsTask = nil
        try? s?.removeInputs()
        inputsTokens?.clear()
        inputsTokens = nil
        inputs = nil
        return .ok(["state": "inactive"])
    }

    /// The session ended: every capability on it is gone.
    func sessionEnded() {
        if speech != nil { _ = speechStop(nil); emit("speech", ["state": "stopped", "reason": "session_stopped"]) }
        if motion != nil { _ = motionStop(nil); emit("motion", ["state": "stopped", "reason": "session_stopped"]) }
        if inputs != nil { _ = inputsStop(nil); emit("input", ["state": "inactive", "reason": "session_stopped"]) }
    }

    // MARK: voice invocations ("Hey Meta, open <app>")

    func voiceStart(device: String) -> NativeExtResult {
        if voice != nil { return .ok(["state": "started", "device": voiceDevice ?? device]) }
        let v: VoiceInvocationsStream
        do {
            v = try VoiceInvocationsStream(wearables: Wearables.shared)
            try v.start(deviceIdentifier: device)
        } catch {
            return .error(code: GlassesErrors.voiceCode(error), message: error.localizedDescription)
        }
        let bag = ListenerTokenBag()
        v.invocationsPublisher.listen { [weak self] inv in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.invoked(inv) } }
        }.store(in: bag)
        v.errorPublisher.listen { [weak self] e in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.emit("voice", ["error": GlassesErrors.voiceCode(e), "message": e.description]) }
            }
        }.store(in: bag)
        voice = v
        voiceTokens = bag
        voiceDevice = device
        return .ok(["state": "started", "device": device])
    }

    private func invoked(_ inv: any VoiceInvocation) {
        guard let launch = inv as? LaunchApp else {
            emit("voice", ["kind": "unknown"])
            return
        }
        voiceSeq += 1
        let id = "v\(voiceSeq)"
        pending[id] = launch.responseHandle
        emit("voice", ["kind": "launch_app", "id": id, "device": launch.deviceIdentifier])
    }

    func voiceRespond(id: String, success: Bool, output: String?) async -> NativeExtResult {
        guard let h = pending.removeValue(forKey: id) else { return .error(code: "not_found", message: "no open voice invocation \(id)") }
        let sent = success ? await h.sendSuccess(actionOutput: output) : await h.sendFailure(actionOutput: output)
        return .ok(["id": id, "sent": sent])
    }

    func voiceStop() -> NativeExtResult {
        guard let v = voice else { return .error(code: "not_active", message: "voice invocations are off") }
        v.stop()
        voiceTokens?.clear()
        voiceTokens = nil
        voice = nil
        voiceDevice = nil
        pending.removeAll()
        return .ok(["state": "stopped"])
    }

    var voiceState: [String: Any] {
        ["on": voice != nil, "device": voiceDevice ?? NSNull(), "pending": pending.keys.sorted()]
    }
}

/// Error words shared by the controller and the capabilities; Android uses the same words.
@available(iOS 17.2, *)
enum GlassesErrors {
    static func session(_ error: Error) -> NativeExtResult {
        guard let e = error as? DeviceSessionError else { return .error(code: "session_failed", message: error.localizedDescription) }
        return .error(code: sessionCode(e), message: e.description)
    }

    static func sessionCode(_ e: DeviceSessionError) -> String {
        switch e {
        case .noEligibleDevice: return "no_device"
        case .notRegistered: return "registration_required"
        case .sessionAlreadyExists, .capabilityAlreadyActive: return "busy"
        case .thermalCritical, .thermalEmergency, .peakPowerShutdown, .batteryCritical: return "device_limit"
        case .datAppOnTheGlassesUpdateRequired: return "glasses_app_update_required"
        case .insufficientSDKVersion: return "sdk_update_required"
        case .dwaUnavailable: return "unavailable"
        case .dwaOutOfStuRange: return "update_suggested"
        case .sessionIdle, .sessionAlreadyStopped: return "session_stopped"
        case .capabilityNotFound: return "not_active"
        default: return "session_failed"
        }
    }

    static func speechCode(_ e: SpeechError) -> String {
        switch e {
        case .deviceDisconnected: return "no_device"
        case .alreadyListening: return "busy"
        case .unavailable: return "unavailable"
        default: return "speech_failed"
        }
    }

    static func motionCode(_ e: MotionError) -> String {
        switch e {
        case .deviceDisconnected: return "no_device"
        case .sensorUnavailable: return "unavailable"
        default: return "motion_failed"
        }
    }

    static func inputsCode(_ e: InputsError) -> String {
        switch e {
        case .permissionDenied: return "permission_denied"
        case .deviceDisconnected: return "no_device"
        case .capabilityUnavailable: return "unavailable"
        case .activationTimeout: return "timeout"
        default: return "inputs_failed"
        }
    }

    static func voiceCode(_ error: Error) -> String {
        guard let e = error as? VoiceInvocationError else { return "voice_failed" }
        switch e {
        case .deviceNotFound: return "no_device"
        case .channelNotConnected: return "no_device"
        default: return "voice_failed"
        }
    }
}
#endif
