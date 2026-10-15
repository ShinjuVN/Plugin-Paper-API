package vn.pab;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ProtectedStore {
    private final PAB plugin;
    private final File file;
    private final Map<String, PabEntry> byName = new ConcurrentHashMap<>();
    private final Map<UUID, PabEntry> byUuid = new ConcurrentHashMap<>();
    private final Map<String, PabEntry> byIp = new ConcurrentHashMap<>();
    private final Set<PabEntry> entries = ConcurrentHashMap.newKeySet();
    private volatile boolean dirty;

    public ProtectedStore(PAB plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    // ------------------------------------------------------------ persistence
    public synchronized void load() {
        byName.clear();
        byUuid.clear();
        byIp.clear();
        entries.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> raw : yml.getMapList("players")) {
            Object n = raw.get("name");
            if (n == null) continue;
            PabEntry e = new PabEntry(String.valueOf(n));
            Object m = raw.get("message");
            if (m != null && !String.valueOf(m).isBlank()) e.setMessage(String.valueOf(m));
            for (String s : strings(raw.get("names"))) e.names().add(s.toLowerCase(Locale.ROOT));
            for (String s : strings(raw.get("uuids"))) {
                try { e.uuids().add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
            }
            for (String s : strings(raw.get("ips"))) {
                try { e.ips().put(s, InetAddress.getByName(s)); } catch (Exception ignored) { }
            }
            index(e);
        }
        dirty = false;
    }

    public synchronized void save() {
        YamlConfiguration yml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (PabEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.name());
            m.put("message", e.message() == null ? "" : e.message());
            m.put("names", new ArrayList<>(e.names()));
            List<String> u = new ArrayList<>();
            for (UUID id : e.uuids()) u.add(id.toString());
            m.put("uuids", u);
            m.put("ips", new ArrayList<>(e.ips().keySet()));
            list.add(m);
        }
        yml.set("players", list);
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().severe("Không lưu được data.yml: " + ex.getMessage());
        }
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof Collection<?> c) for (Object x : c) out.add(String.valueOf(x));
        return out;
    }

    private void index(PabEntry e) {
        entries.add(e);
        for (String n : e.names()) byName.put(n, e);
        for (UUID id : e.uuids()) byUuid.put(id, e);
        for (String ip : e.ips().keySet()) byIp.put(ip, e);
    }

    // ------------------------------------------------------------ queries
    public Collection<PabEntry> all() { return entries; }

    public PabEntry findByName(String name) {
        return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
    }

    public PabEntry findByUuid(UUID id) {
        return id == null ? null : byUuid.get(id);
    }

    /** Tìm theo token bất kỳ: tên, UUID (có/không gạch), hoặc IP. */
    public PabEntry findToken(String token, boolean withIp) {
        if (token == null || token.isEmpty()) return null;
        PabEntry e = byName.get(token.toLowerCase(Locale.ROOT));
        if (e != null) return e;
        UUID id = parseUuid(token);
        if (id != null && (e = byUuid.get(id)) != null) return e;
        if (withIp && (e = byIp.get(token)) != null) return e;
        return null;
    }

    /** Dùng cho đăng nhập/kick: ưu tiên UUID rồi tới tên. */
    public PabEntry resolve(UUID id, String name) {
        PabEntry e = findByUuid(id);
        return e != null ? e : findByName(name);
    }

    public static UUID parseUuid(String s) {
        try {
            if (s.length() == 36) return UUID.fromString(s);
            if (s.length() == 32) {
                return UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                        + "-" + s.substring(16, 20) + "-" + s.substring(20));
            }
        } catch (IllegalArgumentException ignored) { }
        return null;
    }

    // ------------------------------------------------------------ mutations
    public synchronized PabEntry add(String name, UUID id, String message) {
        PabEntry e = findByName(name);
        if (e == null && id != null) e = findByUuid(id);
        if (e == null) e = new PabEntry(name);
        e.setName(name);
        e.names().add(name.toLowerCase(Locale.ROOT));
        if (id != null) e.uuids().add(id);
        e.setMessage(message == null || message.isBlank() ? null : message);
        index(e);
        save();
        return e;
    }

    public synchronized boolean remove(PabEntry e) {
        if (!entries.remove(e)) return false;
        byName.values().removeIf(x -> x == e);
        byUuid.values().removeIf(x -> x == e);
        byIp.values().removeIf(x -> x == e);
        save();
        return true;
    }

    /** Cập nhật thông tin quan sát được khi người chơi đăng nhập. */
    public void touch(PabEntry e, UUID id, String name, InetAddress ip, boolean protectIp) {
        boolean changed = false;
        if (id != null && e.uuids().add(id)) { byUuid.put(id, e); changed = true; }
        if (name != null) {
            String l = name.toLowerCase(Locale.ROOT);
            if (e.names().add(l)) { byName.put(l, e); changed = true; }
            if (!name.equals(e.name())) { e.setName(name); changed = true; }
        }
        if (protectIp && ip != null) {
            String s = ip.getHostAddress();
            if (e.ips().putIfAbsent(s, ip) == null) { byIp.put(s, e); changed = true; }
        }
        if (changed) dirty = true;
    }
}
