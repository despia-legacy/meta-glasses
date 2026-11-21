package despia.ext.metaglasses;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Every decision of the Meta glasses extension that does not need Android: the loopback HTTP
 * request rules, the byte-range rule, the response heads, the stream parameters and the
 * session and clip state machine. No android.* or androidx.* import, so the shared rules run
 * against this file directly on a plain JVM. The Swift
 * twin is ios/app/GlassesCore.swift; both must give the same answer for every vector.
 */
public final class GlassesCore {
    private GlassesCore() {
    }

    // ---------------------------------------------------------------- loopback HTTP

    /** Longest request head (request line and headers) the server reads before refusing. */
    public static final int MAX_HEAD = 8192;
    /** Longest request target accepted. */
    public static final int MAX_TARGET = 512;
    /** The multipart boundary of the live stream. */
    public static final String BOUNDARY = "nxframe";

    /** A parsed request: method, path without query, and the raw Range header or null. */
    public static final class Request {
        public final String method;
        public final String path;
        public final String range;

        Request(String method, String path, String range) {
            this.method = method;
            this.path = path;
            this.range = range;
        }
    }

    /**
     * Parses a request head (everything before the blank line, CRLF line ends). Returns null for
     * anything that is not a well-formed GET, HEAD or OPTIONS request with an origin-form target.
     */
    public static Request parseRequest(String head) {
        if (head == null || head.length() > MAX_HEAD) return null;
        String[] lines = head.split("\r\n", -1);
        String[] parts = lines[0].split(" ", -1);
        if (parts.length != 3) return null;
        String method = parts[0];
        if (!method.equals("GET") && !method.equals("HEAD") && !method.equals("OPTIONS")) return null;
        if (!parts[2].equals("HTTP/1.1") && !parts[2].equals("HTTP/1.0")) return null;
        String target = parts[1];
        if (target.isEmpty() || target.length() > MAX_TARGET || target.charAt(0) != '/') return null;
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c <= 0x20 || c >= 0x7f) return null;
        }
        int q = target.indexOf('?');
        String path = q >= 0 ? target.substring(0, q) : target;
        String range = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) return null;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (name.equals("range")) {
                if (range != null) return null;
                range = line.substring(colon + 1).trim();
            }
        }
        return new Request(method, path, range);
    }

    /** What a path asks for. */
    public static final class Target {
        /** live, frame, media, forbidden or not_found. */
        public final String kind;
        /** For media: the id and the extension (jpg or mp4). */
        public final String id;
        public final String ext;

        Target(String kind, String id, String ext) {
            this.kind = kind;
            this.id = id;
            this.ext = ext;
        }
    }

    /**
     * Classifies a path against the current stream token. The token is the first segment and is
     * compared in constant time; a wrong or missing token is forbidden before anything else is
     * looked at, so the path does not reveal which files exist.
     */
    public static Target classify(String path, String token) {
        if (path == null || !path.startsWith("/")) return new Target("not_found", null, null);
        int slash = path.indexOf('/', 1);
        String first = slash < 0 ? path.substring(1) : path.substring(1, slash);
        if (!isToken(token) || !constantTimeEquals(first, token)) return new Target("forbidden", null, null);
        String rest = slash < 0 ? "" : path.substring(slash);
        if (rest.equals("/live.mjpg")) return new Target("live", null, null);
        if (rest.equals("/frame.jpg")) return new Target("frame", null, null);
        if (rest.startsWith("/media/")) {
            String file = rest.substring("/media/".length());
            int dot = file.lastIndexOf('.');
            if (dot > 0) {
                String id = file.substring(0, dot);
                String ext = file.substring(dot + 1);
                if (isMediaId(id) && (ext.equals("jpg") || ext.equals("mp4"))) return new Target("media", id, ext);
            }
        }
        return new Target("not_found", null, null);
    }

    /** A stream token: exactly 32 lower-case hex digits (128 bits). */
    public static boolean isToken(String s) {
        if (s == null || s.length() != 32) return false;
        for (int i = 0; i < 32; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** A media id: 1 to 32 characters of a-z and 0-9. */
    public static boolean isMediaId(String s) {
        if (s == null || s.isEmpty() || s.length() > 32) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z'))) return false;
        }
        return true;
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    /** A new random token from a cryptographic source. */
    public static String newToken(SecureRandom random) {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    /**
     * The byte range to send for a Range header and a file size: {start, end, partial}, start and
     * end inclusive, partial 1 when the answer is 206 Partial Content with a Content-Range and 0
     * when it is 200 with the whole file. A null header, one that is not a single satisfiable-form
     * bytes range, or an empty file: the whole file ({0, total - 1, 0}, or {0, -1, 0} for an empty
     * file); RFC 9110 section 14.2 lets a server ignore a Range it does not support. An
     * unsatisfiable single range: null (answer 416).
     */
    public static long[] range(String header, long total) {
        long[] whole = {0, total - 1, 0};
        if (header == null || total <= 0) return whole;
        String h = header.trim();
        if (!h.startsWith("bytes=")) return whole;
        String spec = h.substring(6).trim();
        if (spec.indexOf(',') >= 0) return whole;
        int dash = spec.indexOf('-');
        if (dash < 0) return whole;
        String a = spec.substring(0, dash).trim();
        String b = spec.substring(dash + 1).trim();
        if (!digits(a) || !digits(b) || (a.isEmpty() && b.isEmpty())) return whole;
        if (a.isEmpty()) {
            long n = Long.parseLong(b);
            if (n == 0) return null;
            return new long[]{Math.max(0, total - n), total - 1, 1};
        }
        long start = Long.parseLong(a);
        if (start >= total) return null;
        long end = b.isEmpty() ? total - 1 : Math.min(Long.parseLong(b), total - 1);
        if (end < start) return whole;
        return new long[]{start, end, 1};
    }

    private static boolean digits(String s) {
        if (s.length() > 18) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    /** Status line text for the codes the server sends. */
    public static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 416: return "Range Not Satisfiable";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    /**
     * A response head, byte for byte the same as the Swift twin's. The page is on another origin
     * (its own https site), so every answer allows any origin without credentials: the token in
     * the path is the capability. Access-Control-Allow-Private-Network answers a Private Network
     * Access preflight. Nothing is cached.
     */
    public static String responseHead(int status, String contentType, long length, String contentRange) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n");
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n");
        if (contentRange != null) sb.append("Content-Range: ").append(contentRange).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: Range\r\n");
        sb.append("Access-Control-Allow-Private-Network: true\r\n");
        sb.append("Connection: close\r\n\r\n");
        return sb.toString();
    }

    /** The head of a live stream response; the body is a run of {@link #partHead} parts. */
    public static String liveHead() {
        return responseHead(200, "multipart/x-mixed-replace; boundary=" + BOUNDARY, -1, null);
    }

    /** One JPEG part of the live stream; the JPEG bytes and a CRLF follow it. */
    public static String partHead(int length) {
        return "--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + length + "\r\n\r\n";
    }

    // ---------------------------------------------------------------- stream parameters

    /** Frame rates DAT accepts (Meta, "Camera Streaming": 2, 7, 15, 24, 30). */
    public static final int[] FRAME_RATES = {2, 7, 15, 24, 30};

    /** The accepted rate nearest to the asked one, ties to the lower; never a rate DAT refuses. */
    public static int frameRate(int asked) {
        int best = FRAME_RATES[0];
        for (int r : FRAME_RATES) if (Math.abs(r - asked) < Math.abs(best - asked)) best = r;
        return best;
    }

    /** Frame size of a quality (Meta, "Camera Streaming"): width x height, portrait. */
    public static int[] frameSize(String quality) {
        switch (quality) {
            case "high": return new int[]{720, 1280};
            case "low": return new int[]{360, 640};
            default: return new int[]{504, 896};
        }
    }

    // ---------------------------------------------------------------- session and clip state

    /**
     * The stream's state machine, on the main thread. Each start gets a new generation; every
     * SDK callback carries the generation it was made for, and a callback for any other
     * generation changes nothing (it is counted in staleDrops). A clip is open only while the
     * stream streams, and at most one at a time.
     */
    public static final class Session {
        /** idle, starting, streaming, paused. */
        public String state = "idle";
        public long gen = 0;
        public int staleDrops = 0;
        /** The open clip's id, or null. */
        public String clip = null;
        /** The clip generation; a timer or writer callback for another one changes nothing. */
        public long clipGen = 0;

        /** A start: returns the new generation, or -1 when a stream already starts or runs. */
        public long begin() {
            if (!state.equals("idle")) return -1;
            gen++;
            state = "starting";
            return gen;
        }

        private boolean current(long g) {
            if (g != gen || state.equals("idle")) {
                staleDrops++;
                return false;
            }
            return true;
        }

        /** The SDK reported streaming for generation g. True when this is news for the page. */
        public boolean streaming(long g) {
            if (!current(g)) return false;
            boolean changed = !state.equals("streaming");
            state = "streaming";
            return changed;
        }

        /** The SDK paused the stream (a tap on the glasses, hinges closed). */
        public boolean paused(long g) {
            if (!current(g)) return false;
            boolean changed = !state.equals("paused");
            state = "paused";
            return changed;
        }

        /**
         * The stream ended for generation g (stopped, closed or a critical error). Returns the id
         * of a clip that must be finished now, or "" when none was open; null for a stale call.
         */
        public String ended(long g) {
            if (!current(g)) return null;
            return endAll();
        }

        /** An explicit stop: same result as {@link #ended}, and later callbacks are stale. */
        public String stop() {
            if (state.equals("idle")) return "";
            return endAll();
        }

        private String endAll() {
            state = "idle";
            gen++;
            String c = clip == null ? "" : clip;
            clip = null;
            clipGen++;
            return c;
        }

        /** Open a clip: its generation, or -1 when the stream does not stream or a clip is open. */
        public long clipBegin(String id) {
            if (!state.equals("streaming") || clip != null) return -1;
            clip = id;
            clipGen++;
            return clipGen;
        }

        /** Close the clip of generation cg (record_stop or its time limit). The id, or null. */
        public String clipEnd(long cg) {
            if (clip == null || cg != clipGen) {
                staleDrops++;
                return null;
            }
            String c = clip;
            clip = null;
            clipGen++;
            return c;
        }
    }
}
