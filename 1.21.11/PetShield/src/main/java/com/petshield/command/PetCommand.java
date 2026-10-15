package com.petshield.command;

import com.petshield.PetShieldPlugin;
import com.petshield.config.Messages;
import com.petshield.data.PetRecord;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Xử lý cả /mypet và /pet: đăng ký, list, del, revive. */
public final class PetCommand implements CommandExecutor, TabCompleter {

    private final PetShieldPlugin plugin;

    public PetCommand(PetShieldPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Messages msg = plugin.messages();
        if (!(sender instanceof Player player)) {
            msg.send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("petshield.use")) {
            msg.send(player, "no-permission");
            return true;
        }

        if (args.length == 0) {
            if (command.getName().equalsIgnoreCase("mypet")) {
                plugin.pets().startRegistering(player);
            } else {
                msg.send(player, "help");
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add", "register" -> plugin.pets().startRegistering(player);
            case "cancel" -> plugin.pets().cancelRegistering(player);
            case "list" -> list(player);
            case "del", "delete", "remove" -> delete(player, args);
            case "revive" -> revive(player, args);
            case "help" -> msg.send(player, "help");
            default -> msg.send(player, "help");
        }
        return true;
    }

    private void list(Player player) {
        Messages msg = plugin.messages();
        List<PetRecord> pets = new ArrayList<>(plugin.data().get(player.getUniqueId()).pets());
        if (pets.isEmpty()) {
            msg.send(player, "list-empty");
            return;
        }

        msg.send(player, "list-header",
                "count", String.valueOf(pets.size()),
                "limit", String.valueOf(plugin.pets().limitFor(player)));

        for (PetRecord r : pets) {
            long left = plugin.pets().cooldownRemainingSeconds(r);
            String cooldown = left > 0 ? Messages.formatTime(left) : msg.plain("cooldown-ready");
            String type = plugin.petConfig().typeName(r.type);
            if (r.alive) {
                msg.send(player, "list-entry-alive",
                        "id", String.valueOf(r.id), "name", r.name, "type", type, "cooldown", cooldown);
            } else {
                msg.send(player, "list-entry-dead",
                        "id", String.valueOf(r.id), "name", r.name, "type", type, "cooldown", cooldown,
                        "world", String.valueOf(r.deathWorld),
                        "x", String.valueOf((int) Math.floor(r.deathX)),
                        "y", String.valueOf((int) Math.floor(r.deathY)),
                        "z", String.valueOf((int) Math.floor(r.deathZ)));
            }
        }
    }

    private void delete(Player player, String[] args) {
        Messages msg = plugin.messages();
        if (args.length < 2) {
            msg.send(player, "del-usage");
            return;
        }
        String input = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        PetRecord rec = plugin.pets().find(player.getUniqueId(), input);
        if (rec == null) {
            msg.send(player, "pet-not-found", "input", input);
            return;
        }
        plugin.data().remove(rec);
        msg.send(player, "pet-deleted", "name", rec.name);
    }

    private void revive(Player player, String[] args) {
        Messages msg = plugin.messages();
        if (args.length >= 2) {
            String input = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            PetRecord rec = plugin.pets().find(player.getUniqueId(), input);
            if (rec == null) {
                msg.send(player, "pet-not-found", "input", input);
                return;
            }
            plugin.pets().revive(player, rec);
            return;
        }

        List<PetRecord> dead = new ArrayList<>();
        for (PetRecord r : plugin.data().get(player.getUniqueId()).pets()) {
            if (!r.alive) {
                dead.add(r);
            }
        }
        if (dead.isEmpty()) {
            msg.send(player, "revive-no-dead");
            return;
        }
        plugin.pets().reviveAll(player, dead);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("petshield.use")) {
            return Collections.emptyList();
        }
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("list", "del", "revive", "cancel", "help"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("del") || args[0].equalsIgnoreCase("revive"))) {
            for (PetRecord r : plugin.data().get(player.getUniqueId()).pets()) {
                options.add(String.valueOf(r.id));
                if (r.name != null && r.name.indexOf(' ') < 0) {
                    options.add(r.name);
                }
            }
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return options;
    }
}
