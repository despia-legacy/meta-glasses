#if DESPIA_NX_ENABLED
import Foundation
import MWDATCore
import MWDATDisplay

/// The glasses' display (Meta Ray-Ban Display) through DAT's Display capability. The page sends a
/// view tree as JSON (`GlassesDisplayTree`, the same words on Android); taps on buttons and boxes
/// with an `id` come back as the `display` event. Video plays from an http(s) URL. Kept in its own
/// file: MWDATDisplay's Text, Button, Image and VideoCodec would clash with other modules.
@available(iOS 17.2, *)
@MainActor
final class GlassesDisplay {
    private let emit: (String, [String: Any]) -> Void
    private(set) var display: Display?
    private var tokens: ListenerTokenBag?

    init(emit: @escaping (String, [String: Any]) -> Void) {
        self.emit = emit
    }

    var active: Bool { display != nil }

    static func state(_ s: DisplayState) -> String {
        switch s {
        case .starting: return "starting"
        case .started: return "started"
        case .stopping: return "stopping"
        case .stopped: return "stopped"
        @unknown default: return "stopped"
        }
    }

    private var waiting: [CheckedContinuation<Void, Never>] = []
    private var outcome: NativeExtResult?
    private var waitTask: Task<Void, Never>?
    private var begun = false

    var started: Bool { display?.state == .started }

    /// Answers once the display reports `.started`: Meta's Display guide (DAT 1.1.0, display-access) sends
    /// content only after the Display capability is started. Answers `display_failed` when the display stops
    /// before it starts, `timeout` after 20 s.
    func start(_ s: DeviceSession) async -> NativeExtResult {
        if display == nil {
            if let device = s.device, !device.supportsDisplay() {
                return .error(code: "unsupported_device", message: "these glasses have no display")
            }
            let d: Display
            do {
                d = try s.addDisplay()
            } catch {
                return GlassesErrors.session(error)
            }
            let bag = ListenerTokenBag()
            d.statePublisher.listen { [weak self] st in
                DispatchQueue.main.async {
                    MainActor.assumeIsolated {
                        self?.emit("display", ["state": Self.state(st)])
                        self?.observe(st)
                    }
                }
            }.store(in: bag)
            d.onPlaybackEvent = { [weak self] e in
                DispatchQueue.main.async { MainActor.assumeIsolated { self?.emit("display", Self.playback(e)) } }
            }
            display = d
            tokens = bag
            begun = false
            d.start()
        }
        if started { return .ok(["state": "started"]) }
        if waitTask == nil {
            waitTask = Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                guard !Task.isCancelled else { return }
                self?.settle(.error(code: "timeout", message: "the display did not start within 20 s"))
            }
        }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in waiting.append(c) }
        return outcome ?? .error(code: "display_failed", message: "the display did not start")
    }

    private func observe(_ st: DisplayState) {
        if st == .starting || st == .started { begun = true }
        if st == .started {
            settle(.ok(["state": "started"]))
        } else if begun, st == .stopped {
            settle(.error(code: "display_failed", message: "the display stopped before it started"))
        }
    }

    private func settle(_ r: NativeExtResult) {
        waitTask?.cancel()
        waitTask = nil
        guard !waiting.isEmpty else { return }
        outcome = r
        let w = waiting
        waiting = []
        for c in w { c.resume() }
    }

    private func notStarted() -> NativeExtResult {
        display == nil ? .error(code: "not_active", message: "start the display first (display_start)")
            : .error(code: "not_active", message: "the display is not started yet (display_start answers once it is)")
    }

    nonisolated static func playback(_ e: VideoPlaybackEvent) -> [String: Any] {
        let type: String
        switch e.type {
        case .started: type = "started"
        case .ended: type = "ended"
        case .stopped: type = "stopped"
        case .error: type = "error"
        default: type = "unknown"
        }
        var o: [String: Any] = ["video": type]
        if e.type == .error {
            switch e.errorType {
            case .urlInvalid: o["error"] = "invalid_url"
            case .alreadyPlaying: o["error"] = "busy"
            case .playbackFailed: o["error"] = "playback_failed"
            default: o["error"] = "unknown"
            }
        }
        return o
    }

    func send(_ json: String) async -> NativeExtResult {
        guard let d = display, started else { return notStarted() }
        let tree: GlassesDisplayTree.Node
        do {
            tree = try GlassesDisplayTree.parse(json)
        } catch {
            return .error(code: "bad_request", message: "\(error)")
        }
        guard case .box = tree.kind else { return .error(code: "bad_request", message: "the root must be a box") }
        let click: @Sendable (String) -> Void = { [weak self] id in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.emit("display", ["click": id]) } }
        }
        do {
            try await d.send(Self.box(tree, click: click))
        } catch {
            return .error(code: "display_failed", message: error.localizedDescription)
        }
        return .ok(["sent": true])
    }

    func video(_ url: String) async -> NativeExtResult {
        guard let d = display, started else { return notStarted() }
        let player = VideoPlayer(provider: .uri(url), codec: .mp4, onError: { [weak self] e in
            let message = e.description
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.emit("display", ["video": "error", "error": "playback_failed", "message": message]) }
            }
        })
        do {
            try await d.send(player)
        } catch {
            return .error(code: "display_failed", message: error.localizedDescription)
        }
        return .ok(["playing": url])
    }

    func videoStop() async -> NativeExtResult {
        guard let d = display else { return .error(code: "not_active", message: "the display is off") }
        await d.sendVideoStop()
        return .ok(["stopped": true])
    }

    func clear() async -> NativeExtResult {
        guard let d = display else { return .error(code: "not_active", message: "the display is off") }
        guard started else { return notStarted() }
        do {
            try await d.clearDisplay()
        } catch {
            return .error(code: "display_failed", message: error.localizedDescription)
        }
        return .ok(["cleared": true])
    }

    func stop(_ s: DeviceSession?) -> NativeExtResult {
        guard let d = display else { return .error(code: "not_active", message: "the display is off") }
        settle(.error(code: "display_failed", message: "the display was stopped before it started"))
        d.stop()
        tokens?.clear()
        tokens = nil
        display = nil
        return .ok(["state": "stopped"])
    }

    func sessionEnded() {
        guard display != nil else { return }
        settle(.error(code: "session_failed", message: "the glasses session ended before the display started"))
        tokens?.clear()
        tokens = nil
        display = nil
        emit("display", ["state": "stopped", "reason": "session_stopped"])
    }

    // MARK: tree to DAT views

    private static func box(_ n: GlassesDisplayTree.Node, click: @escaping @Sendable (String) -> Void) -> FlexBox {
        let a = n.attrs
        var children: [any ViewComponent] = []
        for c in n.children { children.append(component(c, click: click)) }
        var b = FlexBox(direction: direction(a["direction"]), spacing: CGFloat(a.num("spacing") ?? 0),
                        alignment: alignment(a["align"]) ?? .start, crossAlignment: alignment(a["cross"]) ?? .start,
                        wrap: a.bool("wrap") ?? false) { for c in children { c } }
        let all = a.num("padding")
        let edges = ["padding_top", "padding_bottom", "padding_start", "padding_end"].map { a.num($0) }
        if edges.contains(where: { $0 != nil }) {
            let v = edges.map { CGFloat($0 ?? all ?? 0) }
            b = b.padding(EdgeInsets(top: v[0], bottom: v[1], leading: v[2], trailing: v[3]))
        } else if let all {
            b = b.padding(CGFloat(all))
        }
        if a["background"] == "card" { b = b.background(.card) }
        if let id = a["id"] { b = b.onTap { click(id) } }
        return flex(b, a)
    }

    private static func flex(_ b: FlexBox, _ a: GlassesDisplayTree.Attrs) -> FlexBox {
        var b = b
        if let g = a.num("grow") { b = b.flexGrow(Float(g)) }
        if let s = a.num("shrink") { b = b.flexShrink(Float(s)) }
        if let s = alignment(a["self"]) { b = b.alignSelf(s) }
        return b
    }

    private static func component(_ n: GlassesDisplayTree.Node, click: @escaping @Sendable (String) -> Void) -> any ViewComponent {
        let a = n.attrs
        switch n.kind {
        case .box:
            return box(n, click: click)
        case .text:
            var t = Text(a["text"] ?? "", style: a["style"] == "heading" ? .heading : a["style"] == "meta" ? .meta : .body,
                         color: a["color"] == "secondary" ? .secondary : .primary)
            if let g = a.num("grow") { t = t.flexGrow(Float(g)) }
            if let s = a.num("shrink") { t = t.flexShrink(Float(s)) }
            if let s = alignment(a["self"]) { t = t.alignSelf(s) }
            return t
        case .button:
            var b = button(a, click: click)
            if let g = a.num("grow") { b = b.flexGrow(Float(g)) }
            if let s = a.num("shrink") { b = b.flexShrink(Float(s)) }
            if let s = alignment(a["self"]) { b = b.alignSelf(s) }
            return b
        case .buttons:
            let buttons = n.children.filter { $0.kind == .button }.map { button($0.attrs, click: click) }
            let align: ButtonGroupAlignment = a["align"] == "start" ? .start : a["align"] == "end" ? .end : .center
            var g = ButtonGroup(alignment: align) { for b in buttons { b } }
            if let x = a.num("grow") { g = g.flexGrow(Float(x)) }
            if let x = a.num("shrink") { g = g.flexShrink(Float(x)) }
            if let s = alignment(a["self"]) { g = g.alignSelf(s) }
            return g
        case .icon:
            var i = Icon(name: IconName(rawValue: a["name"] ?? "") ?? .smartGlasses, style: a["style"] == "outline" ? .outline : .filled)
            if let g = a.num("grow") { i = i.flexGrow(Float(g)) }
            if let s = alignment(a["self"]) { i = i.alignSelf(s) }
            return i
        case .image:
            var i = Image(uri: a["src"] ?? "", sizePreset: a["size"] == "fill" ? .fill : .icon,
                          cornerRadius: a["radius"] == "small" ? .small : a["radius"] == "medium" ? .medium : .none)
            if let g = a.num("grow") { i = i.flexGrow(Float(g)) }
            if let s = alignment(a["self"]) { i = i.alignSelf(s) }
            return i
        }
    }

    private static func button(_ a: GlassesDisplayTree.Attrs, click: @escaping @Sendable (String) -> Void) -> Button {
        let style: ButtonStyle = a["style"] == "secondary" ? .secondary : a["style"] == "outline" ? .outline : .primary
        let id = a["id"]
        var b = Button(label: a["label"] ?? "", style: style, iconName: a["icon"].flatMap { IconName(rawValue: $0) },
                       onClick: id.map { i in { @Sendable in click(i) } })
        if a.bool("primary") == true { b = b.actionRole(.primary) }
        return b
    }

    private static func direction(_ s: String?) -> Direction {
        switch s {
        case "row": return .row
        case "row_reverse": return .rowReverse
        case "column_reverse": return .columnReverse
        default: return .column
        }
    }

    private static func alignment(_ s: String?) -> Alignment? {
        switch s {
        case "start": return .start
        case "center": return .center
        case "end": return .end
        case "stretch": return .stretch
        default: return nil
        }
    }
}
#endif
