package vn.smboss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public final class BossManager {

    public static final Pattern BOSS_ID = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");
    private static final Pattern ENTITY_ID = Pattern.compile("[a-z0-9_.\\-]+(:[a-z0-9_./\\-]+)?");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private static final List<String> DEFAULT_MESSAGE = List.of(
            "&8&m------------------------------------------",
            "&c&l⚠ BOSS XUẤT HIỆN ⚠",
            "{boss} &7đã được triệu hồi bởi &e{player}",
            "&7Tọa độ: &f{world} &7(&f{x}, {y}, {z}&7)",
            "&8&m------------------------------------------");

    public record Prepared(String entityId, Map<String, Object> nbt) {}

    private final SMBossPlugin plugin;
    private final File file;
    private final Map<String, BossData> bosses = new LinkedHashMap<>();

    public BossManager(SMBossPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "boss-data.yml");
    }

    // ---------- Load / Save ----------

    public void reload() {
        bosses.clear();
        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            plugin.saveResource("boss-data.yml", false); // 4 boss mau
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("bosses");
        if (sec == null) return;

        for (String id : sec.getKeys(false)) {
            BossData d = new BossData();
            if (sec.isString(id)) {                       // dinh dang cu:  id: <base64>
                d.nbt = sec.getString(id);
            } else {
                ConfigurationSection b = sec.getConfigurationSection(id);
                if (b == null) continue;
                d.nbt = b.getString("nbt");
                d.name = b.getString("name");
                d.exp = b.getInt("exp", -1);
                List<String> msg = b.getStringList("spawn-message");
                d.spawnMessage = msg.isEmpty() ? null : msg;
                d.rawDrops = new ArrayList<>(b.getMapList("drops"));
            }
            if (d.nbt == null || d.nbt.isBlank()) {
                plugin.getLogger().warning("Boss '" + id + "' thieu 'nbt', bo qua.");
                continue;
            }
            d.nbt = d.nbt.replaceAll("\\s", "");
            d.drops = parseDrops(id, d.rawDrops);
            bosses.put(id, d);
        }
    }

    private List<BossData.Drop> parseDrops(String bossId, List<Map<?, ?>> raw) {
        List<BossData.Drop> out = new ArrayList<>();
        int index = 0;
        for (Map<?, ?> m : raw) {
            index++;
            try {
                Material mat = Material.matchMaterial(String.valueOf(m.get("item")));
                if (mat == null || mat.isAir() || !mat.isItem()) throw new IllegalArgumentException("item khong hop le: " + m.get("item"));

                int min = 1, max = 1;
                Object amountObj = m.get("amount");
                if (amountObj != null) {
                    String a = String.valueOf(amountObj).trim();
                    int dash = a.indexOf('-', 1);
                    if (dash > 0) {
                        min = Integer.parseInt(a.substring(0, dash).trim());
                        max = Integer.parseInt(a.substring(dash + 1).trim());
                    } else {
                        min = max = Integer.parseInt(a);
                    }
                }
                min = Math.max(1, min);
                max = Math.max(min, max);

                double chance = 100.0;
                if (m.get("chance") != null) chance = Double.parseDouble(String.valueOf(m.get("chance")));

                Component name = m.get("name") != null ? LEGACY.deserialize(String.valueOf(m.get("name"))) : null;

                List<Component> lore = new ArrayList<>();
                if (m.get("lore") instanceof List<?> l) for (Object o : l) lore.add(LEGACY.deserialize(String.valueOf(o)));

                Map<String, Integer> enchants = new LinkedHashMap<>();
                if (m.get("enchants") instanceof Map<?, ?> em) {
                    for (Map.Entry<?, ?> e : em.entrySet()) {
                        enchants.put(String.valueOf(e.getKey()), Integer.parseInt(String.valueOf(e.getValue())));
                    }
                }
                out.add(new BossData.Drop(mat, min, max, chance, name, lore, enchants));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Boss '" + bossId + "' drop #" + index + " loi: " + ex.getMessage());
            }
        }
        return out;
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "SMBoss - du lieu boss",
                "nbt: base64 cua NBT mob | name: ten hien thi (&mau) | exp: exp roi ra (-1 = mac dinh)",
                "spawn-message: cac dong thong bao ({boss} {player} {world} {x} {y} {z})",
                "drops: item (ten Material), amount (so hoac min-max), chance (% 0-100),",
                "       name, lore (list), enchants (vd: sharpness: 5) - sach enchant dung ENCHANTED_BOOK"));
        ConfigurationSection sec = yml.createSection("bosses");
        for (Map.Entry<String, BossData> e : bosses.entrySet()) {
            BossData d = e.getValue();
            ConfigurationSection s = sec.createSection(e.getKey());
            s.set("nbt", d.nbt);
            if (d.name != null) s.set("name", d.name);
            if (d.exp >= 0) s.set("exp", d.exp);
            if (d.spawnMessage != null) s.set("spawn-message", d.spawnMessage);
            if (!d.rawDrops.isEmpty()) s.set("drops", d.rawDrops);
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Khong the luu boss-data.yml: " + ex.getMessage());
        }
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(bosses.keySet());
    }

    public BossData get(String id) {
        return bosses.get(id);
    }

    /** Tao moi hoac chi thay NBT (giu nguyen drops/name/message cu). @return true neu la boss moi. */
    public boolean put(String id, String base64) {
        BossData d = bosses.get(id);
        boolean isNew = d == null;
        if (isNew) {
            d = new BossData();
            bosses.put(id, d);
        }
        d.nbt = base64.replaceAll("\\s", "");
        save();
        return isNew;
    }

    public boolean remove(String id) {
        boolean removed = bosses.remove(id) != null;
        if (removed) save();
        return removed;
    }

    // ---------- NBT <-> Base64 ----------

    public static String encode(String snbt) {
        return Base64.getEncoder().encodeToString(snbt.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] decode(String b64) {
        String clean = b64.replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            try {
                return Base64.getUrlDecoder().decode(clean);
            } catch (IllegalArgumentException e2) {
                throw new IllegalArgumentException("Base64 khong hop le");
            }
        }
    }

    public static Prepared prepareFromSnbt(String text) {
        return prepare(Snbt.extractAndParse(text));
    }

    public static Prepared prepareFromBase64(String b64) {
        return prepareFromSnbt(new String(decode(b64), StandardCharsets.UTF_8));
    }

    private static Prepared prepare(Map<String, Object> nbt) {
        Snbt.filterEntity(nbt);
        Object id = nbt.get("id");
        if (!(id instanceof String raw)) throw new IllegalArgumentException("NBT khong co truong id (loai mob)");
        String entityId = Snbt.unquote(raw);
        if (!ENTITY_ID.matcher(entityId).matches()) throw new IllegalArgumentException("id mob khong hop le: " + entityId);
        return new Prepared(entityId, nbt);
    }

    // ---------- Spawn + thong bao ----------

    /** @return null neu thanh cong, nguoc lai la thong bao loi. */
    public String spawn(String bossId, Location loc, String spawnerName) {
        BossData data = bosses.get(bossId);
        if (data == null) return "Khong tim thay boss '" + bossId + "'.";

        Prepared p;
        try {
            p = prepareFromBase64(data.nbt);
        } catch (IllegalArgumentException e) {
            return "Du lieu boss loi: " + e.getMessage();
        }

        Map<String, Object> nbt = p.nbt();
        nbt.remove("id");
        addTags(nbt, bossId);

        String cmd = String.format(Locale.ROOT, "execute in %s run summon %s %.4f %.4f %.4f %s",
                loc.getWorld().getKey().asString(), p.entityId(), loc.getX(), loc.getY(), loc.getZ(),
                Snbt.toString(nbt));
        if (cmd.indexOf('\n') >= 0 || cmd.indexOf('\r') >= 0) return "NBT chua ky tu xuong dong, khong the summon.";

        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)) {
            return "Lenh summon that bai (xem console de biet ly do).";
        }
        announce(bossId, data, loc, spawnerName);
        return null;
    }

    private void announce(String bossId, BossData data, Location loc, String spawner) {
        String bossName = data.name != null ? data.name : "&c&l" + bossId;
        List<String> lines = data.spawnMessage != null ? data.spawnMessage : DEFAULT_MESSAGE;

        for (String line : lines) {
            String text = line
                    .replace("{boss}", bossName)
                    .replace("{player}", spawner)
                    .replace("{world}", loc.getWorld().getName())
                    .replace("{x}", String.valueOf(loc.getBlockX()))
                    .replace("{y}", String.valueOf(loc.getBlockY()))
                    .replace("{z}", String.valueOf(loc.getBlockZ()));
            Bukkit.broadcast(LEGACY.deserialize(text));
        }

        Title title = Title.title(LEGACY.deserialize(bossName), LEGACY.deserialize("&7đã xuất hiện!"));
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.showTitle(title);
            pl.playSound(pl.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8f, 1.0f);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addTags(Map<String, Object> nbt, String bossId) {
        List<Object> tags = nbt.get("Tags") instanceof List<?> l ? new ArrayList<>((List<Object>) l) : new ArrayList<>();
        for (String t : new String[]{"smboss", "smboss_" + bossId}) {
            String q = "\"" + t + "\"";
            if (!tags.contains(q) && !tags.contains("'" + t + "'")) tags.add(q);
        }
        nbt.put("Tags", tags);
    }
}
