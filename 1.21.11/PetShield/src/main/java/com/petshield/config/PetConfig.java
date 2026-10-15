package com.petshield.config;

import com.petshield.PetShieldPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Giá trị cấu hình đã parse sẵn. */
public final class PetConfig {

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([smhd]?)");

    private final PetShieldPlugin plugin;

    private volatile int registerTimeout;
    private volatile boolean requireTamed;
    private volatile int snapshotInterval;
    private volatile boolean reviveFullHealth;
    private volatile Set<EntityType> allowedMobs = Collections.emptySet();
    private volatile int defaultLimit;
    private volatile Map<String, Integer> limitPermissions = Collections.emptyMap();
    private volatile long reviveCooldownMillis;
    private volatile boolean costEnabled;
    private volatile int costAmount;
    private volatile boolean costLevels;
    private volatile Map<String, String> mobNames = Collections.emptyMap();

    public PetConfig(PetShieldPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        FileConfiguration c = plugin.getConfig();

        registerTimeout = Math.max(5, c.getInt("settings.register-timeout", 30));
        requireTamed = c.getBoolean("settings.require-tamed", true);
        snapshotInterval = Math.max(0, c.getInt("settings.snapshot-interval", 120));
        reviveFullHealth = c.getBoolean("settings.revive-full-health", true);

        Set<EntityType> mobs = EnumSet.noneOf(EntityType.class);
        for (String name : c.getStringList("settings.allowed-mobs")) {
            try {
                mobs.add(EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("allowed-mobs: loại mob không tồn tại ở phiên bản này: " + name);
            }
        }
        allowedMobs = Collections.unmodifiableSet(mobs);

        defaultLimit = Math.max(0, c.getInt("limits.default", 5));
        Map<String, Integer> limits = new LinkedHashMap<>();
        ConfigurationSection lp = c.getConfigurationSection("limits.permissions");
        if (lp != null) {
            for (String key : lp.getKeys(false)) {
                limits.put(key.toLowerCase(Locale.ROOT), Math.max(0, lp.getInt(key)));
            }
        }
        limitPermissions = Collections.unmodifiableMap(limits);

        reviveCooldownMillis = parseDuration(String.valueOf(c.get("revive.cooldown", "10m"))) * 1000L;

        costEnabled = c.getBoolean("revive.cost.enabled", true);
        String amount = String.valueOf(c.get("revive.cost.amount", "1l")).trim().toLowerCase(Locale.ROOT);
        boolean levels = amount.endsWith("l");
        String digits = levels ? amount.substring(0, amount.length() - 1).trim() : amount;
        int parsed = 0;
        try {
            parsed = Math.max(0, Integer.parseInt(digits));
        } catch (NumberFormatException ex) {
            plugin.getLogger().warning("revive.cost.amount không hợp lệ ('" + amount + "'), dùng 0.");
        }
        costAmount = parsed;
        costLevels = levels;

        Map<String, String> names = new HashMap<>();
        ConfigurationSection ms = c.getConfigurationSection("mob-names");
        if (ms != null) {
            for (String key : ms.getKeys(false)) {
                names.put(key.toUpperCase(Locale.ROOT), ms.getString(key, key));
            }
        }
        mobNames = Collections.unmodifiableMap(names);
    }

    private long parseDuration(String input) {
        Matcher m = DURATION.matcher(input.trim().toLowerCase(Locale.ROOT));
        long total = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "m" -> n * 60;
                case "h" -> n * 3600;
                case "d" -> n * 86400;
                default -> n;
            };
        }
        if (!any) {
            plugin.getLogger().warning("revive.cooldown không hợp lệ ('" + input + "'), dùng 10 phút.");
            return 600;
        }
        return total;
    }

    /** Tên hiển thị của loại mob (từ mob-names, mặc định: viết hoa chữ đầu). */
    public String typeName(String type) {
        String upper = type.toUpperCase(Locale.ROOT);
        String custom = mobNames.get(upper);
        if (custom != null) {
            return custom;
        }
        String lower = upper.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public boolean isAllowed(EntityType type) { return allowedMobs.contains(type); }
    public int registerTimeout() { return registerTimeout; }
    public boolean requireTamed() { return requireTamed; }
    public int snapshotInterval() { return snapshotInterval; }
    public boolean reviveFullHealth() { return reviveFullHealth; }
    public int defaultLimit() { return defaultLimit; }
    public Map<String, Integer> limitPermissions() { return limitPermissions; }
    public long reviveCooldownMillis() { return reviveCooldownMillis; }
    public boolean costEnabled() { return costEnabled && costAmount > 0; }
    public int costAmount() { return costAmount; }
    public boolean costLevels() { return costLevels; }
}
