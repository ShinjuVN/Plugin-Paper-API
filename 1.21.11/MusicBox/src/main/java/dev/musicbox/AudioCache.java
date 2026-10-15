package dev.musicbox;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Cache file âm thanh GỐC (mp3/ogg/wav, chưa giải mã) trên đĩa để không phải tải lại cùng một link.
 *
 * <ul>
 *   <li>Mỗi link = 1 cặp file {@code <sha256>.bin} (dữ liệu) + {@code <sha256>.meta} (ETag, Last-Modified...).</li>
 *   <li>Còn "tươi" (trong {@code ttl}) thì dùng luôn, không tốn dữ liệu mạng.</li>
 *   <li>Hết hạn thì hỏi lại host bằng If-None-Match / If-Modified-Since; host trả 304 là chỉ tốn vài trăm byte.</li>
 *   <li>Tự xóa file cũ nhất (LRU) khi vượt dung lượng tối đa.</li>
 *   <li>Cùng một link đang được tải bởi người khác thì người sau chờ, rồi dùng luôn bản đã cache.</li>
 *   <li>Chỉ ghi vào cache sau khi file đã giải mã thành công, nên không bao giờ lưu trang lỗi/HTML.</li>
 * </ul>
 * Mọi lỗi đọc/ghi cache đều bị bỏ qua êm (chỉ cảnh báo), không bao giờ làm hỏng việc phát nhạc.
 */
public final class AudioCache {

    /** Một mục trong cache. */
    public record Entry(String key, Path data, String url, String etag, String lastModified,
                        long savedAt, long size) {
    }

    private final Path dir;
    private final Logger log;
    private final ConcurrentHashMap<String, CountDownLatch> inflight = new ConcurrentHashMap<>();
    private final Object evictLock = new Object();

    private volatile boolean enabled = true;
    private volatile long maxBytes = 200L * 1024 * 1024;
    private volatile long ttlMs = 24L * 3600 * 1000;

    public AudioCache(Path dir, Logger log) {
        this.dir = dir;
        this.log = log;
    }

    // ------------------------------------------------------------- cấu hình

    public void configure(boolean enabled, long maxBytes, long ttlMs) {
        this.enabled = enabled;
        this.maxBytes = Math.max(0, maxBytes);
        this.ttlMs = Math.max(0, ttlMs);
    }

    public boolean isEnabled() {
        return enabled && maxBytes > 0;
    }

    public static String keyOf(String url) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path dataPath(String key) {
        return dir.resolve(key + ".bin");
    }

    private Path metaPath(String key) {
        return dir.resolve(key + ".meta");
    }

    private Path partPath(String key) {
        return dir.resolve(key + ".part");
    }

    // ------------------------------------------------------------- tra cứu

    /** @return mục cache hợp lệ, hoặc null. Mục hỏng/thiếu file sẽ bị xóa. */
    public Entry find(String key) {
        if (!isEnabled()) return null;
        Path meta = metaPath(key);
        Path data = dataPath(key);
        try {
            if (!Files.isRegularFile(meta) || !Files.isRegularFile(data)) {
                if (Files.exists(meta) || Files.exists(data)) removeFiles(key);
                return null;
            }
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            long size = Long.parseLong(p.getProperty("size", "-1"));
            if (size <= 0 || size != Files.size(data)) {
                removeFiles(key);
                return null;
            }
            return new Entry(key, data, p.getProperty("url", ""), emptyToNull(p.getProperty("etag")),
                    emptyToNull(p.getProperty("lastModified")),
                    Long.parseLong(p.getProperty("savedAt", "0")), size);
        } catch (IOException | RuntimeException e) {
            removeFiles(key);
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Còn trong thời hạn tin cậy, không cần hỏi lại host. */
    public boolean isFresh(Entry e) {
        return ttlMs > 0 && System.currentTimeMillis() - e.savedAt() < ttlMs;
    }

    /** Đánh dấu vừa dùng (cho LRU). */
    public void touch(Entry e) {
        try {
            Files.setLastModifiedTime(e.data(), FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
        }
    }

    /** Host xác nhận (304) file chưa đổi: gia hạn thời gian tin cậy. */
    public void refresh(Entry e) {
        try {
            writeMeta(e.key(), e.url(), e.etag(), e.lastModified(), System.currentTimeMillis(), e.size());
        } catch (IOException ex) {
            log.log(Level.FINE, "Không gia hạn được cache", ex);
        }
        touch(e);
    }

    public void remove(Entry e) {
        removeFiles(e.key());
    }

    private void removeFiles(String key) {
        try {
            Files.deleteIfExists(dataPath(key));
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(metaPath(key));
        } catch (IOException ignored) {
        }
    }

    private void writeMeta(String key, String url, String etag, String lastModified, long savedAt, long size)
            throws IOException {
        Properties p = new Properties();
        p.setProperty("url", url == null ? "" : url);
        p.setProperty("etag", etag == null ? "" : etag);
        p.setProperty("lastModified", lastModified == null ? "" : lastModified);
        p.setProperty("savedAt", Long.toString(savedAt));
        p.setProperty("size", Long.toString(size));
        Path tmp = dir.resolve(key + ".meta.tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, null);
        }
        move(tmp, metaPath(key));
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------- gộp các lần tải trùng

    /**
     * Nếu link này đang được người khác tải, chờ cho tới khi họ xong (tối đa maxWaitMs) để dùng
     * luôn bản đã cache thay vì tải thêm một lần nữa.
     */
    public void awaitInflight(String key, BooleanSupplier cancelled, long maxWaitMs) {
        long end = System.currentTimeMillis() + maxWaitMs;
        try {
            while (true) {
                CountDownLatch l = inflight.get(key);
                if (l == null || cancelled.getAsBoolean()) return;
                long left = end - System.currentTimeMillis();
                if (left <= 0) return;
                l.await(Math.min(left, 100), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ----------------------------------------------------------------- ghi

    /** Phiên ghi một file vào cache. Phải gọi commit() hoặc abort() (abort có thể gọi nhiều lần). */
    public final class CacheWriter {
        private final String key;
        private final String url;
        private final String etag;
        private final String lastModified;
        private final Path part;
        private final CountDownLatch latch;
        private OutputStream out;
        private long written;
        private boolean failed;
        private boolean done;

        private CacheWriter(String key, String url, String etag, String lastModified, Path part,
                        CountDownLatch latch, OutputStream out) {
            this.key = key;
            this.url = url;
            this.etag = etag;
            this.lastModified = lastModified;
            this.part = part;
            this.latch = latch;
            this.out = out;
        }

        public synchronized void write(byte[] b, int off, int len) {
            if (done || failed) return;
            if (written + len > maxBytes) { // một file không được vượt tổng dung lượng cache
                fail();
                return;
            }
            try {
                out.write(b, off, len);
                written += len;
            } catch (IOException e) {
                log.log(Level.FINE, "Ghi cache lỗi, bỏ qua cache cho link này", e);
                fail();
            }
        }

        private void fail() {
            failed = true;
            closeQuietly();
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
            }
        }

        private void closeQuietly() {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
                out = null;
            }
        }

        /** Hoàn tất: đổi tên .part thành .bin và ghi .meta. @return true nếu đã vào cache. */
        public synchronized boolean commit() {
            if (done) return false;
            done = true;
            try {
                if (failed || written <= 0) return false;
                try {
                    out.flush();
                } finally {
                    closeQuietly();
                }
                move(part, dataPath(key));
                writeMeta(key, url, etag, lastModified, System.currentTimeMillis(), written);
                return true;
            } catch (IOException e) {
                log.log(Level.FINE, "Không lưu được cache", e);
                removeFiles(key);
                return false;
            } finally {
                closeQuietly();
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                }
                release();
                evictIfNeeded();
            }
        }

        /** Hủy (nếu chưa commit). An toàn khi gọi nhiều lần. */
        public synchronized void abort() {
            if (done) return;
            done = true;
            closeQuietly();
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
            }
            release();
        }

        private void release() {
            inflight.remove(key, latch);
            latch.countDown();
        }
    }

    /**
     * Bắt đầu ghi cache cho link. @return null nếu cache tắt, người khác đang tải link này,
     * hoặc không tạo được file.
     */
    public CacheWriter begin(String key, String url, String etag, String lastModified) {
        if (!isEnabled()) return null;
        CountDownLatch latch = new CountDownLatch(1);
        if (inflight.putIfAbsent(key, latch) != null) return null;
        try {
            Files.createDirectories(dir);
            Path part = partPath(key);
            OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return new CacheWriter(key, url, etag, lastModified, part, latch, out);
        } catch (IOException e) {
            log.log(Level.FINE, "Không tạo được file cache", e);
            inflight.remove(key, latch);
            latch.countDown();
            return null;
        }
    }

    // ------------------------------------------------------------ dọn dẹp

    private List<Path> listBins() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".bin")).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Xóa file cũ nhất cho tới khi tổng dung lượng <= mức tối đa. */
    public void evictIfNeeded() {
        synchronized (evictLock) {
            List<Path> bins = new ArrayList<>(listBins());
            long total = 0;
            for (Path p : bins) total += sizeOf(p);
            if (total <= maxBytes) return;
            bins.sort(Comparator.comparingLong(AudioCache::mtime)); // cũ nhất trước
            for (Path p : bins) {
                if (total <= maxBytes) break;
                String name = p.getFileName().toString();
                String key = name.substring(0, name.length() - 4);
                if (inflight.containsKey(key)) continue;
                total -= sizeOf(p);
                removeFiles(key);
            }
        }
    }

    /** Gọi lúc khởi động: xóa file .part/.tmp dang dở và file mồ côi, rồi áp dụng giới hạn dung lượng. */
    public void cleanup() {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                try {
                    if (n.endsWith(".part") || n.endsWith(".tmp")) {
                        if (!inflight.containsKey(n.substring(0, n.indexOf('.')))) Files.deleteIfExists(p);
                    } else if (n.endsWith(".meta") && !Files.exists(dir.resolve(n.replace(".meta", ".bin")))) {
                        Files.deleteIfExists(p);
                    } else if (n.endsWith(".bin") && !Files.exists(dir.resolve(n.replace(".bin", ".meta")))) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        evictIfNeeded();
    }

    public int count() {
        return listBins().size();
    }

    public long sizeBytes() {
        long t = 0;
        for (Path p : listBins()) t += sizeOf(p);
        return t;
    }

    /** Xóa toàn bộ cache (trừ file đang được ghi). @return số file đã xóa. */
    public int clear() {
        int n = 0;
        synchronized (evictLock) {
            for (Path p : listBins()) {
                String name = p.getFileName().toString();
                String key = name.substring(0, name.length() - 4);
                if (inflight.containsKey(key)) continue;
                removeFiles(key);
                n++;
            }
        }
        return n;
    }
}
