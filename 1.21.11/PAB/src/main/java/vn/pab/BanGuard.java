package vn.pab;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;

/** Lõi phát hiện: một dòng lệnh có đang cố ban người được bảo vệ không. */
public final class BanGuard {
    private final PAB plugin;
    private Set<String> banCommands = Set.of();
    private Set<String> excluded = Set.of();
    private Set<String> wrappers = Set.of();
    private boolean fuzzy, partial, protectIp;
    private int partialArgs, scanArgs;

    public BanGuard(PAB plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();
        banCommands = lower(c.getStringList("ban-commands"));
        excluded = lower(c.getStringList("excluded-commands"));
        wrappers = lower(c.getStringList("wrapper-commands"));
        fuzzy = c.getBoolean("fuzzy-ban-match", true);
        partial = c.getBoolean("partial-match", true);
        partialArgs = c.getInt("partial-match-args", 2);
        scanArgs = c.getInt("scan-args", 0);
        protectIp = c.getBoolean("protect-ip", true);
    }

    private static Set<String> lower(List<String> in) {
        Set<String> s = new HashSet<>();
        for (String x : in) s.add(x.toLowerCase(Locale.ROOT));
        return s;
    }

    // ------------------------------------------------------------ nhận diện lệnh
    public static String base(String label) {
        String s = label.trim().toLowerCase(Locale.ROOT);
        while (s.startsWith("/")) s = s.substring(1);
        int i = s.indexOf(':');
        if (i >= 0 && i < s.length() - 1) s = s.substring(i + 1);
        return s;
    }

    public boolean isBanName(String raw, boolean allowFuzzy) {
        String b = base(raw);
        if (b.isEmpty() || b.equals("pab")) return false;
        if (excluded.contains(b)) return false;
        if (banCommands.contains(b)) return true;
        if (!fuzzy || !allowFuzzy) return false;
        if (b.startsWith("un") || b.contains("pardon") || b.contains("list")
                || b.contains("history") || b.contains("info")) return false;
        return b.contains("ban");
    }

    public boolean isBanCommand(Command c) {
        if (isBanName(c.getName(), true)) return true;
        for (String a : c.getAliases()) if (isBanName(a, true)) return true;
        return false;
    }

    public boolean isWrapperName(String raw) { return wrappers.contains(base(raw)); }

    /** Lệnh nào sẽ bị bọc trong CommandMap. */
    public boolean isInjectTarget(Command c) {
        if (c.getName().equalsIgnoreCase("pab")) return false;
        return isBanCommand(c) || isWrapperName(c.getName());
    }

    // ------------------------------------------------------------ kiểm tra
    /** @return entry bị nhắm tới, hoặc null nếu lệnh an toàn. */
    public PabEntry inspect(CommandSender sender, String label, String[] args) {
        String base = base(label);
        if (base.equals("pab")) return null;

        boolean ban = isBanName(label, true);
        if (!ban) {
            Command cmd = null;
            try { cmd = Bukkit.getCommandMap().getCommand(base(label)); } catch (Throwable ignored) { }
            if (cmd != null && isBanCommand(cmd)) ban = true;
        }
        // kiểu "/litebans ban Steve", "/punish Steve ban"
        if (!ban && args.length > 0 && isBanName(args[0], false)) ban = true;

        boolean wrapper = isWrapperName(label);
        if (!ban && wrapper) {
            for (String a : args) if (isBanName(a, false)) { ban = true; break; }
        }
        if (!ban) return null;

        int limit = (wrapper || scanArgs <= 0) ? args.length : Math.min(scanArgs, args.length);
        for (int i = 0; i < limit; i++) {
            PabEntry e = matchToken(sender, args[i], i);
            if (e != null) return e;
        }
        return null;
    }

    private PabEntry matchToken(CommandSender sender, String raw, int index) {
        String t = raw.trim().replace("\"", "").replace("'", "");
        if (t.isEmpty()) return null;
        if (t.indexOf(',') >= 0) {
            for (String part : t.split(",")) {
                PabEntry e = single(sender, part.trim(), index);
                if (e != null) return e;
            }
            return null;
        }
        return single(sender, t, index);
    }

    private PabEntry single(CommandSender sender, String t, int index) {
        if (t.isEmpty()) return null;
        ProtectedStore store = plugin.store();

        if (t.startsWith("@")) {
            if (!Bukkit.isPrimaryThread()) return null;
            try {
                for (Entity en : Bukkit.selectEntities(sender, t)) {
                    if (en instanceof Player p) {
                        PabEntry e = store.resolve(p.getUniqueId(), p.getName());
                        if (e != null) return e;
                    }
                }
            } catch (Throwable ignored) { }
            return null;
        }

        PabEntry e = store.findToken(t, protectIp);
        if (e != null) return e;

        if (partial && index < partialArgs && t.length() >= 3) {
            String lt = t.toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(lt)) {
                    PabEntry pe = store.resolve(p.getUniqueId(), p.getName());
                    if (pe != null) return pe;
                }
            }
        }
        return null;
    }
}
