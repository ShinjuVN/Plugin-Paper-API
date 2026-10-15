package vn.pab;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PabCommand implements CommandExecutor, TabCompleter {
    private final PAB plugin;

    public PabCommand(PAB plugin) { this.plugin = plugin; }

    private void say(CommandSender s, String msg) { s.sendMessage(plugin.color(msg)); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("pab.admin")) {
            say(sender, "&cBạn không có quyền dùng lệnh này.");
            return true;
        }
        if (args.length == 0) {
            say(sender, "&e/pab reload &7- nạp lại config & dữ liệu");
            say(sender, "&e/pab add <player> <tin nhắn từ chối ban> &7- thêm (có thể offline)");
            say(sender, "&e/pab remove <player> &7- gỡ bảo vệ (có thể offline)");
            say(sender, "&e/pab list &7- danh sách đang bảo vệ");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadAll();
                say(sender, "&a[PAB] Đã reload. Đang bảo vệ &e" + plugin.store().all().size() + " &angười chơi.");
            }
            case "list" -> {
                if (plugin.store().all().isEmpty()) { say(sender, "&7[PAB] Danh sách trống."); return true; }
                say(sender, "&6[PAB] Đang bảo vệ:");
                for (PabEntry e : plugin.store().all()) {
                    say(sender, "&e- " + e.name() + " &7(" + e.uuids().size() + " uuid, " + e.ips().size() + " ip)");
                }
            }
            case "add" -> {
                if (args.length < 2) { say(sender, "&cDùng: /pab add <player> <tin nhắn từ chối ban>"); return true; }
                String name = args[1];
                String msg = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
                add(sender, name, msg);
            }
            case "remove" -> {
                if (args.length < 2) { say(sender, "&cDùng: /pab remove <player>"); return true; }
                PabEntry e = plugin.store().findToken(args[1], false);
                if (e == null) { say(sender, "&c[PAB] &e" + args[1] + " &ckhông có trong danh sách."); return true; }
                plugin.store().remove(e);
                say(sender, "&a[PAB] Đã gỡ bảo vệ &e" + e.name() + "&a.");
            }
            default -> say(sender, "&cLệnh con không hợp lệ. Gõ /pab để xem trợ giúp.");
        }
        return true;
    }

    private void add(CommandSender sender, String name, String msg) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) { finish(sender, online.getName(), online.getUniqueId(), msg); return; }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            finish(sender, cached.getName() != null ? cached.getName() : name, cached.getUniqueId(), msg);
            return;
        }

        say(sender, "&7[PAB] Đang tra UUID của &e" + name + "&7...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID id = null;
            String real = name;
            if (!Bukkit.getOnlineMode()) {
                id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            } else {
                try {
                    com.destroystokyo.paper.profile.PlayerProfile pr = Bukkit.createProfile(name);
                    if (pr.complete(false) && pr.getId() != null) {
                        id = pr.getId();
                        if (pr.getName() != null) real = pr.getName();
                    }
                } catch (Throwable ignored) { }
            }
            final UUID fid = id;
            final String fname = real;
            Bukkit.getScheduler().runTask(plugin, () -> finish(sender, fname, fid, msg));
        });
    }

    private void finish(CommandSender sender, String name, UUID id, String msg) {
        boolean existed = plugin.store().findByName(name) != null;
        PabEntry e = plugin.store().add(name, id, msg);
        plugin.sweep(e);
        say(sender, (existed ? "&a[PAB] Đã cập nhật &e" : "&a[PAB] Đã thêm &e") + e.name()
                + (id == null ? " &7(không tra được UUID - bảo vệ theo tên, tự gắn UUID khi họ vào server)"
                : " &7(" + id + ")"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("pab.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("reload", "add", "remove", "list")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2) {
            String pre = args[1].toLowerCase(Locale.ROOT);
            if (args[0].equalsIgnoreCase("add")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(p.getName());
                }
            } else if (args[0].equalsIgnoreCase("remove")) {
                for (PabEntry e : plugin.store().all()) {
                    if (e.name().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(e.name());
                }
            }
        }
        return out;
    }
}
