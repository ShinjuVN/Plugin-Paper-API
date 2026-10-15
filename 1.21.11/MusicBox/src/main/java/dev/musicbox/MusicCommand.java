package dev.musicbox;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class MusicCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "musicbox.admin";
    private static final String USE = "musicbox.use";
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");

    private static final List<String> USER_SUBS =
            List.of("add", "del", "play", "global", "list", "setting", "cancel");
    private static final List<String> ADMIN_SUBS =
            List.of("reload", "delplayer", "ban", "unban", "globalmusic", "clearcache");

    private record Target(UUID uuid, String name) {
    }

    private final MusicBoxPlugin plugin;

    /** Những người đang chờ kiểm tra link (mỗi người một lượt, tránh spam request ra ngoài). */
    private final Set<UUID> verifying = ConcurrentHashMap.newKeySet();

    public MusicCommand(MusicBoxPlugin plugin) {
        this.plugin = plugin;
    }

    private DataManager data() {
        return plugin.data();
    }

    // ------------------------------------------------------------- dispatch

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (a.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);

        if (ADMIN_SUBS.contains(sub)) {
            if (!sender.hasPermission(ADMIN)) {
                plugin.send(sender, "no-permission");
                return true;
            }
            switch (sub) {
                case "reload" -> reload(sender);
                case "delplayer" -> delPlayer(sender, a);
                case "ban" -> ban(sender, a, true);
                case "unban" -> ban(sender, a, false);
                case "globalmusic" -> globalMusic(sender, a);
                case "clearcache" -> clearCache(sender);
                default -> sendHelp(sender);
            }
            return true;
        }

        if (USER_SUBS.contains(sub)) {
            if (!sender.hasPermission(USE)) {
                plugin.send(sender, "no-permission");
                return true;
            }
            switch (sub) {
                case "add" -> add(sender, a);
                case "del" -> del(sender, a);
                case "play" -> play(sender, a, false);
                case "global" -> play(sender, a, true);
                case "list" -> list(sender);
                case "setting" -> setting(sender);
                case "cancel" -> cancel(sender);
                default -> sendHelp(sender);
            }
            return true;
        }

        sendHelp(sender);
        return true;
    }

    private void sendHelp(CommandSender s) {
        for (String line : plugin.getConfig().getStringList("messages.help")) {
            s.sendMessage(plugin.color(line));
        }
        if (s.hasPermission(ADMIN)) {
            for (String line : plugin.getConfig().getStringList("messages.help-admin")) {
                s.sendMessage(plugin.color(line));
            }
        }
    }

    private Player requirePlayer(CommandSender s) {
        if (s instanceof Player p) return p;
        plugin.send(s, "player-only");
        return null;
    }

    private void usage(CommandSender s, String usage) {
        plugin.send(s, "usage", "usage", usage);
    }

    private List<String> allowedDomains() {
        return plugin.getConfig().getStringList("settings.allowed-domains");
    }

    /** Kiểm tra id và link; trả về true nếu hợp lệ (nếu không đã gửi thông báo lỗi). */
    private boolean validateIdAndLink(CommandSender s, String id, String link) {
        if (!ID_PATTERN.matcher(id).matches()) {
            plugin.send(s, "invalid-id");
            return false;
        }
        if (id.equals("all")) {
            plugin.send(s, "id-reserved", "id", id);
            return false;
        }
        String err = AudioLoader.validateLink(link, allowedDomains());
        if (err != null) {
            plugin.send(s, err, "domains", String.join(", ", allowedDomains()));
            return false;
        }
        return true;
    }

    // ------------------------------------------------------- player commands

    private void add(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null) return;
        if (a.length < 3) {
            usage(s, "/musicbox add <id> <link>");
            return;
        }
        PlayerData d = data().getOrCreate(p);
        if (d.banned) {
            plugin.send(s, "banned");
            return;
        }
        String id = a[1].toLowerCase(Locale.ROOT);
        String link = a[2];
        if (!validateIdAndLink(s, id, link)) return;
        if (!canAdd(s, d, id)) return; // kiểm tra sớm để khỏi tốn một lượt kiểm tra link

        verifyThen(s, p.getUniqueId(), link, () -> {
            if (!canAdd(s, d, id)) return; // trong lúc chờ có thể đã thêm trùng / đủ giới hạn
            d.songs.put(id, link);
            data().saveAsync();
            plugin.send(s, "song-added", "id", id);
        });
    }

    private boolean canAdd(CommandSender s, PlayerData d, String id) {
        if (d.songs.containsKey(id)) {
            plugin.send(s, "id-exists", "id", id);
            return false;
        }
        int max = plugin.getConfig().getInt("settings.max-songs-per-player", 20);
        if (max > 0 && d.songs.size() >= max && !s.hasPermission(ADMIN)) {
            plugin.send(s, "limit-reached", "max", String.valueOf(max));
            return false;
        }
        return true;
    }

    /**
     * Kiểm tra nhanh link có phải file âm thanh tải được không (chỉ tốn vài KB) rồi mới chạy onOk
     * trên luồng chính. Link chắc chắn hỏng (404, trang web, FLAC...) bị từ chối ngay lúc thêm;
     * lỗi tạm thời (host chậm/quá tải) thì vẫn cho thêm kèm cảnh báo.
     */
    private void verifyThen(CommandSender s, UUID guard, String link, Runnable onOk) {
        if (!plugin.getConfig().getBoolean("settings.verify-on-add", true)) {
            onOk.run();
            return;
        }
        if (!verifying.add(guard)) {
            plugin.send(s, "verifying");
            return;
        }
        plugin.send(s, "verifying");
        AudioLoader.Options opt = plugin.playback().options();
        AudioLoader.Ctx ctx = new AudioLoader.Ctx(null, plugin.cache());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Throwable failure = null;
            try {
                AudioLoader.probe(link, opt, ctx);
            } catch (IOException | UnsupportedAudioFileException e) {
                failure = e;
            } catch (Throwable e) {
                plugin.getLogger().warning("Lỗi bất ngờ khi kiểm tra link " + link + ": " + e);
                failure = e;
            }
            final Throwable f = failure;
            if (!plugin.isEnabled()) {
                verifying.remove(guard);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                verifying.remove(guard);
                if (f == null) {
                    onOk.run();
                } else if (AudioLoader.isDefinitiveFailure(f)) {
                    plugin.send(s, "link-check-failed", "reason",
                            f.getMessage() != null ? f.getMessage() : f.getClass().getSimpleName());
                } else {
                    plugin.send(s, "link-check-skipped");
                    onOk.run();
                }
            });
        });
    }

    private void del(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null) return;
        if (a.length < 2) {
            usage(s, "/musicbox del <id>");
            return;
        }
        String id = a[1].toLowerCase(Locale.ROOT);
        PlayerData d = data().getOrCreate(p);
        if (d.songs.remove(id) == null) {
            plugin.send(s, "song-not-found", "id", id);
            return;
        }
        if (id.equals(d.lastPlayed)) d.lastPlayed = null;
        plugin.playback().cancelIfPersonal(p.getUniqueId(), id);
        data().saveAsync();
        plugin.send(s, "song-deleted", "id", id);
    }

    private void play(CommandSender s, String[] a, boolean global) {
        Player p = requirePlayer(s);
        if (p == null) return;
        if (a.length < 2) {
            usage(s, global ? "/musicbox global <id>" : "/musicbox play <id>");
            return;
        }
        PlayerData d = data().getOrCreate(p);
        if (d.banned) {
            plugin.send(s, "banned");
            return;
        }
        String id = a[1].toLowerCase(Locale.ROOT);
        if (global) {
            String url = data().getGlobal(id);
            if (url == null) {
                plugin.send(s, "global-not-found", "id", id);
                return;
            }
            plugin.playback().play(p, PlaybackManager.Source.GLOBAL, id, url);
        } else {
            String url = d.songs.get(id);
            if (url == null) {
                plugin.send(s, "song-not-found", "id", id);
                return;
            }
            plugin.playback().play(p, PlaybackManager.Source.PERSONAL, id, url);
        }
    }

    private void list(CommandSender s) {
        String empty = plugin.raw("list-empty");
        String mine = empty;
        if (s instanceof Player p) {
            PlayerData d = data().getOrCreate(p);
            if (!d.songs.isEmpty()) mine = String.join(", ", d.songs.keySet());
            plugin.send(s, "list-personal", "list", mine);
        }
        String glob = data().global().isEmpty() ? empty : String.join(", ", data().global().keySet());
        plugin.send(s, "list-global", "list", glob);
    }

    private void setting(CommandSender s) {
        Player p = requirePlayer(s);
        if (p == null) return;
        SettingsGui.open(plugin, p);
    }

    private void cancel(CommandSender s) {
        Player p = requirePlayer(s);
        if (p == null) return;
        if (plugin.playback().cancel(p.getUniqueId())) plugin.send(s, "cancelled");
        else plugin.send(s, "nothing-playing");
    }

    // -------------------------------------------------------- admin commands

    private void reload(CommandSender s) {
        plugin.reloadConfig();
        plugin.applyCacheSettings();
        plugin.send(s, "reload-done");
    }

    private void clearCache(CommandSender s) {
        AudioCache c = plugin.cache();
        long bytes = c.sizeBytes();
        int n = c.clear();
        plugin.send(s, "cache-cleared", "count", String.valueOf(n),
                "size", String.format(Locale.ROOT, "%.1f", bytes / 1048576.0));
    }

    private Target resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return new Target(online.getUniqueId(), online.getName());

        UUID known = data().findUuidByName(name);
        if (known != null) {
            PlayerData d = data().peek(known);
            return new Target(known, d != null && d.name != null ? d.name : name);
        }
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        if (off != null) {
            return new Target(off.getUniqueId(), off.getName() != null ? off.getName() : name);
        }
        return null;
    }

    private void delPlayer(CommandSender s, String[] a) {
        if (a.length < 3) {
            usage(s, "/musicbox delplayer <player> <id/all>");
            return;
        }
        Target t = resolve(a[1]);
        if (t == null) {
            plugin.send(s, "player-not-found", "player", a[1]);
            return;
        }
        PlayerData d = data().peek(t.uuid());
        String id = a[2].toLowerCase(Locale.ROOT);
        int count;
        if (id.equals("all")) {
            count = d == null ? 0 : d.songs.size();
            if (d != null) {
                d.songs.clear();
                d.lastPlayed = null;
            }
            plugin.playback().cancelIfPersonal(t.uuid(), null);
        } else {
            if (d == null || d.songs.remove(id) == null) {
                plugin.send(s, "song-not-found", "id", id);
                return;
            }
            if (id.equals(d.lastPlayed)) d.lastPlayed = null;
            plugin.playback().cancelIfPersonal(t.uuid(), id);
            count = 1;
        }
        data().saveAsync();
        plugin.send(s, "delplayer-done", "player", t.name(), "id", id, "count", String.valueOf(count));
    }

    private void ban(CommandSender s, String[] a, boolean ban) {
        if (a.length < 2) {
            usage(s, ban ? "/musicbox ban <player>" : "/musicbox unban <player>");
            return;
        }
        Target t = resolve(a[1]);
        if (t == null) {
            plugin.send(s, "player-not-found", "player", a[1]);
            return;
        }
        PlayerData d = data().getOrCreate(t.uuid(), t.name());
        d.banned = ban;
        if (ban) plugin.playback().cancel(t.uuid());
        data().saveAsync();
        plugin.send(s, "ban-done", "player", t.name(),
                "action", plugin.raw(ban ? "action-ban" : "action-unban"));
    }

    private void globalMusic(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/musicbox globalmusic <add|del|list> ...");
            return;
        }
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (a.length < 4) {
                    usage(s, "/musicbox globalmusic add <id> <link>");
                    return;
                }
                String id = a[2].toLowerCase(Locale.ROOT);
                if (!validateIdAndLink(s, id, a[3])) return;
                if (data().getGlobal(id) != null) {
                    plugin.send(s, "global-exists", "id", id);
                    return;
                }
                String link = a[3];
                UUID guard = s instanceof Player gp ? gp.getUniqueId() : new UUID(0L, 0L);
                verifyThen(s, guard, link, () -> {
                    if (data().getGlobal(id) != null) {
                        plugin.send(s, "global-exists", "id", id);
                        return;
                    }
                    data().putGlobal(id, link);
                    data().saveAsync();
                    plugin.send(s, "global-done", "id", id, "action", plugin.raw("action-add"));
                });
            }
            case "del" -> {
                if (a.length < 3) {
                    usage(s, "/musicbox globalmusic del <id>");
                    return;
                }
                String id = a[2].toLowerCase(Locale.ROOT);
                if (!data().removeGlobal(id)) {
                    plugin.send(s, "global-not-found", "id", id);
                    return;
                }
                data().saveAsync();
                plugin.send(s, "global-done", "id", id, "action", plugin.raw("action-del"));
            }
            case "list" -> {
                String glob = data().global().isEmpty()
                        ? plugin.raw("list-empty") : String.join(", ", data().global().keySet());
                plugin.send(s, "list-global", "list", glob);
            }
            default -> usage(s, "/musicbox globalmusic <add|del|list> ...");
        }
    }

    // ----------------------------------------------------------- tab complete

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (a.length == 1) {
            List<String> subs = new ArrayList<>();
            if (s.hasPermission(USE)) subs.addAll(USER_SUBS);
            if (s.hasPermission(ADMIN)) subs.addAll(ADMIN_SUBS);
            return filter(subs, a[0]);
        }

        String sub = a[0].toLowerCase(Locale.ROOT);
        boolean admin = s.hasPermission(ADMIN);

        switch (sub) {
            case "del", "play" -> {
                if (a.length == 2 && s instanceof Player p) {
                    PlayerData d = data().peek(p.getUniqueId());
                    if (d != null) return filter(d.songs.keySet(), a[1]);
                }
            }
            case "global" -> {
                if (a.length == 2) return filter(data().global().keySet(), a[1]);
            }
            case "ban", "unban" -> {
                if (admin && a.length == 2) return filter(playerNames(), a[1]);
            }
            case "delplayer" -> {
                if (!admin) break;
                if (a.length == 2) return filter(playerNames(), a[1]);
                if (a.length == 3) {
                    List<String> opts = new ArrayList<>();
                    opts.add("all");
                    Target t = resolve(a[1]);
                    if (t != null) {
                        PlayerData d = data().peek(t.uuid());
                        if (d != null) opts.addAll(d.songs.keySet());
                    }
                    return filter(opts, a[2]);
                }
            }
            case "globalmusic" -> {
                if (!admin) break;
                if (a.length == 2) return filter(List.of("add", "del", "list"), a[1]);
                if (a.length == 3 && a[1].equalsIgnoreCase("del")) {
                    return filter(data().global().keySet(), a[2]);
                }
            }
            default -> {
            }
        }
        return Collections.emptyList();
    }

    private Collection<String> playerNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        names.addAll(data().knownNames());
        return names;
    }

    private static List<String> filter(Collection<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        Collections.sort(out);
        return out;
    }
}


