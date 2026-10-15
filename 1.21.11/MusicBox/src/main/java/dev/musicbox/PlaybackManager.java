package dev.musicbox;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class PlaybackManager implements Listener {

    public enum Source {PERSONAL, GLOBAL}

    /** 20ms ở 48kHz mono. */
    private static final int FRAME_SIZE = 960;

    private final MusicBoxPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile VoicechatServerApi api;

    /**
     * Số lượt tải nhạc chạy cùng lúc bị giới hạn (settings.max-concurrent-downloads) để nhiều người
     * bấm play cùng lúc không làm server tải dồn dập. Người thừa sẽ xếp hàng chờ.
     */
    private final ExecutorService downloads;

    public PlaybackManager(MusicBoxPlugin plugin) {
        this.plugin = plugin;
        int threads = Math.max(1, plugin.getConfig().getInt("settings.max-concurrent-downloads", 3));
        AtomicInteger n = new AtomicInteger();
        this.downloads = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "MusicBox-Download-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Dừng hẳn các luồng tải (gọi khi tắt plugin, sau stopAll). */
    public void shutdown() {
        downloads.shutdownNow();
    }

    private static final class Session {
        final UUID owner;
        final Source source;
        final String id;
        final PcmBuffer buf = new PcmBuffer();
        volatile int pos;
        volatile boolean stopped;
        AudioPlayer audioPlayer;
        LocationalAudioChannel channel;
        AtomicBoolean ignore;
        UUID worldUid;
        BukkitTask follow;

        Session(UUID owner, Source source, String id) {
            this.owner = owner;
            this.source = source;
            this.id = id;
        }
    }

    // ------------------------------------------------------------------- api

    public void setApi(VoicechatServerApi api) {
        this.api = api;
        if (api == null) stopAll();
    }

    public boolean isPlaying(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    // ------------------------------------------------------------ public ops

    public void play(Player p, Source source, String id, String url) {
        loadAndPlay(p, source, id, url, false);
    }

    /** @return true nếu có nhạc để dừng. */
    public boolean cancel(UUID uuid) {
        Session s = sessions.get(uuid);
        if (s == null) return false;
        stopSession(s);
        return true;
    }

    /** Dừng nếu đang phát bài cá nhân có id này (id = null: bất kỳ bài cá nhân nào). */
    public void cancelIfPersonal(UUID uuid, String idOrNull) {
        Session s = sessions.get(uuid);
        if (s != null && s.source == Source.PERSONAL && (idOrNull == null || idOrNull.equals(s.id))) {
            stopSession(s);
        }
    }

    public void stopAll() {
        for (Session s : new ArrayList<>(sessions.values())) stopSession(s);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer().getUniqueId());
    }

    // --------------------------------------------------------------- loading

    private void loadAndPlay(Player p, Source source, String id, String url, boolean auto) {
        UUID uuid = p.getUniqueId();
        VoicechatServerApi a = api;
        if (a == null) {
            if (!auto) plugin.send(p, "voicechat-not-ready");
            cancel(uuid);
            return;
        }
        if (a.getConnectionOf(uuid) == null) {
            if (!auto) plugin.send(p, "no-voicechat-client");
            cancel(uuid);
            return;
        }

        cancel(uuid);
        Session s = new Session(uuid, source, id);
        sessions.put(uuid, s);
        if (!auto) plugin.send(p, "loading", "id", id);

        if (source == Source.PERSONAL) {
            PlayerData d = plugin.data().getOrCreate(p);
            d.lastPlayed = id;
            plugin.data().saveAsync();
        }

        AudioLoader.Options opt = options();
        int prebuffer = (int) (plugin.getConfig().getDouble("settings.prebuffer-seconds", 2.0)
                * AudioLoader.SAMPLE_RATE);
        PcmBuffer buf = s.buf;
        // Được gọi (1 lần) trên luồng giải mã khi đã đủ dữ liệu để phát, hoặc khi kết thúc/lỗi
        buf.setReadyListener(prebuffer, () -> {
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> onReady(s, auto));
        });

        // Cờ hủy: người chơi dừng nhạc (hoặc thoát game) thì ngắt tải ngay, không tải nốt cả file
        AudioLoader.Ctx ctx = new AudioLoader.Ctx(() -> s.stopped, plugin.cache());
        Runnable job = () -> {
            if (s.stopped) return; // đã bị dừng trong lúc xếp hàng chờ: khỏi tải
            try {
                AudioLoader.streamInto(url, opt, buf, ctx);
                buf.finish();
            } catch (AudioLoader.CancelledException e) {
                // người chơi đã dừng nhạc, không cần báo gì
            } catch (UnsupportedAudioFileException e) {
                plugin.getLogger().log(Level.WARNING, "Không giải mã được " + url + ": " + e.getMessage());
                buf.fail(plugin.raw("reason-unsupported") + " &8(" + e.getMessage() + ")");
            } catch (Throwable e) {
                // Bắt cả Error (vd: thiếu thư viện) để người chơi luôn nhận được thông báo
                plugin.getLogger().log(Level.WARNING, "Không thể tải nhạc từ " + url, e);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (e instanceof LinkageError) msg = "lỗi thư viện giải mã: " + msg + " (xem console)";
                buf.fail(msg);
            }
        };
        try {
            downloads.execute(job);
        } catch (RejectedExecutionException e) {
            // plugin đang tắt
            buf.fail("plugin đang tắt");
        }
    }

    AudioLoader.Options options() {
        FileConfiguration c = plugin.getConfig();
        return new AudioLoader.Options(
                c.getLong("settings.max-file-size-mb", 30) * 1024L * 1024L,
                c.getInt("settings.max-duration-seconds", 600),
                (float) c.getDouble("audio.volume", 1.0),
                c.getInt("settings.connect-timeout-ms", 8000),
                c.getInt("settings.read-timeout-ms", 15000),
                c.getBoolean("settings.block-private-addresses", true),
                c.getString("settings.user-agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36 MusicBox/1.0"));
    }

    private void onReady(Session s, boolean auto) {
        if (s.stopped || sessions.get(s.owner) != s) return;
        if (s.buf.length() == 0) {
            onFailed(s, s.buf.error() != null ? s.buf.error() : "?");
            return;
        }
        Player p = Bukkit.getPlayer(s.owner);
        if (p == null) {
            stopSession(s);
            return;
        }
        s.pos = 0;
        if (!startAudio(s, p)) {
            plugin.send(p, "load-failed", "id", s.id, "reason", "voice chat");
            stopSession(s);
            return;
        }
        s.follow = Bukkit.getScheduler().runTaskTimer(plugin, () -> follow(s), 4L, 4L);
        if (!auto) {
            plugin.send(p, "now-playing", "id", s.id, "source", plugin.sourceLabel(s.source));
        }
    }

    private void onFailed(Session s, String reason) {
        if (s.stopped || sessions.get(s.owner) != s) return;
        sessions.remove(s.owner, s);
        s.stopped = true;
        Player p = Bukkit.getPlayer(s.owner);
        if (p != null) plugin.send(p, "load-failed", "id", s.id, "reason", reason == null ? "?" : reason);
    }

    // ----------------------------------------------------------------- audio

    private boolean startAudio(Session s, Player p) {
        detach(s);
        VoicechatServerApi a = api;
        if (a == null) return false;

        Location loc = p.getEyeLocation();
        LocationalAudioChannel ch = a.createLocationalAudioChannel(
                UUID.randomUUID(),
                a.fromServerLevel(loc.getWorld()),
                a.createPosition(loc.getX(), loc.getY(), loc.getZ()));
        if (ch == null) return false;

        ch.setDistance((float) plugin.getConfig().getDouble("audio.distance", 24.0));

        UUID owner = s.owner;
        DataManager dm = plugin.data();
        // Chủ bài luôn nghe được; người khác chỉ nghe nếu bật "Nghe nhạc người khác"
        ch.setFilter(vp -> {
            UUID u = vp.getUuid();
            if (u.equals(owner)) return true;
            PlayerData d = dm.peek(u);
            return d == null || d.listenOthers;
        });

        AtomicBoolean ignore = new AtomicBoolean(false);
        OpusEncoder encoder = a.createEncoder();
        AudioPlayer ap = a.createAudioPlayer(ch, encoder, () -> ignore.get() ? null : nextFrame(s));
        ap.setOnStopped(() -> {
            // Chạy trên thread của audio player, sau khi vòng lặp đã kết thúc
            try {
                if (!encoder.isClosed()) encoder.close();
            } catch (Exception ignored) {
            }
            if (!ignore.get() && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> onTrackEnd(s));
            }
        });

        s.channel = ch;
        s.audioPlayer = ap;
        s.ignore = ignore;
        s.worldUid = loc.getWorld().getUID();
        ap.startPlaying();
        return true;
    }

    private short[] nextFrame(Session s) {
        if (s.stopped) return null;
        PcmBuffer buf = s.buf;
        int pos = s.pos;
        short[] frame = new short[FRAME_SIZE];
        int n = buf.read(pos, frame, FRAME_SIZE);
        if (n == FRAME_SIZE) {
            s.pos = pos + n;
            return frame;
        }
        if (buf.isDone()) {
            // Hết bài (n có thể là phần còn lại, phần thừa của frame là im lặng)
            if (n <= 0) return null;
            s.pos = pos + n;
            return frame;
        }
        // Mạng chậm hơn tốc độ phát: phát im lặng chờ dữ liệu, không tiến vị trí
        return frame;
    }

    private void detach(Session s) {
        if (s.ignore != null) s.ignore.set(true);
        if (s.audioPlayer != null) {
            try {
                s.audioPlayer.stopPlaying();
            } catch (Exception ignored) {
            }
        }
        s.audioPlayer = null;
        s.channel = null;
    }

    private void stopSession(Session s) {
        s.stopped = true;
        detach(s);
        if (s.follow != null) {
            s.follow.cancel();
            s.follow = null;
        }
        sessions.remove(s.owner, s);
    }

    /** Cập nhật vị trí kênh âm thanh theo người chơi (và đổi kênh khi sang thế giới khác). */
    private void follow(Session s) {
        if (s.stopped || sessions.get(s.owner) != s) {
            if (s.follow != null) s.follow.cancel();
            return;
        }
        Player p = Bukkit.getPlayer(s.owner);
        if (p == null) {
            stopSession(s);
            return;
        }
        LocationalAudioChannel ch = s.channel;
        VoicechatServerApi a = api;
        if (ch == null || a == null || (s.buf.isDone() && s.pos >= s.buf.length())) return;

        Location loc = p.getEyeLocation();
        if (!loc.getWorld().getUID().equals(s.worldUid)) {
            if (!startAudio(s, p)) stopSession(s);
            return;
        }
        ch.updateLocation(a.createPosition(loc.getX(), loc.getY(), loc.getZ()));
    }

    // ------------------------------------------------------------- track end

    private void onTrackEnd(Session s) {
        if (s.stopped || sessions.get(s.owner) != s) return;
        Player p = Bukkit.getPlayer(s.owner);
        if (p == null) {
            stopSession(s);
            return;
        }

        PlayerData d = plugin.data().peek(s.owner);
        if (s.source == Source.PERSONAL && d != null && d.repeat && !d.banned) {
            String next = pickNext(d, s);
            if (next != null) {
                if (next.equals(s.id)) {
                    // Cùng bài: dùng lại dữ liệu đã giải mã, không cần tải lại
                    s.pos = 0;
                    if (startAudio(s, p)) return;
                } else {
                    String url = d.songs.get(next);
                    if (url != null) {
                        loadAndPlay(p, Source.PERSONAL, next, url, true);
                        return;
                    }
                }
            }
        }

        stopSession(s);
        plugin.send(p, "finished");
    }

    private String pickNext(PlayerData d, Session s) {
        List<String> ids = new ArrayList<>(d.songs.keySet());
        if (ids.isEmpty()) return null;
        switch (d.mode) {
            case SINGLE:
                return ids.contains(s.id) ? s.id : ids.get(0);
            case SHUFFLE:
                if (ids.size() == 1) return ids.get(0);
                String pick;
                do {
                    pick = ids.get(ThreadLocalRandom.current().nextInt(ids.size()));
                } while (pick.equals(s.id));
                return pick;
            case PLAYLIST:
            default:
                int i = ids.indexOf(s.id); // -1 -> bài đầu tiên
                return ids.get((i + 1) % ids.size());
        }
    }
}


