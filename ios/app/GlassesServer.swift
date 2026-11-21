#if DESPIA_NX_ENABLED
import Foundation
import Network

/// The page's view of the glasses camera: a small HTTP/1.1 server on 127.0.0.1, on a port the
/// system picks. Every path starts with a fresh 128-bit token, so only the page that got the URL
/// from a route can read it.
///
/// - `/<token>/live.mjpg`: multipart/x-mixed-replace of JPEG frames for an `<img>`. Each
///   connection is sent the newest frame only, and only after its previous frame was written, so a
///   slow reader skips frames and the picture never lags.
/// - `/<token>/frame.jpg`: the newest frame once (503 before the first one).
/// - `/<token>/media/<id>.jpg|mp4`: a saved photo or clip, with byte ranges for `<video>`.
///
/// Request parsing, the token check, ranges and every response head are `GlassesCore`, shared
/// byte for byte with the Android twin and run against the vector table. Everything runs on the
/// server's own serial queue.
final class GlassesServer: @unchecked Sendable {
    static let maxConnections = 8
    private static let chunk = 64 * 1024

    let token = GlassesCore.newToken()
    private let mediaDirectory: URL
    private let queue = DispatchQueue(label: "nx.glasses.http")
    private let listener: NWListener
    private var connections: [ObjectIdentifier: NWConnection] = [:]
    private var headDeadlines: [ObjectIdentifier: DispatchWorkItem] = [:]
    private var live: [ObjectIdentifier: LiveClient] = [:]
    private var frame: Data?
    private var seq: Int64 = 0
    private(set) var port: UInt16 = 0

    private final class LiveClient {
        let connection: NWConnection
        var sending = false
        var sent: Int64

        init(_ connection: NWConnection, sent: Int64) {
            self.connection = connection
            self.sent = sent
        }
    }

    /// Binds 127.0.0.1 on an ephemeral port and waits (at most 3 s) until it listens.
    init(mediaDirectory: URL) throws {
        self.mediaDirectory = mediaDirectory
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(.loopback), port: .any)
        params.allowLocalEndpointReuse = true
        listener = try NWListener(using: params)
        let ready = DispatchSemaphore(value: 0)
        var failed = false
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.port = self?.listener.port?.rawValue ?? 0
                ready.signal()
            case .failed, .cancelled:
                failed = true
                ready.signal()
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] c in self?.accept(c) }
        listener.start(queue: queue)
        if ready.wait(timeout: .now() + 3) == .timedOut || failed || port == 0 {
            listener.cancel()
            throw NSError(domain: "GlassesServer", code: 1, userInfo: [NSLocalizedDescriptionKey: "the loopback listener did not start"])
        }
    }

    /// http://127.0.0.1:<port>/<token>
    var base: String { "http://127.0.0.1:\(port)/\(token)" }

    /// Makes jpeg the newest frame. Any thread.
    func publish(_ jpeg: Data) {
        queue.async {
            self.frame = jpeg
            self.seq += 1
            for client in self.live.values { self.pump(client) }
        }
    }

    /// The newest frame and its sequence number.
    func latest() -> (Int64, Data?) {
        queue.sync { (seq, frame) }
    }

    /// Ends every live connection (the stream stopped) and forgets the last frame.
    func endLive() {
        queue.async {
            self.frame = nil
            for client in self.live.values { client.connection.cancel() }
            self.live.removeAll()
        }
    }

    func close() {
        queue.async {
            self.listener.cancel()
            for c in self.connections.values { c.cancel() }
            self.connections.removeAll()
            for deadline in self.headDeadlines.values { deadline.cancel() }
            self.headDeadlines.removeAll()
            self.live.removeAll()
            self.frame = nil
        }
    }

    // MARK: - Connections (server queue)

    private func accept(_ c: NWConnection) {
        let id = ObjectIdentifier(c)
        guard connections.count < Self.maxConnections else {
            c.start(queue: queue)
            send(c, Data(GlassesCore.responseHead(503, contentType: nil, length: 0, contentRange: nil).utf8), close: true)
            return
        }
        connections[id] = c
        // Bound the entire request head, not each read. Completed live streams have no such timer.
        let deadline = DispatchWorkItem { [weak self, weak c] in
            guard let self, let c, self.headDeadlines.removeValue(forKey: id) != nil else { return }
            self.connections[id] = nil
            c.cancel()
        }
        headDeadlines[id] = deadline
        queue.asyncAfter(deadline: .now() + 10, execute: deadline)
        c.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled:
                self?.connections[id] = nil
                self?.headDeadlines.removeValue(forKey: id)?.cancel()
                self?.live[id] = nil
            default:
                break
            }
        }
        c.start(queue: queue)
        readHead(c, Data())
    }

    private func readHead(_ c: NWConnection, _ buffer: Data) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, done, error in
            guard let self, self.headDeadlines[ObjectIdentifier(c)] != nil else { return }
            var buf = buffer
            if let data { buf.append(data) }
            if let end = buf.range(of: Data("\r\n\r\n".utf8)) {
                self.headDeadlines.removeValue(forKey: ObjectIdentifier(c))?.cancel()
                let head = String(decoding: buf[buf.startIndex..<end.lowerBound], as: UTF8.self)
                self.respond(c, head: head)
            } else if buf.count > GlassesCore.maxHead + 4 || done || error != nil {
                self.headDeadlines.removeValue(forKey: ObjectIdentifier(c))?.cancel()
                self.send(c, Data(GlassesCore.responseHead(400, contentType: nil, length: 0, contentRange: nil).utf8), close: true)
            } else {
                self.readHead(c, buf)
            }
        }
    }

    private func head(_ status: Int, _ type: String? = nil, _ length: Int64 = 0, _ range: String? = nil) -> Data {
        Data(GlassesCore.responseHead(status, contentType: type, length: length, contentRange: range).utf8)
    }

    private func respond(_ c: NWConnection, head text: String) {
        guard let req = GlassesCore.parseRequest(text) else { return send(c, head(400), close: true) }
        if req.method == "OPTIONS" { return send(c, head(204, nil, -1), close: true) }
        let body = req.method == "GET"
        let target = GlassesCore.classify(req.path, token: token)
        switch target.kind {
        case "live":
            send(c, Data(GlassesCore.liveHead().utf8), close: !body)
            guard body else { return }
            let client = LiveClient(c, sent: frame == nil ? seq : seq - 1)
            live[ObjectIdentifier(c)] = client
            pump(client)
        case "frame":
            guard let f = frame else { return send(c, head(503), close: true) }
            var out = head(200, "image/jpeg", Int64(f.count))
            if body { out.append(f) }
            send(c, out, close: true)
        case "media":
            media(c, target, range: req.range, body: body)
        case "forbidden":
            send(c, head(403), close: true)
        default:
            send(c, head(404), close: true)
        }
    }

    /// Sends the newest frame to a live client that is not mid-send and has not had it yet.
    private func pump(_ client: LiveClient) {
        guard !client.sending, let f = frame, seq != client.sent else { return }
        client.sending = true
        client.sent = seq
        var part = Data(GlassesCore.partHead(f.count).utf8)
        part.append(f)
        part.append(Data("\r\n".utf8))
        client.connection.send(content: part, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            client.sending = false
            if error != nil {
                client.connection.cancel()
                self.live[ObjectIdentifier(client.connection)] = nil
                return
            }
            self.pump(client)
        })
    }

    private func media(_ c: NWConnection, _ t: GlassesCore.Target, range header: String?, body: Bool) {
        guard let id = t.id, let ext = t.ext else { return send(c, head(404), close: true) }
        let url = mediaDirectory.appendingPathComponent("\(id).\(ext)")
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              (attrs[.type] as? FileAttributeType) == .typeRegular,
              let size = (attrs[.size] as? NSNumber)?.int64Value,
              let handle = try? FileHandle(forReadingFrom: url) else { return send(c, head(404), close: true) }
        guard let r = GlassesCore.range(header, total: size) else {
            try? handle.close()
            return send(c, head(416, nil, 0, "bytes */\(size)"), close: true)
        }
        let type = ext == "mp4" ? "video/mp4" : "image/jpeg"
        let length = r.1 - r.0 + 1
        let h = head(r.2 ? 206 : 200, type, length, r.2 ? "bytes \(r.0)-\(r.1)/\(size)" : nil)
        guard body, length > 0 else {
            try? handle.close()
            return send(c, h, close: true)
        }
        do {
            try handle.seek(toOffset: UInt64(r.0))
        } catch {
            try? handle.close()
            return send(c, head(404), close: true)
        }
        c.send(content: h, completion: .contentProcessed { [weak self] _ in
            self?.stream(c, handle, left: length)
        })
    }

    private func stream(_ c: NWConnection, _ handle: FileHandle, left: Int64) {
        guard left > 0, let data = try? handle.read(upToCount: Int(min(Int64(Self.chunk), left))), !data.isEmpty else {
            try? handle.close()
            return send(c, Data(), close: true)
        }
        c.send(content: data, completion: .contentProcessed { [weak self] error in
            if error != nil {
                try? handle.close()
                c.cancel()
                return
            }
            self?.stream(c, handle, left: left - Int64(data.count))
        })
    }

    private func send(_ c: NWConnection, _ data: Data, close: Bool) {
        c.send(content: data, isComplete: close, completion: .contentProcessed { _ in
            if close { c.cancel() }
        })
    }
}
#endif
