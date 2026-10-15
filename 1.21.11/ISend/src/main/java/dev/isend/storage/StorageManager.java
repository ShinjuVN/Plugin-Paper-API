package dev.isend.storage;

import dev.isend.ISendPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Per-player YAML storage: plugins/ISend/userdata/&lt;uuid&gt;.yml.
 * Main-thread only. Every mutation must be followed by {@link #save(PlayerData)}.
 */
public final class StorageManager {

    private final ISendPlugin plugin;
    private final File dir;
    private final Map<UUID, PlayerData> cache = new HashMap<>();

    public StorageManager(ISendPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "userdata");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Could not create " + dir.getPath());
        }
    }

    public PlayerData get(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data == null) {
            data = load(uuid);
            // Only online players are cached; offline data is re-read so it never goes stale or leaks.
            if (Bukkit.getPlayer(uuid) != null) {
                cache.put(uuid, data);
            }
        }
        return data;
    }

    /** Drops the cached copy (data is always saved on change). */
    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    private File file(UUID uuid) {
        return new File(dir, uuid + ".yml");
    }

    private PlayerData load(UUID uuid) {
        PlayerData data = new PlayerData(uuid);
        File file = file(uuid);
        if (!file.exists()) {
            return data;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        data.setReceiving(yaml.getBoolean("receive", true));
        readUuids(yaml.getStringList("blocked"), data.blocked());
        readUuids(yaml.getStringList("exceptions"), data.exceptions());

        ConfigurationSection mails = yaml.getConfigurationSection("mails");
        if (mails != null) {
            for (String key : mails.getKeys(false)) {
                ConfigurationSection section = mails.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                try {
                    UUID id = UUID.fromString(key);
                    UUID sender = UUID.fromString(section.getString("sender", ""));
                    String senderName = section.getString("sender-name", "?");
                    long time = section.getLong("time", System.currentTimeMillis());
                    ItemStack item = ItemStack.deserializeBytes(
                            Base64.getDecoder().decode(section.getString("item", "")));
                    data.addMail(new MailEntry(id, sender, senderName, time, item));
                } catch (Exception ex) {
                    data.unreadable().put(key, new HashMap<>(section.getValues(false)));
                    plugin.getLogger().log(Level.WARNING,
                            "Could not read mail entry " + key + " of " + uuid + " (kept in file)", ex);
                }
            }
        }
        return data;
    }

    private static void readUuids(List<String> raw, java.util.Collection<UUID> target) {
        for (String s : raw) {
            try {
                target.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // skip invalid entry
            }
        }
    }

    /**
     * Writes the player's file atomically. Offline players are evicted from the cache afterwards;
     * on failure the cached copy is discarded so memory never disagrees with disk.
     */
    public boolean save(PlayerData data) {
        UUID uuid = data.uuid();
        File file = file(uuid);
        try {
            if (data.isDefault()) {
                Files.deleteIfExists(file.toPath());
            } else {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("receive", data.isReceiving());
                yaml.set("blocked", toStrings(data.blocked()));
                yaml.set("exceptions", toStrings(data.exceptions()));
                for (MailEntry mail : data.mails()) {
                    String base = "mails." + mail.id();
                    yaml.set(base + ".sender", mail.senderId().toString());
                    yaml.set(base + ".sender-name", mail.senderName());
                    yaml.set(base + ".time", mail.timestamp());
                    yaml.set(base + ".item",
                            Base64.getEncoder().encodeToString(mail.item().serializeAsBytes()));
                }
                for (Map.Entry<String, Map<String, Object>> raw : data.unreadable().entrySet()) {
                    for (Map.Entry<String, Object> field : raw.getValue().entrySet()) {
                        yaml.set("mails." + raw.getKey() + "." + field.getKey(), field.getValue());
                    }
                }
                Path target = file.toPath();
                Path tmp = target.resolveSibling(file.getName() + ".tmp");
                Files.writeString(tmp, yaml.saveToString(), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save mail data of " + uuid, ex);
            cache.remove(uuid);
            return false;
        }
        if (Bukkit.getPlayer(uuid) == null) {
            cache.remove(uuid);
        }
        return true;
    }

    public void saveAll() {
        for (PlayerData data : new ArrayList<>(cache.values())) {
            save(data);
        }
    }

    private static List<String> toStrings(java.util.Collection<UUID> uuids) {
        List<String> out = new ArrayList<>(uuids.size());
        for (UUID uuid : uuids) {
            out.add(uuid.toString());
        }
        return out;
    }
}
