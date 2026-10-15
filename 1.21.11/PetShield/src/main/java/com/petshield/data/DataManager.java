package com.petshield.data;

import com.petshield.PetShieldPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lưu mỗi người chơi 1 file data/<uuid>.yml. Bộ nhớ chỉ truy cập ở main thread;
 * đọc file chạy async rồi hoán đổi ở main thread, ghi file chạy async (build chuỗi YAML ở main thread).
 */
public final class DataManager {

    private final PetShieldPlugin plugin;
    private final File dir;
    private final Object writeLock = new Object();

    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final Map<UUID, PetRecord> byEntity = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();

    public DataManager(PetShieldPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "data");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    // ---------------------------------------------------------------- truy cập

    public PlayerData get(UUID owner) {
        return players.computeIfAbsent(owner, PlayerData::new);
    }

    public PetRecord findByEntity(UUID entityUuid) {
        return byEntity.get(entityUuid);
    }

    public void index(PetRecord rec) {
        if (rec.entityUuid != null) {
            byEntity.put(rec.entityUuid, rec);
        }
    }

    public void unindex(PetRecord rec) {
        if (rec.entityUuid != null) {
            byEntity.remove(rec.entityUuid, rec);
        }
    }

    public void remove(PetRecord rec) {
        unindex(rec);
        get(rec.owner).remove(rec.id);
        markDirty(rec.owner);
    }

    /** Bản sao danh sách pet đang sống (an toàn để duyệt và sửa). */
    public List<PetRecord> aliveRecords() {
        List<PetRecord> out = new ArrayList<>();
        for (PetRecord r : byEntity.values()) {
            if (r.alive) {
                out.add(r);
            }
        }
        return out;
    }

    public void markDirty(UUID owner) {
        dirty.add(owner);
    }

    // ---------------------------------------------------------------- đọc

    /** Đọc toàn bộ file ở thread khác rồi hoán đổi dữ liệu ở main thread. */
    public void loadAsync(Runnable afterSwap) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, PlayerData> loaded = new HashMap<>();
            File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
            if (files != null) {
                for (File f : files) {
                    try {
                        String base = f.getName().substring(0, f.getName().length() - 4);
                        UUID owner = UUID.fromString(base);
                        loaded.put(owner, read(owner, f));
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Không đọc được " + f.getName() + ": " + ex.getMessage());
                    }
                }
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    players.clear();
                    byEntity.clear();
                    dirty.clear();
                    players.putAll(loaded);
                    for (PlayerData pd : loaded.values()) {
                        for (PetRecord r : pd.pets()) {
                            index(r);
                        }
                    }
                    if (afterSwap != null) {
                        afterSwap.run();
                    }
                });
            } catch (IllegalStateException ignored) {
                // plugin đã bị tắt trong lúc đọc
            }
        });
    }

    private PlayerData read(UUID owner, File file) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        PlayerData pd = new PlayerData(owner);
        pd.setNextId(Math.max(1, y.getInt("next-id", 1)));

        ConfigurationSection sec = y.getConfigurationSection("pets");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                try {
                    PetRecord r = new PetRecord(owner, Integer.parseInt(key));
                    r.name = s.getString("name", "Pet #" + key);
                    r.type = s.getString("type", "UNKNOWN");
                    String eu = s.getString("entity-uuid");
                    r.entityUuid = eu == null ? null : UUID.fromString(eu);
                    r.alive = s.getBoolean("alive", true);
                    r.deathWorld = s.getString("death-world");
                    r.deathX = s.getDouble("death-x");
                    r.deathY = s.getDouble("death-y");
                    r.deathZ = s.getDouble("death-z");
                    r.deathTime = s.getLong("death-time");
                    r.registered = s.getLong("registered");
                    r.lastRevive = s.getLong("last-revive");
                    r.snapshot = s.getString("snapshot");
                    pd.add(r);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Bỏ qua pet lỗi #" + key + " của " + owner);
                }
            }
        }
        return pd;
    }

    // ---------------------------------------------------------------- ghi

    public void startAutoFlush() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushAsync, 100L, 100L);
    }

    public void flushAsync() {
        if (dirty.isEmpty()) {
            return;
        }
        Map<UUID, String> out = buildDirty();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> out.forEach(this::write));
    }

    public void flushSync() {
        if (dirty.isEmpty()) {
            return;
        }
        buildDirty().forEach(this::write);
    }

    private Map<UUID, String> buildDirty() {
        Map<UUID, String> out = new HashMap<>();
        for (UUID owner : dirty) {
            PlayerData pd = players.get(owner);
            if (pd != null) {
                out.put(owner, toYaml(pd));
            }
        }
        dirty.clear();
        return out;
    }

    private String toYaml(PlayerData pd) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", pd.nextId());
        Collection<PetRecord> pets = pd.pets();
        for (PetRecord r : pets) {
            String p = "pets." + r.id + ".";
            y.set(p + "name", r.name);
            y.set(p + "type", r.type);
            if (r.entityUuid != null) {
                y.set(p + "entity-uuid", r.entityUuid.toString());
            }
            y.set(p + "alive", r.alive);
            if (r.deathWorld != null) {
                y.set(p + "death-world", r.deathWorld);
                y.set(p + "death-x", r.deathX);
                y.set(p + "death-y", r.deathY);
                y.set(p + "death-z", r.deathZ);
                y.set(p + "death-time", r.deathTime);
            }
            y.set(p + "registered", r.registered);
            y.set(p + "last-revive", r.lastRevive);
            if (r.snapshot != null) {
                y.set(p + "snapshot", r.snapshot);
            }
        }
        return y.saveToString();
    }

    private void write(UUID owner, String content) {
        File target = new File(dir, owner + ".yml");
        File tmp = new File(dir, owner + ".yml.tmp");
        synchronized (writeLock) {
            try {
                Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp.toPath(), target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                plugin.getLogger().warning("Không ghi được dữ liệu " + target.getName() + ": " + ex.getMessage());
            }
        }
    }
}
