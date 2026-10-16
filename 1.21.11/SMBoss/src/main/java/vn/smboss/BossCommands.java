package vn.smboss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BossCommands implements CommandExecutor, TabCompleter {

    private final BossManager manager;

    public BossCommands(BossManager manager) {
        this.manager = manager;
    }

    private static void msg(CommandSender s, TextColor color, String text) {
        s.sendMessage(Component.text("[SMBoss] ", NamedTextColor.GOLD).append(Component.text(text, color)));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "smbreload" -> {
                manager.reload();
                msg(sender, NamedTextColor.GREEN, "Da tai lai boss-data.yml (" + manager.ids().size() + " boss).");
            }
            case "smboss" -> spawn(sender, args);
            case "cboss" -> create(sender, args);
            case "delboss" -> delete(sender, args);
            case "cdnbt" -> convert(sender, args);
            default -> { return false; }
        }
        return true;
    }

    private void spawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, NamedTextColor.RED, "Chi nguoi choi moi dung duoc lenh nay.");
            return;
        }
        if (args.length != 1) {
            msg(sender, NamedTextColor.RED, "Dung: /smboss <id>");
            return;
        }
        String error = manager.spawn(args[0], player.getLocation(), player.getName());
        if (error != null) msg(sender, NamedTextColor.RED, error);
        else msg(sender, NamedTextColor.GREEN, "Da trieu hoi boss '" + args[0] + "'.");
    }

    private void create(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, NamedTextColor.RED, "Dung: /cboss <id> <ma base64 nbt>");
            return;
        }
        String id = args[0];
        if (!BossManager.BOSS_ID.matcher(id).matches()) {
            msg(sender, NamedTextColor.RED, "ID chi gom chu, so, '_' , '-' (toi da 32 ky tu).");
            return;
        }
        String b64 = String.join("", java.util.Arrays.copyOfRange(args, 1, args.length));
        try {
            BossManager.Prepared p = BossManager.prepareFromBase64(b64);
            boolean isNew = manager.put(id, b64);
            msg(sender, NamedTextColor.GREEN, (isNew ? "Da tao" : "Da cap nhat NBT (giu nguyen drops/thong bao)") + " boss '" + id
                    + "' (mob: " + p.entityId() + "). Trieu hoi bang /smboss " + id);
        } catch (IllegalArgumentException e) {
            msg(sender, NamedTextColor.RED, "Loi: " + e.getMessage());
        }
    }

    private void delete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            msg(sender, NamedTextColor.RED, "Dung: /delboss <id>");
            return;
        }
        if (manager.remove(args[0])) msg(sender, NamedTextColor.GREEN, "Da xoa boss '" + args[0] + "'.");
        else msg(sender, NamedTextColor.RED, "Khong tim thay boss '" + args[0] + "'.");
    }

    private void convert(CommandSender sender, String[] args) {
        if (args.length == 0) {
            msg(sender, NamedTextColor.RED, "Dung: /cdnbt <nbt mob>  (lay NBT bang /data get entity <mob>)");
            return;
        }
        String raw = String.join(" ", args);
        try {
            BossManager.Prepared p = BossManager.prepareFromSnbt(raw);
            String b64 = BossManager.encode(Snbt.toString(p.nbt()));

            msg(sender, NamedTextColor.GREEN, "Da chuyen NBT (" + p.entityId() + ") sang Base64 - " + b64.length() + " ky tu.");

            if (sender instanceof Player) {
                Component open = Component.text("[Mo san lenh /cboss]", NamedTextColor.AQUA, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.suggestCommand("/cboss NhapID " + b64))
                        .hoverEvent(HoverEvent.showText(Component.text("Bam de dien san lenh vao o chat, nho doi 'NhapID'")));
                Component copy = Component.text("[Copy Base64]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.copyToClipboard(b64))
                        .hoverEvent(HoverEvent.showText(Component.text("Bam de copy ma Base64")));
                sender.sendMessage(open.append(Component.text("  ")).append(copy));
            } else {
                sender.sendMessage("/cboss NhapID " + b64);
            }
        } catch (IllegalArgumentException e) {
            msg(sender, NamedTextColor.RED, "Loi: " + e.getMessage());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1 && (name.equals("smboss") || name.equals("delboss"))) {
            List<String> out = new ArrayList<>();
            for (String id : manager.ids()) if (id.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(id);
            return out;
        }
        return List.of();
    }
}
