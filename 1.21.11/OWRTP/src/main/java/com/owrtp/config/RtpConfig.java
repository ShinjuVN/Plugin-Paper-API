package com.owrtp.config;

import com.owrtp.OwRtpPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Giữ giá trị cấu hình đã parse sẵn (đọc nhanh, không đụng YAML mỗi lần). */
public final class RtpConfig {

    public static final int FALLBACK_COOLDOWN = 300;

    private final OwRtpPlugin plugin;

    private volatile int minRadius;
    private volatile int maxRadius;
    private volatile int countdown;
    private volatile int maxAttempts;
    private volatile boolean safeLocation;
    private volatile boolean cancelOnMove;
    private volatile boolean cancelOnDamage;
    private volatile boolean useGroupPermission;
    private volatile Set<String> enabledWorlds = Collections.emptySet();
    private volatile Set<Material> unsafeBlocks = Collections.emptySet();
    private volatile Map<String, Integer> cooldowns = Collections.emptyMap();

    public RtpConfig(OwRtpPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        FileConfiguration c = plugin.getConfig();

        int min = Math.max(0, c.getInt("settings.min-radius", 500));
        int max = c.getInt("settings.max-radius", 5000);
        if (max <= min) {
            plugin.getLogger().warning("max-radius phải lớn hơn min-radius, tự điều chỉnh max-radius = " + (min + 1));
            max = min + 1;
        }
        this.minRadius = min;
        this.maxRadius = max;

        this.countdown = Math.max(0, c.getInt("settings.countdown", 5));
        this.maxAttempts = Math.max(1, c.getInt("settings.max-attempts", 30));
        this.safeLocation = c.getBoolean("settings.safe-location", true);
        this.cancelOnMove = c.getBoolean("settings.cancel-on-move", true);
        this.cancelOnDamage = c.getBoolean("settings.cancel-on-damage", true);
        this.useGroupPermission = c.getBoolean("settings.use-group-permission", true);

        Set<String> worlds = new HashSet<>();
        for (String w : c.getStringList("settings.enabled-worlds")) {
            worlds.add(w.toLowerCase(Locale.ROOT));
        }
        this.enabledWorlds = Collections.unmodifiableSet(worlds);

        Set<Material> unsafe = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("settings.unsafe-blocks")) {
            Material m = Material.matchMaterial(name);
            if (m != null) {
                unsafe.add(m);
            } else {
                // Có thể block chưa tồn tại ở phiên bản server này -> bỏ qua êm
                plugin.getLogger().fine("Bỏ qua unsafe-block không tồn tại: " + name);
            }
        }
        this.unsafeBlocks = Collections.unmodifiableSet(unsafe);

        Map<String, Integer> map = new LinkedHashMap<>();
        ConfigurationSection sec = c.getConfigurationSection("cooldowns");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                map.put(key.toLowerCase(Locale.ROOT), Math.max(0, sec.getInt(key)));
            }
        }
        map.putIfAbsent("default", FALLBACK_COOLDOWN);
        this.cooldowns = Collections.unmodifiableMap(map);
    }

    // ---------------------------------------------------------------- setters (ghi file)

    public void setMinRadius(int value) {
        set("settings.min-radius", value);
    }

    public void setMaxRadius(int value) {
        set("settings.max-radius", value);
    }

    public void setCancelOnDamage(boolean value) {
        set("settings.cancel-on-damage", value);
    }

    public void setCancelOnMove(boolean value) {
        set("settings.cancel-on-move", value);
    }

    public void setRankCooldown(String rank, int seconds) {
        set("cooldowns." + rank, Math.max(0, seconds));
    }

    /** Đặt lại: xóa rank (về dùng "default"); riêng "default" quay về giá trị mặc định. */
    public void resetRankCooldown(String rank) {
        if (rank.equals("default")) {
            set("cooldowns.default", FALLBACK_COOLDOWN);
        } else {
            set("cooldowns." + rank, null);
        }
    }

    private void set(String path, Object value) {
        plugin.getConfig().set(path, value);
        plugin.saveConfig();
        load();
    }

    // ---------------------------------------------------------------- getters

    public int minRadius() { return minRadius; }
    public int maxRadius() { return maxRadius; }
    public int countdown() { return countdown; }
    public int maxAttempts() { return maxAttempts; }
    public boolean safeLocation() { return safeLocation; }
    public boolean cancelOnMove() { return cancelOnMove; }
    public boolean cancelOnDamage() { return cancelOnDamage; }
    public boolean useGroupPermission() { return useGroupPermission; }
    public Set<Material> unsafeBlocks() { return unsafeBlocks; }
    public Map<String, Integer> cooldowns() { return cooldowns; }

    public boolean isWorldEnabled(String worldName) {
        Set<String> w = enabledWorlds;
        return w.isEmpty() || w.contains(worldName.toLowerCase(Locale.ROOT));
    }
}
