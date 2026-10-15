package dev.musicbox;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class MusicBoxPlugin extends JavaPlugin {

    private DataManager data;
    private PlaybackManager playback;
    private AudioCache cache;
    private MusicVoicechatPlugin voicechatPlugin;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        data = new DataManager(this);
        data.load();

        // Cache file nhạc gốc trên đĩa: nghe lại cùng một link không tốn thêm dữ liệu mạng
        cache = new AudioCache(getDataFolder().toPath().resolve("cache"), getLogger());
        applyCacheSettings();
        Bukkit.getScheduler().runTaskAsynchronously(this, cache::cleanup);

        playback = new PlaybackManager(this);

        getServer().getPluginManager().registerEvents(playback, this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);

        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().severe("Không tìm thấy Simple Voice Chat. Hãy cài plugin 'voicechat' lên server!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        voicechatPlugin = new MusicVoicechatPlugin(this);
        service.registerPlugin(voicechatPlugin);

        PluginCommand cmd = getCommand("musicbox");
        if (cmd != null) {
            MusicCommand executor = new MusicCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
        getLogger().info("MusicBox đã sẵn sàng.");
    }

    @Override
    public void onDisable() {
        if (voicechatPlugin != null) {
            getServer().getServicesManager().unregister(voicechatPlugin);
        }
        if (playback != null) {
            playback.stopAll();   // đặt cờ hủy để các luồng đang tải dừng ngay
            playback.shutdown();
        }
        if (data != null) data.shutdown();
    }

    /** Đọc lại mục cache.* trong config (gọi lúc bật plugin và sau /musicbox reload). */
    public void applyCacheSettings() {
        if (cache == null) return;
        FileConfiguration c = getConfig();
        long maxBytes = Math.max(0L, c.getLong("cache.max-size-mb", 200)) * 1024L * 1024L;
        long ttlMs = (long) (Math.max(0.0, c.getDouble("cache.ttl-hours", 24.0)) * 3600_000.0);
        cache.configure(c.getBoolean("cache.enabled", true), maxBytes, ttlMs);
    }

    public AudioCache cache() {
        return cache;
    }

    public DataManager data() {
        return data;
    }

    public PlaybackManager playback() {
        return playback;
    }

    // ------------------------------------------------------------ messages

    public String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    /** Thay các biến {key} bằng giá trị; kv = key1, value1, key2, value2... */
    public String fill(String s, String... kv) {
        if (s == null) return "";
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return s;
    }

    /** Thông báo chưa tô màu, chưa có prefix. */
    public String raw(String key, String... kv) {
        String s = getConfig().getString("messages." + key);
        if (s == null) s = "&cThiếu thông báo trong config: " + key;
        return fill(s, kv);
    }

    /** Thông báo đã có prefix và tô màu. */
    public String msg(String key, String... kv) {
        return color(getConfig().getString("prefix", "") + raw(key, kv));
    }

    public void send(CommandSender to, String key, String... kv) {
        to.sendMessage(msg(key, kv));
    }

    public String sourceLabel(PlaybackManager.Source source) {
        return raw(source == PlaybackManager.Source.GLOBAL ? "source-global" : "source-personal");
    }
}


