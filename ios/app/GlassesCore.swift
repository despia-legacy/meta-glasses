import Foundation

/// Every decision of the Meta glasses extension that needs no system framework: the loopback HTTP
/// request rules, the byte-range rule, the response heads, the stream parameters and the session
/// and clip state machine. Foundation only, so the shared rules
/// run against this file with plain swiftc. The Java twin is
/// android/glasses/java/despia/ext/metaglasses/GlassesCore.java; both give the same answer for
/// every vector.
enum GlassesCore {
    /// Longest request head (request line and headers) the server reads before refusing.
    static let maxHead = 8192
    /// Longest request target accepted.
    static let maxTarget = 512
    /// The multipart boundary of the live stream.
    static let boundary = "nxframe"

    struct Request: Equatable {
        let method: String
        let path: String
        let range: String?
    }

    /// Parses a request head (everything before the blank line, CRLF line ends). nil for anything
    /// that is not a well-formed GET, HEAD or OPTIONS request with an origin-form target.
    static func parseRequest(_ head: String) -> Request? {
        guard head.utf16.count <= maxHead else { return nil }
        let lines = head.components(separatedBy: "\r\n")
        let parts = lines[0].components(separatedBy: " ")
        guard parts.count == 3 else { return nil }
        let method = parts[0]
        guard ["GET", "HEAD", "OPTIONS"].contains(method), ["HTTP/1.1", "HTTP/1.0"].contains(parts[2]) else { return nil }
        let target = parts[1]
        guard !target.isEmpty, target.utf16.count <= maxTarget, target.hasPrefix("/") else { return nil }
        guard target.unicodeScalars.allSatisfy({ $0.value > 0x20 && $0.value < 0x7f }) else { return nil }
        let path = target.firstIndex(of: "?").map { String(target[..<$0]) } ?? target
        var range: String?
        for line in lines.dropFirst() where !line.isEmpty {
            guard let colon = line.firstIndex(of: ":"), colon != line.startIndex else { return nil }
            let name = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            if name == "range" {
                if range != nil { return nil }
                range = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            }
        }
        return Request(method: method, path: path, range: range)
    }

    struct Target: Equatable {
        /// live, frame, media, forbidden or not_found.
        let kind: String
        var id: String? = nil
        var ext: String? = nil
    }

    /// Classifies a path against the current stream token. The token is the first segment and is
    /// compared in constant time; a wrong or missing token is forbidden before anything else is
    /// looked at, so the path does not reveal which files exist.
    static func classify(_ path: String, token: String) -> Target {
        guard path.hasPrefix("/") else { return Target(kind: "not_found") }
        let body = path.dropFirst()
        let first = body.firstIndex(of: "/").map { String(body[..<$0]) } ?? String(body)
        guard isToken(token), constantTimeEquals(first, token) else { return Target(kind: "forbidden") }
        let rest = String(body.dropFirst(first.count))
        if rest == "/live.mjpg" { return Target(kind: "live") }
        if rest == "/frame.jpg" { return Target(kind: "frame") }
        if rest.hasPrefix("/media/") {
            let file = String(rest.dropFirst("/media/".count))
            if let dot = file.lastIndex(of: "."), dot != file.startIndex {
                let id = String(file[..<dot])
                let ext = String(file[file.index(after: dot)...])
                if isMediaId(id), ext == "jpg" || ext == "mp4" { return Target(kind: "media", id: id, ext: ext) }
            }
        }
        return Target(kind: "not_found")
    }

    /// A stream token: exactly 32 lower-case hex digits (128 bits).
    static func isToken(_ s: String) -> Bool {
        s.utf8.count == 32 && s.utf8.allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x66) }
    }

    /// A media id: 1 to 32 characters of a-z and 0-9.
    static func isMediaId(_ s: String) -> Bool {
        !s.isEmpty && s.utf8.count <= 32 && s.utf8.allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x7a) }
    }

    static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<x.count { diff |= x[i] ^ y[i] }
        return diff == 0
    }

    /// A new random token from the system's cryptographic generator.
    static func newToken() -> String {
        var rng = SystemRandomNumberGenerator()
        return (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255, using: &rng)) }.joined()
    }

    /// The byte range to send for a Range header and a file size: start and end inclusive, and
    /// partial true when the answer is 206 Partial Content with a Content-Range, false when it is
    /// 200 with the whole file. A nil header, one that is not a single satisfiable-form bytes
    /// range, or an empty file: the whole file ((0, total-1, false), or (0, -1, false) for an empty
    /// file); RFC 9110 section 14.2 lets a server ignore a Range it does not support. An
    /// unsatisfiable single range: nil (answer 416).
    static func range(_ header: String?, total: Int64) -> (Int64, Int64, Bool)? {
        let whole = (Int64(0), total - 1, false)
        guard let header, total > 0 else { return whole }
        let h = header.trimmingCharacters(in: .whitespaces)
        guard h.hasPrefix("bytes=") else { return whole }
        let spec = h.dropFirst(6).trimmingCharacters(in: .whitespaces)
        guard !spec.contains(","), let dash = spec.firstIndex(of: "-") else { return whole }
        let a = spec[..<dash].trimmingCharacters(in: .whitespaces)
        let b = spec[spec.index(after: dash)...].trimmingCharacters(in: .whitespaces)
        guard digits(a), digits(b), !(a.isEmpty && b.isEmpty) else { return whole }
        if a.isEmpty {
            let n = Int64(b)!
            if n == 0 { return nil }
            return (max(0, total - n), total - 1, true)
        }
        let start = Int64(a)!
        if start >= total { return nil }
        let end = b.isEmpty ? total - 1 : min(Int64(b)!, total - 1)
        if end < start { return whole }
        return (start, end, true)
    }

    private static func digits(_ s: String) -> Bool {
        s.utf8.count <= 18 && s.utf8.allSatisfy { $0 >= 0x30 && $0 <= 0x39 }
    }

    static func reason(_ status: Int) -> String {
        switch status {
        case 200: return "OK"
        case 204: return "No Content"
        case 206: return "Partial Content"
        case 400: return "Bad Request"
        case 403: return "Forbidden"
        case 404: return "Not Found"
        case 405: return "Method Not Allowed"
        case 416: return "Range Not Satisfiable"
        case 503: return "Service Unavailable"
        default: return "Error"
        }
    }

    /// A response head, byte for byte the same as the Java twin's. The page is on another origin
    /// (its own https site), so every answer allows any origin without credentials: the token in
    /// the path is the capability. Access-Control-Allow-Private-Network answers a Private Network
    /// Access preflight. Nothing is cached.
    static func responseHead(_ status: Int, contentType: String?, length: Int64, contentRange: String?) -> String {
        var s = "HTTP/1.1 \(status) \(reason(status))\r\n"
        if let contentType { s += "Content-Type: \(contentType)\r\n" }
        if length >= 0 { s += "Content-Length: \(length)\r\n" }
        if let contentRange { s += "Content-Range: \(contentRange)\r\n" }
        s += "Accept-Ranges: bytes\r\n"
        s += "Cache-Control: no-store\r\n"
        s += "Access-Control-Allow-Origin: *\r\n"
        s += "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"
        s += "Access-Control-Allow-Headers: Range\r\n"
        s += "Access-Control-Allow-Private-Network: true\r\n"
        s += "Connection: close\r\n\r\n"
        return s
    }

    static func liveHead() -> String {
        responseHead(200, contentType: "multipart/x-mixed-replace; boundary=\(boundary)", length: -1, contentRange: nil)
    }

    static func partHead(_ length: Int) -> String {
        "--\(boundary)\r\nContent-Type: image/jpeg\r\nContent-Length: \(length)\r\n\r\n"
    }

    /// Frame rates DAT accepts (Meta, "Camera Streaming": 2, 7, 15, 24, 30).
    static let frameRates = [2, 7, 15, 24, 30]

    /// The accepted rate nearest to the asked one, ties to the lower; never a rate DAT refuses.
    static func frameRate(_ asked: Int) -> Int {
        var best = frameRates[0]
        for r in frameRates where abs(r - asked) < abs(best - asked) { best = r }
        return best
    }

    /// Frame size of a quality (Meta, "Camera Streaming"): width x height, portrait.
    static func frameSize(_ quality: String) -> (Int, Int) {
        switch quality {
        case "high": return (720, 1280)
        case "low": return (360, 640)
        default: return (504, 896)
        }
    }

    /// The stream's state machine, on the main actor. Each start gets a new generation; every SDK
    /// callback carries the generation it was made for, and a callback for any other generation
    /// changes nothing (counted in staleDrops). A clip is open only while the stream streams, and
    /// at most one at a time.
    struct Session {
        /// idle, starting, streaming, paused.
        var state = "idle"
        var gen: Int64 = 0
        var staleDrops = 0
        var clip: String?
        var clipGen: Int64 = 0

        /// A start: the new generation, or -1 when a stream already starts or runs.
        mutating func begin() -> Int64 {
            guard state == "idle" else { return -1 }
            gen += 1
            state = "starting"
            return gen
        }

        private mutating func current(_ g: Int64) -> Bool {
            if g != gen || state == "idle" {
                staleDrops += 1
                return false
            }
            return true
        }

        mutating func streaming(_ g: Int64) -> Bool {
            guard current(g) else { return false }
            let changed = state != "streaming"
            state = "streaming"
            return changed
        }

        mutating func paused(_ g: Int64) -> Bool {
            guard current(g) else { return false }
            let changed = state != "paused"
            state = "paused"
            return changed
        }

        /// The stream ended for generation g. The id of a clip to finish now, "" when none was
        /// open, nil for a stale call.
        mutating func ended(_ g: Int64) -> String? {
            guard current(g) else { return nil }
            return endAll()
        }

        /// An explicit stop: same result as ended, and later callbacks are stale.
        mutating func stop() -> String {
            if state == "idle" { return "" }
            return endAll()
        }

        private mutating func endAll() -> String {
            state = "idle"
            gen += 1
            let c = clip ?? ""
            clip = nil
            clipGen += 1
            return c
        }

        /// Open a clip: its generation, or -1 when the stream does not stream or a clip is open.
        mutating func clipBegin(_ id: String) -> Int64 {
            guard state == "streaming", clip == nil else { return -1 }
            clip = id
            clipGen += 1
            return clipGen
        }

        /// Close the clip of generation cg (record_stop or its time limit). The id, or nil.
        mutating func clipEnd(_ cg: Int64) -> String? {
            guard let c = clip, cg == clipGen else {
                staleDrops += 1
                return nil
            }
            clip = nil
            clipGen += 1
            return c
        }
    }
}

/// The display view tree a page sends to `display_send`: JSON, parsed and checked here before any
/// DAT view is made. Foundation only; the Java twin is GlassesCore.DisplayTree and both accept and
/// refuse the same documents.
///
/// A node is an object with `type` and string, number or boolean attributes:
/// `box` (children; direction row|column|row_reverse|column_reverse, spacing, padding, align,
/// cross, wrap, background none|card, id; padding_top, padding_bottom, padding_start and
/// padding_end override padding on one edge), `text` (text, style heading|body|meta, color
/// primary|secondary), `button` (label, style primary|secondary|outline, icon, id, primary),
/// `buttons` (children: buttons only; align start|center|end), `icon` (name, style filled|outline)
/// and `image` (src: an https URL; size icon|fill, radius none|small|medium). Every node also takes
/// grow, shrink and self (start|center|end|stretch). Icon names are DAT's, in snake case.
enum GlassesDisplayTree {
    static let maxNodes = 64
    static let maxDepth = 8

    enum Kind: String { case box, text, button, buttons, icon, image }

    struct Attrs {
        var values: [String: String] = [:]
        subscript(_ k: String) -> String? { values[k] }
        func num(_ k: String) -> Double? { values[k].flatMap(Double.init) }
        func bool(_ k: String) -> Bool? { values[k].map { $0 == "true" } }
    }

    struct Node {
        let kind: Kind
        let attrs: Attrs
        let children: [Node]
    }

    struct Invalid: Error, CustomStringConvertible {
        let description: String
    }

    static func parse(_ json: String) throws -> Node {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw Invalid(description: "content is not a JSON object")
        }
        var count = 0
        return try node(root, depth: 1, count: &count)
    }

    private static func node(_ o: [String: Any], depth: Int, count: inout Int) throws -> Node {
        count += 1
        guard count <= maxNodes else { throw Invalid(description: "more than \(maxNodes) nodes") }
        guard depth <= maxDepth else { throw Invalid(description: "deeper than \(maxDepth) levels") }
        guard let t = o["type"] as? String, let kind = Kind(rawValue: t) else { throw Invalid(description: "unknown node type") }
        var attrs = Attrs()
        var children: [Node] = []
        for (k, v) in o.sorted(by: { $0.key < $1.key }) where k != "type" {
            if k == "children" {
                guard kind == .box || kind == .buttons, let list = v as? [Any] else { throw Invalid(description: "\(t) has no children") }
                for c in list {
                    guard let co = c as? [String: Any] else { throw Invalid(description: "a child is not an object") }
                    let child = try node(co, depth: depth + 1, count: &count)
                    if kind == .buttons && child.kind != .button { throw Invalid(description: "buttons holds buttons only") }
                    children.append(child)
                }
                continue
            }
            guard allowed(kind).contains(k) else { throw Invalid(description: "\(t) has no attribute \(k)") }
            if let b = v as? Bool, CFGetTypeID(v as CFTypeRef) == CFBooleanGetTypeID() {
                attrs.values[k] = b ? "true" : "false"
            } else if let n = v as? NSNumber {
                guard n.doubleValue.isFinite, abs(n.doubleValue) <= 10_000 else { throw Invalid(description: "\(k) is out of range") }
                attrs.values[k] = String(n.doubleValue)
            } else if let s = v as? String {
                guard s.utf16.count <= 512 else { throw Invalid(description: "\(k) is too long") }
                attrs.values[k] = s
            } else {
                throw Invalid(description: "\(k) must be a string, number or boolean")
            }
        }
        if let id = attrs["id"], !isId(id) { throw Invalid(description: "id must be 1 to 32 of a-z, A-Z, 0-9, _ and -") }
        if kind == .image, !(attrs["src"] ?? "").hasPrefix("https://") { throw Invalid(description: "image src must be an https URL") }
        return Node(kind: kind, attrs: attrs, children: children)
    }

    static func allowed(_ k: Kind) -> Set<String> {
        let common: Set<String> = ["grow", "shrink", "self"]
        switch k {
        case .box: return common.union(["direction", "spacing", "padding", "padding_top", "padding_bottom", "padding_start",
                                           "padding_end", "align", "cross", "wrap", "background", "id"])
        case .text: return common.union(["text", "style", "color"])
        case .button: return common.union(["label", "style", "icon", "id", "primary"])
        case .buttons: return common.union(["align"])
        case .icon: return common.union(["name", "style"])
        case .image: return common.union(["src", "size", "radius"])
        }
    }

    static func isId(_ s: String) -> Bool {
        !s.isEmpty && s.utf8.count <= 32 && s.utf8.allSatisfy {
            ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x41 && $0 <= 0x5a) || ($0 >= 0x61 && $0 <= 0x7a) || $0 == 0x5f || $0 == 0x2d
        }
    }
}
