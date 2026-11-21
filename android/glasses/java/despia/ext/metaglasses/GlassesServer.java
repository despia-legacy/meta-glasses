package despia.ext.metaglasses;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The page's view of the glasses camera: a tiny HTTP/1.1 server bound to 127.0.0.1 on a port the
 * system picks. Every path starts with a fresh 128-bit token, so only the page that got the URL
 * from a route can read it. Three resources:
 * <ul>
 *   <li>{@code /<token>/live.mjpg}: multipart/x-mixed-replace of JPEG frames for an {@code <img>}.
 *   Each connection is sent the newest frame only; a slow reader skips frames instead of falling
 *   behind, so the picture never lags.</li>
 *   <li>{@code /<token>/frame.jpg}: the newest frame once (503 before the first one).</li>
 *   <li>{@code /<token>/media/<id>.jpg|mp4}: a saved photo or clip, with byte ranges, so a
 *   {@code <video>} can seek.</li>
 * </ul>
 * Request parsing, the token check, ranges and the response heads are {@link GlassesCore}, shared
 * byte for byte with the iOS twin and run against the vector table.
 */
final class GlassesServer {
    /** At most this many connections are served at once; the next one is answered 503. */
    static final int MAX_CONNECTIONS = 8;
    /** A live connection waits this long for a new frame before it checks whether to end. */
    private static final long LIVE_WAIT_MS = 1000;

    private final ServerSocket socket;
    private final File mediaDir;
    final String token;
    private final AtomicInteger open = new AtomicInteger();
    private final Object lock = new Object();
    private byte[] frame;
    private long seq;
    /** Bumped by {@link #endLive}; a live connection opened under an older epoch ends. */
    private long epoch;
    private volatile boolean closed;

    private GlassesServer(ServerSocket socket, File mediaDir, String token) {
        this.socket = socket;
        this.mediaDir = mediaDir;
        this.token = token;
    }

    static GlassesServer start(File mediaDir) throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
        GlassesServer server = new GlassesServer(s, mediaDir, GlassesCore.newToken(new SecureRandom()));
        Thread t = new Thread(server::acceptLoop, "nx-glasses-http");
        t.setDaemon(true);
        t.start();
        return server;
    }

    int port() {
        return socket.getLocalPort();
    }

    /** http://127.0.0.1:<port>/<token> */
    String base() {
        return "http://127.0.0.1:" + port() + "/" + token;
    }

    /** Makes jpeg the newest frame. Called on the frame thread. */
    void publish(byte[] jpeg) {
        synchronized (lock) {
            frame = jpeg;
            seq++;
            lock.notifyAll();
        }
    }

    /** The newest frame and its sequence number (0 and null before the first). */
    Object[] latest() {
        synchronized (lock) {
            return new Object[] {seq, frame};
        }
    }

    /** Ends every open live connection (the stream stopped) and forgets the last frame. */
    void endLive() {
        synchronized (lock) {
            epoch++;
            frame = null;
            lock.notifyAll();
        }
    }

    void close() {
        closed = true;
        endLive();
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a listening socket that already failed: nothing to release.
        }
    }

    private void acceptLoop() {
        while (!closed) {
            Socket c;
            try {
                c = socket.accept();
            } catch (IOException e) {
                if (closed) return;
                continue;
            }
            if (open.incrementAndGet() > MAX_CONNECTIONS) {
                open.decrementAndGet();
                refuse(c);
                continue;
            }
            Thread t = new Thread(() -> {
                try {
                    serve(c);
                } finally {
                    open.decrementAndGet();
                    try {
                        c.close();
                    } catch (IOException ignored) {
                        // The peer already went away.
                    }
                }
            }, "nx-glasses-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void refuse(Socket c) {
        try {
            c.getOutputStream().write(ascii(GlassesCore.responseHead(503, null, 0, null)));
        } catch (IOException ignored) {
            // A refused peer that already left.
        } finally {
            try {
                c.close();
            } catch (IOException ignored) {
                // Nothing left to release.
            }
        }
    }

    private void serve(Socket c) {
        try {
            c.setSoTimeout(10000);
            c.setTcpNoDelay(true);
            String head = readHead(c.getInputStream());
            OutputStream out = c.getOutputStream();
            GlassesCore.Request req = head == null ? null : GlassesCore.parseRequest(head);
            if (req == null) {
                out.write(ascii(GlassesCore.responseHead(400, null, 0, null)));
                return;
            }
            if (req.method.equals("OPTIONS")) {
                out.write(ascii(GlassesCore.responseHead(204, null, -1, null)));
                return;
            }
            GlassesCore.Target t = GlassesCore.classify(req.path, token);
            boolean body = req.method.equals("GET");
            switch (t.kind) {
                case "live":
                    live(out, body);
                    return;
                case "frame": {
                    byte[] f = (byte[]) latest()[1];
                    if (f == null) {
                        out.write(ascii(GlassesCore.responseHead(503, null, 0, null)));
                        return;
                    }
                    out.write(ascii(GlassesCore.responseHead(200, "image/jpeg", f.length, null)));
                    if (body) out.write(f);
                    return;
                }
                case "media":
                    media(out, t, req.range, body);
                    return;
                case "forbidden":
                    out.write(ascii(GlassesCore.responseHead(403, null, 0, null)));
                    return;
                default:
                    out.write(ascii(GlassesCore.responseHead(404, null, 0, null)));
            }
        } catch (IOException ignored) {
            // The page closed the connection (an <img> replaced, a <video> seeking): nothing to do.
        }
    }

    private void live(OutputStream out, boolean body) throws IOException {
        out.write(ascii(GlassesCore.liveHead()));
        out.flush();
        if (!body) return;
        long mine;
        long sent;
        synchronized (lock) {
            mine = epoch;
            sent = frame == null ? seq : seq - 1;
        }
        while (!closed) {
            byte[] f;
            synchronized (lock) {
                while (!closed && epoch == mine && seq == sent) {
                    try {
                        lock.wait(LIVE_WAIT_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (closed || epoch != mine) return;
                f = frame;
                sent = seq;
            }
            if (f == null) continue;
            out.write(ascii(GlassesCore.partHead(f.length)));
            out.write(f);
            out.write(ascii("\r\n"));
            out.flush();
        }
    }

    private void media(OutputStream out, GlassesCore.Target t, String rangeHeader, boolean body) throws IOException {
        File f = new File(mediaDir, t.id + "." + t.ext);
        if (!f.isFile()) {
            out.write(ascii(GlassesCore.responseHead(404, null, 0, null)));
            return;
        }
        long total = f.length();
        long[] r = GlassesCore.range(rangeHeader, total);
        if (r == null) {
            out.write(ascii(GlassesCore.responseHead(416, null, 0, "bytes */" + total)));
            return;
        }
        boolean partial = r[2] == 1;
        String type = t.ext.equals("mp4") ? "video/mp4" : "image/jpeg";
        long length = r[1] - r[0] + 1;
        out.write(ascii(GlassesCore.responseHead(partial ? 206 : 200, type, length,
                partial ? "bytes " + r[0] + "-" + r[1] + "/" + total : null)));
        if (!body || length <= 0) return;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(r[0]);
            byte[] buf = new byte[64 * 1024];
            long left = length;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    /** The request head (up to the blank line, without it), or null when it is too long or cut off. */
    static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(512);
        int state = 0;
        while (b.size() <= GlassesCore.MAX_HEAD + 4) {
            int c = in.read();
            if (c < 0) return null;
            b.write(c);
            // \r\n\r\n
            if ((state == 0 || state == 2) && c == '\r') state++;
            else if ((state == 1 || state == 3) && c == '\n') state++;
            else state = c == '\r' ? 1 : 0;
            if (state == 4) {
                byte[] all = b.toByteArray();
                return new String(all, 0, all.length - 4, StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
