package dev.musicbox;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bộ đệm PCM 16-bit mono 48kHz tăng dần. Luồng giải mã ghi vào (append),
 * luồng phát đọc ra (read) nên có thể phát khi file vẫn đang được tải.
 */
public final class PcmBuffer {

    private short[] data = new short[AudioLoader.SAMPLE_RATE * 15];
    private int length;                       // được bảo vệ bởi synchronized
    private volatile int published;           // bản sao để đọc không cần khóa
    private volatile boolean done;
    private volatile String error;

    private final AtomicBoolean fired = new AtomicBoolean(false);
    private volatile Runnable readyListener;
    private volatile int threshold = Integer.MAX_VALUE;

    /** Gọi listener (đúng 1 lần) khi đã đủ dữ liệu để bắt đầu phát, hoặc khi kết thúc/lỗi. */
    public void setReadyListener(int thresholdSamples, Runnable listener) {
        this.threshold = thresholdSamples;
        this.readyListener = listener;
    }

    private void fire() {
        Runnable r = readyListener;
        if (r != null && fired.compareAndSet(false, true)) r.run();
    }

    public void append(short[] src, int n) {
        if (n <= 0) return;
        synchronized (this) {
            if (length + n > data.length) {
                long want = Math.max((long) data.length * 2, (long) length + n);
                data = Arrays.copyOf(data, (int) Math.min(want, Integer.MAX_VALUE - 16));
            }
            System.arraycopy(src, 0, data, length, n);
            length += n;
            published = length;
        }
        if (published >= threshold) fire();
    }

    public void finish() {
        done = true;
        fire();
    }

    public void fail(String reason) {
        error = reason;
        done = true;
        fire();
    }

    public int length() {
        return published;
    }

    public boolean isDone() {
        return done;
    }

    public String error() {
        return error;
    }

    /** Sao chép tối đa max mẫu bắt đầu từ pos vào dst; trả về số mẫu đã chép. */
    public synchronized int read(int pos, short[] dst, int max) {
        int n = Math.min(max, length - pos);
        if (n <= 0) return 0;
        System.arraycopy(data, pos, dst, 0, n);
        return n;
    }

    public synchronized short[] snapshot() {
        return Arrays.copyOf(data, length);
    }
}


