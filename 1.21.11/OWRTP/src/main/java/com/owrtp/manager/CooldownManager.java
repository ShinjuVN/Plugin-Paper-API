package com.owrtp.manager;

import com.owrtp.OwRtpPlugin;
import com.owrtp.config.RtpConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CooldownManager {

    private final OwRtpPlugin plugin;
    private final File file;
    private final Map<UUID, Long> expiry = new ConcurrentHashMap<>();

    public CooldownManager(OwRtpPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        expiry.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("cooldowns");
        if (sec == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String key : sec.getKeys(false)) {
            try {
                long until = sec.getLong(key);
                if (until > now) {
                    expiry.put(UUID.fromString(key), until);
                }
            } catch (IllegalArgumentException ignored) {
                // UUID hỏng -> bỏ qua
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration yml = new YamlConfiguration();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : expiry.entrySet()) {
            if (e.getValue() > now) {
                yml.set("cooldowns." + e.getKey(), e.getValue());
            }
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                //noinspection ResultOfMethodCallIgnored
                plugin.getDataFolder().mkdirs();
            }
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Không lưu được data.yml: " + ex.getMessage());
        }
    }

    public void saveAsync() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    public long remainingSeconds(UUID id) {
        Long until = expiry.get(id);
        if (until == null) {
            return 0;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            expiry.remove(id);
            return 0;
        }
        return (left + 999) / 1000;
    }

    public void apply(Player player) {
        int seconds = resolveSeconds(player);
        if (seconds <= 0) {
            expiry.remove(player.getUniqueId());
        } else {
            expiry.put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
        }
        saveAsync();
    }

    /** Thời gian hồi chiêu thấp nhất trong các rank người chơi có; không có rank nào -> "default". */
    public int resolveSeconds(Player player) {
        RtpConfig cfg = plugin.rtpConfig();
        int best = Integer.MAX_VALUE;
        boolean found = false;
        for (Map.Entry<String, Integer> e : cfg.cooldowns().entrySet()) {
            String rank = e.getKey();
            if (rank.equals("default")) {
                continue;
            }
            if (player.hasPermission("rtp.cooldown." + rank)
                    || (cfg.useGroupPermission() && player.hasPermission("group." + rank))) {
                best = Math.min(best, e.getValue());
                found = true;
            }
        }
        return found ? best : cfg.cooldowns().getOrDefault("default", RtpConfig.FALLBACK_COOLDOWN);
    }
}
