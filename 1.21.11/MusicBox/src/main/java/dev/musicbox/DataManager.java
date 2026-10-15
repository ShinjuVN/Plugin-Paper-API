package dev.musicbox;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Lưu dữ liệu vào data.yml. Bài hát được lưu dạng danh sách "id link"
 * để tránh việc YAML hiểu nhầm ID dạng số (vd: 007).
 */
public final class DataManager {

    private final MusicBoxPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private final Map<String, String> global = new LinkedHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MusicBox-IO");
        t.setDaemon(true);
        return t;
    });

    public DataManager(MusicBoxPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    // ------------------------------------------------------------------ load

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);

        for (String line : y.getStringList("global")) {
            String[] kv = split(line);
            if (kv != null) global.put(kv[0], kv[1]);
        }

        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps == null) return;
        for (String key : ps.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection s = ps.getConfigurationSection(key);
            if (s == null) continue;

            PlayerData d = new PlayerData();
            d.name = s.getString("name");
            d.banned = s.getBoolean("banned", false);
            d.listenOthers = s.getBoolean("settings.listen-others", true);
            d.repeat = s.getBoolean("settings.repeat", false);
            try {
                d.mode = PlaybackMode.valueOf(s.getString("settings.mode", "PLAYLIST").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                d.mode = PlaybackMode.PLAYLIST;
            }
            d.lastPlayed = s.getString("last-played");
            for (String line : s.getStringList("songs")) {
                String[] kv = split(line);
                if (kv != null) d.songs.put(kv[0], kv[1]);
            }
            players.put(uuid, d);
        }
    }

    private static String[] split(String line) {
        if (line == null) return null;
        int i = line.indexOf(' ');
        if (i <= 0 || i == line.length() - 1) return null;
        return new String[]{line.substring(0, i), line.substring(i + 1).trim()};
    }

    // ------------------------------------------------------------------ save

    private synchronized String serialize() {
        YamlConfiguration y = new YamlConfiguration();

        List<String> g = new ArrayList<>();
        for (Map.Entry<String, String> e : global.entrySet()) g.add(e.getKey() + " " + e.getValue());
        y.set("global", g);

        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            PlayerData d = e.getValue();
            String base = "players." + e.getKey();
            y.set(base + ".name", d.name);
            y.set(base + ".banned", d.banned);
            y.set(base + ".settings.listen-others", d.listenOthers);
            y.set(base + ".settings.repeat", d.repeat);
            y.set(base + ".settings.mode", d.mode.name());
            y.set(base + ".last-played", d.lastPlayed);
            List<String> songs = new ArrayList<>();
            for (Map.Entry<String, String> s : d.songs.entrySet()) songs.add(s.getKey() + " " + s.getValue());
            y.set(base + ".songs", songs);
        }
        return y.saveToString();
    }

    private void write(String content) {
        try {
            File dir = file.getParentFile();
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            File tmp = new File(dir, "data.yml.tmp");
            Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Không thể lưu data.yml", e);
        }
    }

    public void saveAsync() {
        String content = serialize();
        if (io.isShutdown()) {
            write(content);
            return;
        }
        io.execute(() -> write(content));
    }

    public void shutdown() {
        saveAsync();
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- players

    public PlayerData peek(UUID uuid) {
        return players.get(uuid);
    }

    public PlayerData getOrCreate(Player p) {
        return getOrCreate(p.getUniqueId(), p.getName());
    }

    public PlayerData getOrCreate(UUID uuid, String name) {
        PlayerData d = players.computeIfAbsent(uuid, k -> new PlayerData());
        if (name != null) d.name = name;
        return d;
    }

    public UUID findUuidByName(String name) {
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            String n = e.getValue().name;
            if (n != null && n.equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    public Collection<String> knownNames() {
        List<String> names = new ArrayList<>();
        for (PlayerData d : players.values()) {
            if (d.name != null) names.add(d.name);
        }
        return names;
    }

    // ----------------------------------------------------------------- global

    public Map<String, String> global() {
        return global;
    }

    public String getGlobal(String id) {
        return global.get(id);
    }

    public void putGlobal(String id, String url) {
        global.put(id, url);
    }

    public boolean removeGlobal(String id) {
        return global.remove(id) != null;
    }
}


