package dev.isend.command;

import dev.isend.ISendPlugin;
import dev.isend.config.ConfigManager;
import dev.isend.gui.InboxGui;
import dev.isend.storage.PlayerData;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class ISendCommand implements TabExecutor {

    private static final String PERM_USE = "isend.use";
    private static final String PERM_ADMIN = "isend.admin";
    private static final List<String> USER_SUBS =
            List.of("to", "gui", "inbox", "from", "toggle", "exception", "block", "help");
    private static final List<String> ADMIN_SUBS = List.of("reload", "see", "config");
    private static final Set<String> NAME_ARG_SUBS = Set.of("to", "from", "exception", "block", "see");

    private final ISendPlugin plugin;

    public ISendCommand(ISendPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager cfg = plugin.config();
        String sub = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
        boolean adminSub = ADMIN_SUBS.contains(sub);

        if (!sender.hasPermission(adminSub ? PERM_ADMIN : PERM_USE)) {
            cfg.send(sender, "no-permission");
            return true;
        }

        if (sub.equals("reload")) {
            cfg.load();
            plugin.economy().setup();
            cfg.send(sender, "reload-success");
            return true;
        }
        if (sub.equals("config")) {
            showConfig(sender);
            return true;
        }
        if (sub.equals("help")) {
            cfg.sendRaw(sender, "help");
            if (sender.hasPermission(PERM_ADMIN)) {
                cfg.sendRaw(sender, "help-admin");
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            cfg.send(sender, "player-only");
            return true;
        }

        switch (sub) {
            case "toggle" -> toggle(player);
            case "to" -> cmdTo(player, args);
            case "gui", "inbox" ->
                    plugin.inboxGui().open(player, player.getUniqueId(), player.getName(),
                            InboxGui.Mode.OWN, null, null);
            case "from" -> cmdFrom(player, args);
            case "exception" -> cmdException(player, args);
            case "block" -> cmdBlock(player, args);
            case "see" -> cmdSee(player, args);
            default -> cfg.sendRaw(player, "help");
        }
        return true;
    }

    // ------------------------------------------------------------ sub commands

    private void toggle(Player player) {
        PlayerData data = plugin.storage().get(player.getUniqueId());
        data.setReceiving(!data.isReceiving());
        boolean on = data.isReceiving();
        if (!plugin.storage().save(data)) {
            plugin.config().send(player, "error-generic");
            return;
        }
        plugin.config().send(player, on ? "toggle-on" : "toggle-off");
    }

    private void cmdTo(Player player, String[] args) {
        ConfigManager cfg = plugin.config();
        if (args.length < 2) {
            cfg.sendRaw(player, "help");
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            cfg.send(player, "player-not-found", Placeholder.unparsed("player", args[1]));
            return;
        }
        String name = nameOf(target, args[1]);
        String denial = plugin.service().checkRestrictions(player.getUniqueId(), target.getUniqueId(), false);
        if (denial != null) {
            cfg.send(player, denial, Placeholder.unparsed("player", name));
            return;
        }
        plugin.sendGui().open(player, target.getUniqueId(), name);
    }

    private void cmdFrom(Player player, String[] args) {
        ConfigManager cfg = plugin.config();
        if (args.length < 2) {
            cfg.sendRaw(player, "help");
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            cfg.send(player, "player-not-found", Placeholder.unparsed("player", args[1]));
            return;
        }
        plugin.inboxGui().open(player, player.getUniqueId(), player.getName(), InboxGui.Mode.FROM,
                target.getUniqueId(), nameOf(target, args[1]));
    }

    private void cmdSee(Player player, String[] args) {
        ConfigManager cfg = plugin.config();
        if (args.length < 2) {
            cfg.sendRaw(player, "help-admin");
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            cfg.send(player, "player-not-found", Placeholder.unparsed("player", args[1]));
            return;
        }
        String name = nameOf(target, args[1]);
        plugin.inboxGui().open(player, target.getUniqueId(), name, InboxGui.Mode.SEE, null, null);
    }

    /** Toggles the player on the executor's exception list. */
    private void cmdException(Player player, String[] args) {
        ConfigManager cfg = plugin.config();
        if (args.length < 2) {
            cfg.sendRaw(player, "help");
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            cfg.send(player, "player-not-found", Placeholder.unparsed("player", args[1]));
            return;
        }
        UUID uuid = target.getUniqueId();
        if (uuid.equals(player.getUniqueId())) {
            cfg.send(player, "cannot-target-self");
            return;
        }
        TagResolver tag = Placeholder.unparsed("player", nameOf(target, args[1]));
        PlayerData data = plugin.storage().get(player.getUniqueId());
        boolean removed = data.exceptions().remove(uuid);
        if (!removed) {
            data.exceptions().add(uuid);
        }
        if (!plugin.storage().save(data)) {
            cfg.send(player, "error-generic");
            return;
        }
        cfg.send(player, removed ? "exception-removed" : "exception-added", tag);
    }

    /** Toggles the player on the executor's block list (blocking also clears their exception). */
    private void cmdBlock(Player player, String[] args) {
        ConfigManager cfg = plugin.config();
        if (args.length < 2) {
            cfg.sendRaw(player, "help");
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            cfg.send(player, "player-not-found", Placeholder.unparsed("player", args[1]));
            return;
        }
        UUID uuid = target.getUniqueId();
        if (uuid.equals(player.getUniqueId())) {
            cfg.send(player, "cannot-target-self");
            return;
        }
        TagResolver tag = Placeholder.unparsed("player", nameOf(target, args[1]));
        PlayerData data = plugin.storage().get(player.getUniqueId());
        boolean removed = data.blocked().remove(uuid);
        if (!removed) {
            data.blocked().add(uuid);
            data.exceptions().remove(uuid);
        }
        if (!plugin.storage().save(data)) {
            cfg.send(player, "error-generic");
            return;
        }
        cfg.send(player, removed ? "block-removed" : "block-added", tag);
    }

    private void showConfig(CommandSender sender) {
        ConfigManager cfg = plugin.config();
        cfg.sendRaw(sender, "config-header");
        line(sender, "delivery-fee", String.valueOf(cfg.deliveryFee()));
        line(sender, "charge-per-item", String.valueOf(cfg.chargePerItem()));
        line(sender, "max-items-per-mail", String.valueOf(cfg.maxItemsPerMail()));
        line(sender, "allow-sending-to-self", String.valueOf(cfg.allowSendingToSelf()));
        line(sender, "drop-on-full", String.valueOf(cfg.dropOnFull()));
        line(sender, "notify-on-join", String.valueOf(cfg.notifyOnJoin()));
        line(sender, "gui.send.size", String.valueOf(cfg.intValue("gui.send.size", 27)));
        line(sender, "gui.inbox.size", String.valueOf(cfg.intValue("gui.inbox.size", 54)));
        line(sender, "vault-economy", plugin.economy().isAvailable() ? "connected" : "not found");
    }

    private void line(CommandSender sender, String key, String value) {
        plugin.config().sendRaw(sender, "config-line",
                Placeholder.unparsed("key", key), Placeholder.unparsed("value", value));
    }

    // ------------------------------------------------------------ helpers

    /** Online players first, then the server's name cache (never blocks on a Mojang lookup). */
    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private String nameOf(OfflinePlayer player, String fallback) {
        return player.getName() != null ? player.getName() : fallback;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission(PERM_ADMIN);
        if (args.length == 1) {
            if (sender.hasPermission(PERM_USE)) {
                out.addAll(USER_SUBS);
            }
            if (admin) {
                out.addAll(ADMIN_SUBS);
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            out.removeIf(s -> !s.startsWith(prefix));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            boolean allowed = NAME_ARG_SUBS.contains(sub)
                    && (sub.equals("see") ? admin : sender.hasPermission(PERM_USE));
            if (allowed) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        out.add(online.getName());
                    }
                }
            }
        }
        return out;
    }
}
