package dev.musicbox;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;

import javax.net.ssl.SSLException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Tải âm thanh từ link trực tiếp, vừa tải vừa giải mã (mp3/ogg/wav) thành PCM 16-bit mono 48kHz
 * và ghi dần vào {@link PcmBuffer} để có thể phát ngay khi mới có vài giây dữ liệu.
 *
 * <p>Chống lỗi cho mọi host (catbox, github.io, archive.org, ...):
 * <ul>
 *   <li>Link luôn được chuẩn hóa (mã hóa %XX ký tự tiếng Việt, dấu cách, [] | {} ..., punycode tên miền)
 *       nên không còn HTTP 400 do URL sai cú pháp.</li>
 *   <li>Nếu máy chủ vẫn trả 400/403/404/405/406/415/429/5xx hoặc 200 rỗng, tự thử lại bằng các
 *       "kiểu kết nối" khác (header trình duyệt, header tối giản, curl, URL dạng khác...) và nhớ kiểu
 *       đã chạy được cho từng host.</li>
 *   <li>Không tin Content-Type: định dạng được nhận diện bằng nội dung (magic bytes).</li>
 *   <li>Tự xử lý chuyển hướng (kể cả Location có ký tự lạ / tương đối), gzip, 206.</li>
 * </ul>
 *
 * <p>Tiết kiệm dữ liệu mạng:
 * <ul>
 *   <li>Cache file gốc trên đĩa ({@link AudioCache}); hết hạn thì hỏi lại bằng ETag/Last-Modified (304).</li>
 *   <li>Ngắt tải ngay khi người chơi dừng nhạc ({@link Ctx}).</li>
 *   <li>{@link #probe} kiểm tra link chỉ tốn vài KB (Range đầu file) thay vì tải cả bài.</li>
 * </ul>
 */
public final class AudioLoader {

    public static final int SAMPLE_RATE = 48000;

    private static final Logger LOG = Logger.getLogger("MusicBox");
    private static final int MAX_REDIRECTS = 8;
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0 Safari/537.36";

    public record Options(long maxBytes, int maxSeconds, float volume,
                          int connectTimeoutMs, int readTimeoutMs, boolean blockPrivate,
                          String userAgent) {
    }

    private AudioLoader() {
    }

    // ------------------------------------------------------------ validation

    /** @return null nếu hợp lệ, ngược lại là key thông báo lỗi trong config. */
    public static String validateLink(String link, List<String> allowedDomains) {
        if (link == null || link.length() > 2048) return "invalid-link";
        String host;
        try {
            host = hostOf(normalizeUrl(link, UrlForm.NORMALIZED));
        } catch (IOException e) {
            return "invalid-link";
        }
        if (host == null || host.isBlank()) return "invalid-link";

        if (allowedDomains != null && !allowedDomains.isEmpty()) {
            String h = host.toLowerCase(Locale.ROOT);
            boolean ok = false;
            for (String d : allowedDomains) {
                String dd = d.toLowerCase(Locale.ROOT).trim();
                if (!dd.isEmpty() && (h.equals(dd) || h.endsWith("." + dd))) {
                    ok = true;
                    break;
                }
            }
            if (!ok) return "domain-not-allowed";
        }
        return null;
    }

    // ------------------------------------------------------------ URL helpers

    /** Các cách dựng URL khác nhau; thử lần lượt nếu máy chủ trả 400. */
    enum UrlForm {
        /** Giữ nguyên %XX hợp lệ, mã hóa ký tự không hợp lệ (tiếng Việt, dấu cách, [] |...). */
        NORMALIZED,
        /** Chuẩn hóa chặt: giải mã %XX không cần thiết, mã hóa lại mọi ký tự ngoài unreserved. */
        CANONICAL,
        /** Chỉ mã hóa ký tự bắt buộc (dấu cách, ký tự không phải ASCII), giữ [] | {} ^ nguyên dạng. */
        LENIENT
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static final Pattern GH_BLOB = Pattern.compile(
            "^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/blob/(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DRIVE_FILE = Pattern.compile(
            "^https?://drive\\.google\\.com/file/d/([\\w-]+).*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DRIVE_OPEN = Pattern.compile(
            "^https?://drive\\.google\\.com/open\\?(?:.*&)?id=([\\w-]+).*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROPBOX = Pattern.compile(
            "^https?://(?:www\\.)?dropbox\\.com/.*$", Pattern.CASE_INSENSITIVE);

    /** Đổi link "trang xem" của một số host sang link tải trực tiếp. */
    static String rewriteKnownHosts(String link) {
        String l = cleanLink(link);
        Matcher m = GH_BLOB.matcher(l);
        if (m.matches()) {
            return "https://raw.githubusercontent.com/" + m.group(1) + "/" + m.group(2) + "/" + m.group(3);
        }
        m = DRIVE_FILE.matcher(l);
        if (m.matches()) return "https://drive.google.com/uc?export=download&id=" + m.group(1);
        m = DRIVE_OPEN.matcher(l);
        if (m.matches()) return "https://drive.google.com/uc?export=download&id=" + m.group(1);
        if (DROPBOX.matcher(l).matches()) {
            if (l.contains("dl=0")) return l.replace("dl=0", "dl=1");
            if (!l.contains("dl=")) return l + (l.contains("?") ? "&" : "?") + "dl=1";
        }
        return l;
    }

    /** Bỏ ký tự điều khiển / zero-width / dấu &lt;&gt; bao quanh. */
    private static String cleanLink(String raw) {
        String s = raw == null ? "" : raw.trim();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || c == '\u200B' || c == '\u200C'
                    || c == '\u200D' || c == '\uFEFF') {
                continue;
            }
            sb.append(c);
        }
        s = sb.toString().trim();
        if (s.length() > 2 && s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length() - 1).trim();
        return s;
    }

    /** Chuẩn hóa một URL http/https tuyệt đối thành dạng hợp lệ để gửi đi. */
    static String normalizeUrl(String raw, UrlForm form) throws IOException {
        String s = cleanLink(raw);
        int sch = s.indexOf("://");
        if (sch <= 0) throw new BadLinkException("link không hợp lệ");
        String scheme = s.substring(0, sch).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new BadLinkException("link không hợp lệ");

        String rest = s.substring(sch + 3);
        int hash = rest.indexOf('#');
        if (hash >= 0) rest = rest.substring(0, hash);

        int end = -1;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '\\') {
                end = i;
                break;
            }
        }
        String authority = end < 0 ? rest : rest.substring(0, end);
        String tail = end < 0 ? "" : rest.substring(end);
        if (tail.startsWith("?")) tail = "/" + tail;

        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);

        String host = authority;
        int port = -1;
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close < 0) throw new BadLinkException("link không hợp lệ");
            String after = host.substring(close + 1);
            host = host.substring(0, close + 1);
            if (after.startsWith(":") && after.length() > 1) port = parsePort(after.substring(1));
        } else {
            int colon = host.lastIndexOf(':');
            if (colon >= 0) {
                String p = host.substring(colon + 1);
                host = host.substring(0, colon);
                if (!p.isEmpty()) port = parsePort(p);
            }
            host = host.toLowerCase(Locale.ROOT);
            while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            try {
                host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                throw new BadLinkException("tên miền không hợp lệ");
            }
            if (host.isEmpty() || !host.matches("[a-z0-9._-]+")) throw new BadLinkException("tên miền không hợp lệ");
        }

        String path = tail;
        String query = null;
        int q = tail.indexOf('?');
        if (q >= 0) {
            path = tail.substring(0, q);
            query = tail.substring(q + 1);
        }
        path = path.replace('\\', '/');
        if (path.isEmpty()) path = "/";
        path = encodePart(path, form, false);

        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (port > 0 && !(scheme.equals("http") && port == 80) && !(scheme.equals("https") && port == 443)) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (query != null) {
            query = encodePart(query, form, true);
            if (!query.isEmpty()) sb.append('?').append(query);
        }
        return sb.toString();
    }

    private static int parsePort(String p) throws IOException {
        try {
            int v = Integer.parseInt(p);
            if (v < 1 || v > 65535) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw new BadLinkException("cổng không hợp lệ");
        }
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    private static boolean isAllowed(char c, UrlForm form, boolean query) {
        if (isUnreserved(c)) return true;
        switch (c) {
            case '/', ':', '@', '+', ',', ';', '=', '&':
                return true;
            case '?':
                return query;
            case '!', '$', '\'', '(', ')', '*':
                return form != UrlForm.CANONICAL;
            case '[', ']', '|', '{', '}', '^':
                return form == UrlForm.LENIENT;
            default:
                return false;
        }
    }

    private static String encodePart(String s, UrlForm form, boolean query) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                int val = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                if (form == UrlForm.CANONICAL && isUnreserved(val)) {
                    out.append((char) val);
                } else if (form == UrlForm.CANONICAL) {
                    out.append('%').append(Character.toUpperCase(s.charAt(i + 1)))
                            .append(Character.toUpperCase(s.charAt(i + 2)));
                } else {
                    out.append(c).append(s, i + 1, i + 3);
                }
                i += 3;
                continue;
            }
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x80 && isAllowed((char) cp, form, query)) {
                out.append((char) cp);
                continue;
            }
            byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) out.append('%').append(HEX[(b >> 4) & 15]).append(HEX[b & 15]);
        }
        return out.toString();
    }

    /**
     * Java đọc header theo Latin-1. Nhiều host gửi Location chứa UTF-8 thô (vd: tên file tiếng Việt),
     * nên nếu các byte đó là UTF-8 hợp lệ thì giải mã lại cho đúng, tránh mã hóa 2 lần.
     */
    private static String fixHeaderEncoding(String s) {
        boolean high = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0xFF) return s;
            if (c >= 0x80) high = true;
        }
        if (!high) return s;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1))).toString();
        } catch (CharacterCodingException e) {
            return s;
        }
    }

    /** Ghép Location (có thể tương đối, có thể chứa ký tự lạ) với URL hiện tại. */
    private static String resolveLocation(String base, String loc) throws IOException {
        String l = cleanLink(fixHeaderEncoding(loc));
        if (l.isEmpty()) throw new IOException("chuyển hướng không có địa chỉ đích");
        if (l.regionMatches(true, 0, "http://", 0, 7) || l.regionMatches(true, 0, "https://", 0, 8)) return l;
        URI b = URI.create(base);
        if (l.startsWith("//")) return b.getScheme() + ":" + l;

        int hash = l.indexOf('#');
        if (hash >= 0) l = l.substring(0, hash);
        String path = l;
        String query = null;
        int q = l.indexOf('?');
        if (q >= 0) {
            path = l.substring(0, q);
            query = l.substring(q + 1);
        }
        String enc = encodePart(path.replace('\\', '/'), UrlForm.NORMALIZED, false);
        if (query != null) enc += "?" + encodePart(query, UrlForm.NORMALIZED, true);
        try {
            return b.resolve(enc).toString();
        } catch (IllegalArgumentException e) {
            throw new IOException("chuyển hướng không hợp lệ");
        }
    }

    /** Lấy host từ URL đã chuẩn hóa (không phụ thuộc java.net.URI vì URI từ chối '_' trong host). */
    static String hostOf(String url) {
        int s = url.indexOf("://");
        if (s < 0) return null;
        String rest = url.substring(s + 3);
        if (rest.startsWith("[")) {
            int c = rest.indexOf(']');
            return c < 0 ? null : rest.substring(0, c + 1);
        }
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == ':') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end);
    }

    private static String originOf(String url) {
        int s = url.indexOf("://");
        if (s < 0) return null;
        int slash = url.indexOf('/', s + 3);
        return slash < 0 ? url : url.substring(0, slash);
    }

    // ----------------------------------------------------------------- loading

    /** Bối cảnh một lần tải: cờ hủy (khi người chơi dừng nhạc) và cache trên đĩa (có thể null). */
    public record Ctx(BooleanSupplier cancelled, AudioCache cache) {
        public static final Ctx NONE = new Ctx(null, null);

        public Ctx {
            if (cancelled == null) cancelled = () -> false;
        }

        boolean isCancelled() {
            return cancelled.getAsBoolean();
        }

        void check() throws CancelledException {
            if (cancelled.getAsBoolean()) throw new CancelledException();
        }
    }

    /** Người chơi đã dừng nhạc: bỏ tải ngay để khỏi tốn dữ liệu mạng. */
    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("đã hủy");
        }
    }

    /** Tải toàn bộ rồi trả về mảng PCM (dùng cho test). */
    public static short[] load(String url, Options opt) throws IOException, UnsupportedAudioFileException {
        return load(url, opt, Ctx.NONE);
    }

    public static short[] load(String url, Options opt, Ctx ctx) throws IOException, UnsupportedAudioFileException {
        PcmBuffer buf = new PcmBuffer();
        streamInto(url, opt, buf, ctx);
        buf.finish();
        return buf.snapshot();
    }

    /** Máy chủ trả HTTP 200 nhưng không có dữ liệu. */
    private static final class EmptyBodyException extends IOException {
        EmptyBodyException() {
            super("máy chủ trả về dữ liệu rỗng");
        }
    }

    private static final class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code) {
            super(describeHttp(code));
            this.code = code;
        }
    }

    static String describeHttp(int code) {
        String why = switch (code) {
            case 400 -> "link không được máy chủ chấp nhận";
            case 401, 403 -> "máy chủ từ chối truy cập";
            case 404, 410 -> "file không tồn tại hoặc đã bị xóa";
            case 429 -> "máy chủ giới hạn lượt tải, hãy thử lại sau";
            default -> code >= 500 ? "máy chủ đang gặp lỗi" : "máy chủ từ chối yêu cầu";
        };
        return why + " (HTTP " + code + ")";
    }

    /** Link trả về trang web / văn bản thay vì file âm thanh. */
    private static final class NotAudioException extends IOException {
        NotAudioException(String msg) {
            super(msg);
        }
    }

    private static final class TooLargeException extends IOException {
        TooLargeException() {
            super("file quá lớn");
        }
    }

    private static final class BlockedException extends IOException {
        BlockedException() {
            super("địa chỉ bị chặn");
        }
    }

    /** Link sai cú pháp / tên miền không tồn tại: thử lại cũng vô ích. */
    private static final class BadLinkException extends IOException {
        BadLinkException(String msg) {
            super(msg);
        }
    }

    /** Host trả 304: bản cache vẫn dùng được. */
    private static final class NotModifiedException extends IOException {
        NotModifiedException() {
            super("not modified");
        }
    }

    private static boolean retryable(IOException e) {
        if (e instanceof TooLargeException || e instanceof BlockedException || e instanceof BadLinkException
                || e instanceof CancelledException) {
            return false;
        }
        if (e instanceof EmptyBodyException || e instanceof NotAudioException) return true;
        if (e instanceof HttpStatusException h) {
            int c = h.code;
            return c == 400 || c == 403 || c == 404 || c == 405 || c == 406 || c == 408 || c == 415
                    || c == 416 || c == 421 || c == 429 || c >= 500;
        }
        return e instanceof SocketException || e instanceof SocketTimeoutException
                || e instanceof EOFException || e instanceof SSLException;
    }

    /**
     * Lỗi này là chắc chắn (link hỏng / không phải nhạc / quá lớn...) hay chỉ tạm thời (mạng chập chờn,
     * host quá tải)? Dùng khi kiểm tra link lúc /musicbox add.
     */
    public static boolean isDefinitiveFailure(Throwable e) {
        if (e instanceof UnsupportedAudioFileException || e instanceof NotAudioException
                || e instanceof TooLargeException || e instanceof BlockedException
                || e instanceof BadLinkException) {
            return true;
        }
        if (e instanceof HttpStatusException h) {
            return !(h.code == 408 || h.code == 429 || h.code >= 500);
        }
        return false;
    }

    private enum Profile {BROWSER, MINIMAL, CURL, PLAIN}

    private record Strategy(Profile profile, UrlForm form) {
    }

    /** Thứ tự các cách kết nối sẽ thử khi gặp lỗi có thể khắc phục được. */
    private static final List<Strategy> PLAN = List.of(
            new Strategy(Profile.BROWSER, UrlForm.NORMALIZED),
            new Strategy(Profile.MINIMAL, UrlForm.NORMALIZED),
            new Strategy(Profile.CURL, UrlForm.NORMALIZED),
            new Strategy(Profile.BROWSER, UrlForm.CANONICAL),
            new Strategy(Profile.MINIMAL, UrlForm.LENIENT),
            new Strategy(Profile.PLAIN, UrlForm.NORMALIZED));

    /** Host nào đã chạy được bằng cách kết nối số mấy thì lần sau thử cách đó trước (đỡ tốn request thừa). */
    private static final ConcurrentHashMap<String, Integer> PREFERRED = new ConcurrentHashMap<>();

    private static List<Strategy> orderFor(String host) {
        Integer p = host == null ? null : PREFERRED.get(host);
        if (p == null || p <= 0 || p >= PLAN.size()) return PLAN;
        List<Strategy> r = new ArrayList<>(PLAN.size());
        r.add(PLAN.get(p));
        for (int i = 0; i < PLAN.size(); i++) if (i != p) r.add(PLAN.get(i));
        return r;
    }

    private static void remember(String host, Strategy st) {
        if (host == null) return;
        int i = PLAN.indexOf(st);
        if (i <= 0) {
            PREFERRED.remove(host);
        } else {
            if (PREFERRED.size() > 512) PREFERRED.clear();
            PREFERRED.put(host, i);
        }
    }

    /** Điều kiện hỏi lại host (If-None-Match / If-Modified-Since). */
    private record Validators(String etag, String lastModified) {
        static Validators of(AudioCache.Entry e) {
            if (e == null || (e.etag() == null && e.lastModified() == null)) return null;
            return new Validators(e.etag(), e.lastModified());
        }
    }

    /** Kết nối đã mở + thông tin phản hồi. */
    private record Opened(InputStream in, String info, String etag, String lastModified, boolean cacheable) {
    }

    /** Đếm số lần thất bại để không thử mãi một kiểu lỗi. */
    private static final class Budget {
        private int notAudio;
        private int net;
        private int overloaded;

        /** Ném lại e nếu không nên thử tiếp; nếu không thì nghỉ một chút rồi trả về. */
        void failed(IOException e) throws IOException {
            if (!retryable(e)) throw e;
            if (e instanceof NotAudioException) {
                if (++notAudio >= 2) throw e;
            } else if (e instanceof HttpStatusException h) {
                // Host quá tải/lỗi (429, 5xx): đổi header cũng không giúp gì, đừng dồn thêm request
                if ((h.code == 429 || h.code >= 500) && ++overloaded >= 2) throw e;
            } else if (!(e instanceof EmptyBodyException)) {
                if (++net >= 2) throw e;
            }
            pause(e);
        }
    }

    /** Tải và giải mã, ghi dần vào buf (không cache, không hủy). */
    public static void streamInto(String url, Options opt, PcmBuffer buf)
            throws IOException, UnsupportedAudioFileException {
        streamInto(url, opt, buf, Ctx.NONE);
    }

    /**
     * Tải và giải mã, ghi dần vào buf. Tự thử lại bằng nhiều cách kết nối khác nhau nếu chưa có mẫu
     * âm thanh nào được ghi. Người gọi chịu trách nhiệm gọi buf.finish() / buf.fail().
     * Ném {@link CancelledException} nếu ctx báo đã hủy.
     */
    public static void streamInto(String url, Options opt, PcmBuffer buf, Ctx ctx)
            throws IOException, UnsupportedAudioFileException {
        // javax.sound (OGG/WAV) tìm decoder qua ServiceLoader dùng context classloader,
        // nên trỏ về classloader của plugin để thấy vorbisspi đã được shade.
        Thread thread = Thread.currentThread();
        ClassLoader old = thread.getContextClassLoader();
        thread.setContextClassLoader(AudioLoader.class.getClassLoader());
        try {
            runPlan(url, opt, buf, ctx == null ? Ctx.NONE : ctx);
        } finally {
            thread.setContextClassLoader(old);
        }
    }

    /** Chờ tối đa chừng này khi người khác đang tải đúng link này (để dùng luôn bản cache của họ). */
    private static final long WAIT_INFLIGHT_MS = 25_000;

    private static void runPlan(String link, Options opt, PcmBuffer buf, Ctx ctx)
            throws IOException, UnsupportedAudioFileException {
        ctx.check();
        String start = rewriteKnownHosts(link);
        String canon = normalizeUrl(start, UrlForm.NORMALIZED); // kiểm tra sớm
        String host = hostOf(canon);

        // ---- 1. Cache trên đĩa
        AudioCache cache = ctx.cache();
        String key = null;
        AudioCache.Entry entry = null;
        if (cache != null && cache.isEnabled()) {
            key = AudioCache.keyOf(canon);
            cache.awaitInflight(key, ctx.cancelled(), WAIT_INFLIGHT_MS);
            ctx.check();
            entry = cache.find(key);
            if (entry != null && cache.isFresh(entry)) {
                if (playFromCache(cache, entry, opt, buf, ctx)) return; // 0 byte mạng
                entry = null;
            }
        }
        Validators cond = Validators.of(entry);

        // ---- 2. Tải từ mạng, thử lần lượt các cách kết nối
        List<Strategy> plan = orderFor(host);
        Budget budget = new Budget();
        IOException last = null;
        int attempts = 0;
        Kind forced = null;
        int idx = 0;

        while (idx < plan.size()) {
            ctx.check();
            Strategy st = plan.get(idx);
            attempts++;
            Opened o = null;
            AudioCache.CacheWriter writer = null;
            try {
                o = open(start, opt, st, ctx, cond, false);
                InputStream src = o.in();
                TeeInputStream tee = null;
                if (key != null && o.cacheable()) {
                    writer = cache.begin(key, canon, o.etag(), o.lastModified());
                    if (writer != null) {
                        tee = new TeeInputStream(src, writer);
                        src = tee;
                    }
                }
                BufferedInputStream bis = new BufferedInputStream(src, 1 << 16);
                Sniff sn = sniff(bis, o.info());
                Kind kind = forced != null ? forced : sn.kind();
                try {
                    boolean complete = decode(bis, kind, opt, buf, sn.head(), ctx);
                    remember(host, st);
                    if (tee != null && complete) finishCache(tee, writer);
                    return;
                } catch (UnsupportedAudioFileException e) {
                    if (buf.length() > 0 || forced != null || sn.kind() != Kind.UNKNOWN) throw e;
                    // Không nhận diện được định dạng: thử decoder còn lại trên kết nối mới
                    forced = Kind.JAVASOUND;
                    continue;
                }
            } catch (NotModifiedException e) {
                // Host xác nhận file chưa đổi -> dùng bản cache (tốn vài trăm byte thay vì cả file)
                if (entry != null && playFromCache(cache, entry, opt, buf, ctx)) {
                    cache.refresh(entry);
                    return;
                }
                if (entry != null) cache.remove(entry);
                entry = null;
                cond = null; // bản cache hỏng: tải lại bình thường
                continue;
            } catch (IOException e) {
                if (e instanceof CancelledException) throw e;
                if (buf.length() > 0) {
                    if (e instanceof TooLargeException || buf.length() < SAMPLE_RATE) throw e;
                    // Kết nối đứt giữa chừng nhưng đã có nhạc -> dùng phần đã tải
                    LOG.warning("Kết nối bị ngắt giữa chừng, dùng phần đã tải: " + e.getMessage());
                    return;
                }
                last = e;
                budget.failed(e); // ném lại nếu không còn đáng thử
                idx++;
            } finally {
                if (writer != null) writer.abort(); // không làm gì nếu đã commit
                if (o != null) {
                    try {
                        o.in().close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        if (attempts > 1) LOG.warning("Đã thử " + attempts + " cách kết nối tới " + host + " nhưng đều lỗi: "
                + (last != null ? last.getMessage() : "?"));
        throw last != null ? last : new IOException("không tải được file");
    }

    /** Phát từ file cache. @return false nếu cache hỏng (đã xóa) và chưa phát gì, để tải lại từ mạng. */
    private static boolean playFromCache(AudioCache cache, AudioCache.Entry e, Options opt, PcmBuffer buf, Ctx ctx)
            throws IOException, UnsupportedAudioFileException {
        try (InputStream in = new LimitedInputStream(Files.newInputStream(e.data()), opt.maxBytes(), null,
                ctx.cancelled())) {
            BufferedInputStream bis = new BufferedInputStream(in, 1 << 16);
            Sniff sn = sniff(bis, "cache");
            decode(bis, sn.kind(), opt, buf, sn.head(), ctx);
            cache.touch(e);
            return true;
        } catch (CancelledException c) {
            throw c;
        } catch (IOException | UnsupportedAudioFileException ex) {
            if (buf.length() > 0) throw ex; // đã phát một phần rồi, không thể làm lại
            LOG.warning("File cache bị hỏng, tải lại từ mạng: " + ex.getMessage());
            cache.remove(e);
            return false;
        }
    }

    /** Đã giải mã xong toàn bộ file: đọc nốt phần đuôi (nếu có) rồi chốt vào cache. */
    private static void finishCache(TeeInputStream tee, AudioCache.CacheWriter writer) {
        try {
            tee.drain();
            writer.commit();
        } catch (IOException ex) {
            writer.abort();
        }
    }

    private static void pause(IOException e) throws IOException {
        long ms = 150;
        if (e instanceof HttpStatusException h) ms = h.code == 429 ? 1500 : (h.code >= 500 ? 800 : 150);
        else if (e instanceof EmptyBodyException) ms = 600;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    // ------------------------------------------------------------ kiểm tra link

    /**
     * Kiểm tra nhanh một link có phải file âm thanh tải được không, chỉ tốn vài KB dữ liệu
     * (gửi Range đầu file, đọc vài byte để nhận diện định dạng rồi ngắt kết nối).
     * Ném lỗi nếu có vấn đề; dùng {@link #isDefinitiveFailure(Throwable)} để biết lỗi chắc chắn hay tạm thời.
     */
    public static void probe(String link, Options opt, Ctx ctx) throws IOException, UnsupportedAudioFileException {
        ctx = ctx == null ? Ctx.NONE : ctx;
        ctx.check();
        String start = rewriteKnownHosts(link);
        String canon = normalizeUrl(start, UrlForm.NORMALIZED);
        AudioCache cache = ctx.cache();
        if (cache != null && cache.isEnabled() && cache.find(AudioCache.keyOf(canon)) != null) return;

        String host = hostOf(canon);
        List<Strategy> plan = orderFor(host);
        Budget budget = new Budget();
        IOException last = null;
        for (int i = 0; i < plan.size(); i++) {
            ctx.check();
            Strategy st = plan.get(i);
            Opened o = null;
            try {
                o = open(start, opt, st, ctx, null, i == 0); // lần đầu xin Range, các lần sau xin cả file
                BufferedInputStream bis = new BufferedInputStream(o.in(), 8192);
                sniff(bis, o.info());
                remember(host, st);
                return;
            } catch (IOException e) {
                last = e;
                budget.failed(e);
            } finally {
                if (o != null) {
                    try {
                        o.in().close(); // đóng sớm: không tải nốt phần còn lại
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        throw last != null ? last : new IOException("không kiểm tra được link");
    }

    // ----------------------------------------------------------------- HTTP

    private static String cleanHeader(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c >= 0x20 && c <= 0x7E) sb.append(c);
        }
        return sb.toString().trim();
    }

    private static void applyHeaders(HttpURLConnection conn, Profile p, String url, Options opt) {
        String ua = cleanHeader(opt.userAgent());
        if (ua.isEmpty()) ua = DEFAULT_UA;
        switch (p) {
            case BROWSER -> {
                conn.setRequestProperty("User-Agent", ua);
                conn.setRequestProperty("Accept",
                        "audio/webm,audio/ogg,audio/wav,audio/*;q=0.9,application/ogg;q=0.7,video/*;q=0.6,*/*;q=0.5");
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                conn.setRequestProperty("Accept-Encoding", "identity");
                String origin = originOf(url);
                if (origin != null) conn.setRequestProperty("Referer", origin + "/");
            }
            case MINIMAL -> {
                conn.setRequestProperty("User-Agent", ua);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Encoding", "identity");
            }
            case CURL -> {
                conn.setRequestProperty("User-Agent", "curl/8.5.0");
                conn.setRequestProperty("Accept", "*/*");
            }
            case PLAIN -> {
                // dùng header mặc định của Java
            }
        }
    }

    private static Opened open(String link, Options opt, Strategy st, Ctx ctx, Validators cond, boolean range)
            throws IOException {
        String current = normalizeUrl(link, st.form());
        Set<String> seen = new HashSet<>();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            ctx.check();
            if (!seen.add(current)) throw new IOException("chuyển hướng lặp vô hạn");
            String host = hostOf(current);
            if (host == null) throw new BadLinkException("link không hợp lệ");
            if (opt.blockPrivate()) assertPublicHost(host);

            HttpURLConnection conn;
            try {
                conn = (HttpURLConnection) URI.create(current).toURL().openConnection();
            } catch (IllegalArgumentException e) {
                throw new BadLinkException("link không hợp lệ");
            }
            conn.setInstanceFollowRedirects(false); // tự xử lý để kiểm tra từng bước
            conn.setConnectTimeout(opt.connectTimeoutMs());
            conn.setReadTimeout(opt.readTimeoutMs());
            conn.setUseCaches(false);
            applyHeaders(conn, st.profile(), current, opt);
            if (cond != null) {
                if (cond.etag() != null) conn.setRequestProperty("If-None-Match", cleanHeader(cond.etag()));
                if (cond.lastModified() != null) {
                    conn.setRequestProperty("If-Modified-Since", cleanHeader(cond.lastModified()));
                }
            }
            if (range) conn.setRequestProperty("Range", "bytes=0-16383");

            int code;
            try {
                code = conn.getResponseCode();
            } catch (UnknownHostException e) {
                conn.disconnect();
                throw new BadLinkException("không tìm thấy tên miền " + host);
            } catch (IOException e) {
                conn.disconnect();
                throw e;
            }

            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isBlank()) throw new IOException("chuyển hướng không có địa chỉ đích");
                current = normalizeUrl(resolveLocation(current, loc), UrlForm.NORMALIZED);
                continue;
            }
            if (code == 304) {
                conn.disconnect();
                if (cond != null) throw new NotModifiedException();
                throw new HttpStatusException(code);
            }
            if (code == 204 || code == 205) {
                conn.disconnect();
                throw new EmptyBodyException();
            }
            if (code != 200 && code != 206) {
                conn.disconnect();
                throw new HttpStatusException(code);
            }

            String type = conn.getContentType();
            long len = conn.getContentLengthLong();
            long total = len;
            if (code == 206) { // "Content-Range: bytes 0-16383/123456" -> kích thước thật của file
                String cr = conn.getHeaderField("Content-Range");
                int slash = cr == null ? -1 : cr.lastIndexOf('/');
                if (slash >= 0) {
                    try {
                        total = Long.parseLong(cr.substring(slash + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            String enc = conn.getContentEncoding();
            String info = "HTTP " + code + ", type=" + type + ", length=" + total + ", encoding=" + enc;
            if (total > opt.maxBytes()) {
                conn.disconnect();
                throw new TooLargeException();
            }

            String cc = conn.getHeaderField("Cache-Control");
            boolean noStore = cc != null && cc.toLowerCase(Locale.ROOT).contains("no-store");
            String etag = cleanHeader(conn.getHeaderField("ETag"));
            String lastMod = cleanHeader(conn.getHeaderField("Last-Modified"));

            InputStream raw = conn.getInputStream();
            if (enc != null) {
                String e = enc.trim().toLowerCase(Locale.ROOT);
                if (e.equals("gzip") || e.equals("x-gzip")) {
                    raw = new GZIPInputStream(raw);
                } else if (e.equals("deflate")) {
                    raw = new InflaterInputStream(raw);
                } else if (!e.equals("identity") && !e.isEmpty()) {
                    conn.disconnect();
                    throw new IOException("máy chủ dùng kiểu nén không hỗ trợ (" + e + ")");
                }
            }
            return new Opened(new LimitedInputStream(raw, opt.maxBytes(), conn, ctx.cancelled()), info,
                    etag.isEmpty() ? null : etag, lastMod.isEmpty() ? null : lastMod,
                    code == 200 && !range && !noStore);
        }
        throw new IOException("quá nhiều lần chuyển hướng");
    }

    private static void assertPublicHost(String host) throws IOException {
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BadLinkException("không tìm thấy tên miền " + host);
        }
        for (InetAddress a : all) {
            byte[] b = a.getAddress();
            boolean uniqueLocalV6 = b.length == 16 && (b[0] & 0xFE) == 0xFC;
            if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                    || a.isSiteLocalAddress() || a.isMulticastAddress() || uniqueLocalV6) {
                throw new BlockedException();
            }
        }
    }

    // ----------------------------------------------------------- format sniffing

    private enum Kind {MP3, JAVASOUND, UNKNOWN}

    private record Sniff(Kind kind, byte[] head) {
    }

    private static String ascii(byte[] b, int off, int len) {
        if (off >= b.length) return "";
        return new String(b, off, Math.min(len, b.length - off), StandardCharsets.ISO_8859_1);
    }

    /** Nhận diện định dạng bằng nội dung file (không tin Content-Type / đuôi file). */
    private static Sniff sniff(BufferedInputStream bis, String info)
            throws IOException, UnsupportedAudioFileException {
        bis.mark(256);
        byte[] h = bis.readNBytes(64);
        bis.reset();
        if (h.length < 4) {
            LOG.warning("Phản hồi rỗng/quá ngắn: " + info);
            throw new EmptyBodyException();
        }
        byte[] head = Arrays.copyOf(h, 4);

        // Trang web / JSON / văn bản
        int off = 0;
        if (h.length >= 3 && (h[0] & 0xFF) == 0xEF && (h[1] & 0xFF) == 0xBB && (h[2] & 0xFF) == 0xBF) off = 3;
        while (off < h.length && (h[off] == ' ' || h[off] == '\n' || h[off] == '\r' || h[off] == '\t')) off++;
        if (off < h.length && (h[off] == '<' || h[off] == '{')) {
            LOG.warning("Link trả về trang web/văn bản: " + info);
            throw new NotAudioException("link trả về trang web chứ không phải file âm thanh trực tiếp");
        }

        String m4 = ascii(h, 0, 4);
        if (m4.equals("OggS")) {
            if (ascii(h, 0, 64).contains("OpusHead")) {
                throw new UnsupportedAudioFileException("Ogg Opus chưa được hỗ trợ, hãy dùng Ogg Vorbis/MP3/WAV");
            }
            return new Sniff(Kind.JAVASOUND, head);
        }
        if (m4.equals("RIFF")) {
            String t = ascii(h, 8, 4);
            if (t.equals("WAVE")) return new Sniff(Kind.JAVASOUND, head);
            throw new UnsupportedAudioFileException("file RIFF không phải WAV (" + t.trim() + ")");
        }
        if (m4.equals("FORM") || m4.equals(".snd")) return new Sniff(Kind.JAVASOUND, head);
        if (m4.equals("fLaC")) throw new UnsupportedAudioFileException("FLAC không được hỗ trợ");
        if (ascii(h, 4, 4).equals("ftyp")) {
            throw new UnsupportedAudioFileException("file MP4/M4A/AAC không được hỗ trợ");
        }
        if ((h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3) {
            throw new UnsupportedAudioFileException("file WebM/MKV không được hỗ trợ");
        }
        if (m4.startsWith("ID3")) return new Sniff(Kind.MP3, head);
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) {
            int version = (h[1] >> 3) & 3;
            int layer = (h[1] >> 1) & 3;
            if (layer == 0) throw new UnsupportedAudioFileException("file AAC (ADTS) không được hỗ trợ");
            if (version != 1) return new Sniff(Kind.MP3, head);
        }
        if (looksLikeText(h)) {
            LOG.warning("Link trả về văn bản: " + info);
            throw new NotAudioException("link trả về văn bản chứ không phải file âm thanh");
        }
        return new Sniff(Kind.UNKNOWN, head);
    }

    private static boolean looksLikeText(byte[] h) {
        for (byte x : h) {
            int c = x & 0xFF;
            if (!(c == 9 || c == 10 || c == 13 || (c >= 0x20 && c <= 0x7E))) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- decoding

    /** @return true nếu đã giải mã hết file; false nếu dừng sớm vì chạm giới hạn thời lượng. */
    private static boolean decode(BufferedInputStream bis, Kind kind, Options opt, PcmBuffer out, byte[] head,
                                  Ctx ctx) throws IOException, UnsupportedAudioFileException {
        if (kind == Kind.JAVASOUND) return decodeWithJavaSound(bis, opt, out, head, ctx);
        return decodeMp3(bis, opt, out, head, ctx); // MP3 (có/không ID3) và định dạng chưa rõ
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    /** Ghi nhớ lỗi IO đầu tiên và ném lại ngay, tránh chờ timeout nhiều lần. */
    private static final class StickyInputStream extends FilterInputStream {
        IOException error;

        StickyInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            if (error != null) throw error;
            try {
                return super.read();
            } catch (IOException e) {
                error = e;
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (error != null) throw error;
            try {
                return super.read(b, off, len);
            } catch (IOException e) {
                error = e;
                throw e;
            }
        }

        @Override
        public long skip(long n) throws IOException {
            if (error != null) throw error;
            try {
                return super.skip(n);
            } catch (IOException e) {
                error = e;
                throw e;
            }
        }
    }

    /** Gom mẫu mono thành khối rồi đẩy vào bộ resample. */
    private static final class Chunker {
        private final StreamResampler rs;
        private final short[] chunk = new short[8192];
        private int n;
        boolean full;

        Chunker(StreamResampler rs) {
            this.rs = rs;
        }

        void add(short s) {
            chunk[n++] = s;
            if (n == chunk.length) flush();
        }

        void flush() {
            if (n > 0 && !full) {
                if (!rs.push(chunk, n)) full = true;
            }
            n = 0;
        }

        void finish() {
            flush();
            rs.finish();
        }
    }

    /** Giải mã MP3 trực tiếp bằng JLayer; bỏ qua frame hỏng thay vì dừng cả bài. */
    private static boolean decodeMp3(InputStream in, Options opt, PcmBuffer out, byte[] head, Ctx ctx)
            throws IOException, UnsupportedAudioFileException {
        StickyInputStream src = new StickyInputStream(new NoCloseInputStream(in));
        Bitstream bs = new Bitstream(src);
        Decoder decoder = new Decoder();
        Chunker chunker = null;
        long decoded = 0;
        int errors = 0;
        String lastError = null;

        try {
            while (true) {
                ctx.check(); // người chơi đã dừng nhạc -> thôi không tải/giải mã nữa
                try {
                    Header h = bs.readFrame();
                    if (h == null) break;
                    if (chunker == null) {
                        chunker = new Chunker(new StreamResampler(out, h.frequency(), opt));
                    }
                    SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(h, bs);
                    short[] buf = sb.getBuffer();
                    int len = sb.getBufferLength();
                    int ch = Math.max(1, sb.getChannelCount());
                    bs.closeFrame();

                    for (int i = 0; i + ch <= len; i += ch) {
                        int sum = 0;
                        for (int c = 0; c < ch; c++) sum += buf[i + c];
                        chunker.add((short) (sum / ch));
                        decoded++;
                    }
                    errors = 0;
                    if (chunker.full) break;
                } catch (JavaLayerException | RuntimeException e) {
                    if (src.error != null) break; // lỗi mạng: không lặp lại
                    lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    if (++errors > 64) break;
                    try {
                        bs.closeFrame();
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        } finally {
            try {
                bs.close();
            } catch (JavaLayerException ignored) {
            }
        }

        if (chunker == null || decoded == 0) {
            if (src.error != null) throw src.error;
            LOG.warning("Không giải mã được MP3" + (lastError != null ? " (" + lastError + ")" : "")
                    + ", header=" + hex(head));
            throw new UnsupportedAudioFileException("file MP3 không hợp lệ hoặc bị hỏng");
        }
        chunker.finish();
        if (src.error != null && !chunker.full) throw src.error;
        return !chunker.full;
    }

    /** OGG / WAV... qua javax.sound. */
    private static boolean decodeWithJavaSound(InputStream bis, Options opt, PcmBuffer out, byte[] head, Ctx ctx)
            throws IOException, UnsupportedAudioFileException {
        AudioInputStream ais;
        try {
            ais = AudioSystem.getAudioInputStream(new NoCloseInputStream(bis));
        } catch (UnsupportedAudioFileException e) {
            LOG.warning("Không có decoder cho định dạng này, header=" + hex(head));
            throw new UnsupportedAudioFileException("không có bộ giải mã cho định dạng này");
        }
        try (ais) {
            AudioFormat base = ais.getFormat();
            int channels = Math.max(1, base.getChannels());
            float rate = base.getSampleRate();
            if (rate <= 0) throw new IOException("không xác định được sample rate");

            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16,
                    channels, channels * 2, rate, false);
            AudioInputStream pcm;
            try {
                pcm = base.matches(target) ? ais : AudioSystem.getAudioInputStream(target, ais);
            } catch (IllegalArgumentException e) {
                throw new UnsupportedAudioFileException("kiểu mã hóa âm thanh không hỗ trợ (" + base + ")");
            }

            Chunker chunker = new Chunker(new StreamResampler(out, rate, opt));
            int frameSize = channels * 2;
            byte[] buf = new byte[frameSize * 4096];
            int filled = 0;
            long total = 0;

            while (!chunker.full) {
                ctx.check();
                int n = pcm.read(buf, filled, buf.length - filled);
                if (n < 0) break;
                filled += n;
                int frames = filled / frameSize;
                for (int f = 0; f < frames; f++) {
                    int sum = 0;
                    int idx = f * frameSize;
                    for (int c = 0; c < channels; c++) {
                        int i = idx + c * 2;
                        sum += (short) ((buf[i + 1] << 8) | (buf[i] & 0xFF));
                    }
                    chunker.add((short) (sum / channels));
                    total++;
                }
                int used = frames * frameSize;
                int left = filled - used;
                System.arraycopy(buf, used, buf, 0, left);
                filled = left;
            }
            if (total == 0) throw new IOException("file âm thanh rỗng");
            chunker.finish();
            return !chunker.full;
        }
    }

    // --------------------------------------------------------------- resampler

    /** Resample tuyến tính theo dòng về 48kHz, áp dụng âm lượng và giới hạn thời lượng. */
    private static final class StreamResampler {
        private final PcmBuffer out;
        private final double ratio;
        private final float volume;
        private final long maxOut;
        private short[] win = new short[0];
        private long base = 0;       // chỉ số toàn cục của win[0]
        private long outIndex = 0;
        private final short[] tmp = new short[4096];
        private int tmpN = 0;

        StreamResampler(PcmBuffer out, float srcRate, Options opt) throws IOException {
            if (srcRate <= 0) throw new IOException("không xác định được sample rate");
            this.out = out;
            this.ratio = srcRate / (double) SAMPLE_RATE;
            this.volume = opt.volume();
            this.maxOut = (long) opt.maxSeconds() * SAMPLE_RATE;
        }

        private void emit(double s) {
            s *= volume;
            if (s > Short.MAX_VALUE) s = Short.MAX_VALUE;
            else if (s < Short.MIN_VALUE) s = Short.MIN_VALUE;
            tmp[tmpN++] = (short) s;
            if (tmpN == tmp.length) flushTmp();
            outIndex++;
        }

        private void flushTmp() {
            if (tmpN > 0) out.append(tmp, tmpN);
            tmpN = 0;
        }

        /** @return false khi đã đạt giới hạn thời lượng. */
        boolean push(short[] src, int n) {
            short[] all = Arrays.copyOf(win, win.length + n);
            System.arraycopy(src, 0, all, win.length, n);

            while (true) {
                if (outIndex >= maxOut) {
                    flushTmp();
                    return false;
                }
                double p = outIndex * ratio;
                long i0 = (long) p;
                long rel = i0 - base;
                if (rel + 1 >= all.length) break;
                double frac = p - i0;
                emit(all[(int) rel] * (1.0 - frac) + all[(int) rel + 1] * frac);
            }
            flushTmp();

            long keepFrom = (long) (outIndex * ratio);
            int rel = (int) Math.max(0, Math.min(keepFrom - base, all.length));
            win = Arrays.copyOfRange(all, rel, all.length);
            base += rel;
            return true;
        }

        void finish() {
            while (win.length > 0 && outIndex < maxOut) {
                double p = outIndex * ratio;
                long i0 = (long) p;
                long rel = i0 - base;
                if (rel >= win.length) break;
                int r1 = (int) Math.min(rel + 1, win.length - 1);
                double frac = p - i0;
                emit(win[(int) rel] * (1.0 - frac) + win[r1] * frac);
            }
            flushTmp();
        }
    }

    // ------------------------------------------------------------------ helper

    /**
     * JLayer / AudioInputStream tự đóng luồng nguồn khi xong. Bọc lại để luồng gốc còn mở, nhờ đó
     * còn đọc nốt được phần đuôi file để chốt vào cache. Luồng gốc luôn được đóng ở runPlan().
     */
    private static final class NoCloseInputStream extends FilterInputStream {
        NoCloseInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // cố ý không đóng
        }
    }

    /**
     * Vừa đọc vừa ghi bản sao vào cache. PHẢI chặn cả skip(): nếu không, các byte bị decoder bỏ qua
     * (vd: thẻ ID3) sẽ không được ghi và file cache bị thủng.
     */
    private static final class TeeInputStream extends FilterInputStream {
        private final AudioCache.CacheWriter writer;
        private final byte[] one = new byte[1];

        TeeInputStream(InputStream in, AudioCache.CacheWriter writer) {
            super(in);
            this.writer = writer;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                one[0] = (byte) b;
                writer.write(one, 0, 1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) writer.write(b, off, n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            if (n <= 0) return 0;
            byte[] tmp = new byte[(int) Math.min(n, 8192)];
            long total = 0;
            while (total < n) {
                int r = read(tmp, 0, (int) Math.min(tmp.length, n - total));
                if (r < 0) break;
                total += r;
            }
            return total;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        /** Đọc nốt phần còn lại (ghi vào cache) cho tới hết file. */
        void drain() throws IOException {
            byte[] tmp = new byte[16384];
            while (read(tmp, 0, tmp.length) >= 0) {
                // chỉ cần đọc
            }
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long max;
        private final HttpURLConnection conn; // null nếu đọc từ file cache
        private final BooleanSupplier cancelled;
        private long count;

        LimitedInputStream(InputStream in, long max, HttpURLConnection conn, BooleanSupplier cancelled) {
            super(in);
            this.max = max;
            this.conn = conn;
            this.cancelled = cancelled;
        }

        private void add(long n) throws IOException {
            count += n;
            if (count > max) throw new TooLargeException();
        }

        private void checkCancelled() throws IOException {
            if (cancelled.getAsBoolean()) throw new CancelledException();
        }

        @Override
        public int read() throws IOException {
            checkCancelled();
            int b = super.read();
            if (b >= 0) add(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            checkCancelled();
            int n = super.read(b, off, len);
            if (n > 0) add(n);
            return n;
        }

        @Override
        public long skip(long k) throws IOException {
            checkCancelled();
            long n = super.skip(k);
            if (n > 0) add(n);
            return n;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (conn != null) conn.disconnect(); // đóng hẳn socket: dừng tải ngay
            }
        }
    }
}
