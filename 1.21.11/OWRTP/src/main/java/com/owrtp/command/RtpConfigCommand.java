package com.owrtp.command;

import com.owrtp.OwRtpPlugin;
import com.owrtp.config.Messages;
import com.owrtp.config.RtpConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class RtpConfigCommand implements CommandExecutor, TabCompleter {

    private static final Pattern RANK = Pattern.compile("[a-z0-9_\\-]+");

    private final OwRtpPlugin plugin;

    public RtpConfigCommand(OwRtpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Messages msg = plugin.messages();
        if (!sender.hasPermission("rtp.admin")) {
            msg.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            msg.send(sender, "usage-config");
            return true;
        }

        RtpConfig cfg = plugin.rtpConfig();
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "radius" -> {
                if (args.length < 3) {
                    msg.send(sender, "usage-config");
                    return true;
                }
                Integer value = parseInt(sender, args[2]);
                if (value == null) {
                    return true;
                }
                String type = args[1].toLowerCase(Locale.ROOT);
                switch (type) {
                    case "min" -> {
                        if (value < 0 || value >= cfg.maxRadius()) {
                            radiusInvalid(sender, cfg);
                            return true;
                        }
                        cfg.setMinRadius(value);
                    }
                    case "max" -> {
                        if (value <= cfg.minRadius()) {
                            radiusInvalid(sender, cfg);
                            return true;
                        }
                        cfg.setMaxRadius(value);
                    }
                    default -> {
                        msg.send(sender, "usage-config");
                        return true;
                    }
                }
                msg.send(sender, "radius-updated", "type", type, "value", String.valueOf(value));
            }
            case "cooldown" -> {
                if (args.length < 3) {
                    msg.send(sender, "usage-config");
                    return true;
                }
                String action = args[1].toLowerCase(Locale.ROOT);
                String rank = args[2].toLowerCase(Locale.ROOT);
                if (!RANK.matcher(rank).matches()) {
                    msg.send(sender, "invalid-rank");
                    return true;
                }
                switch (action) {
                    case "add" -> {
                        if (args.length < 4) {
                            msg.send(sender, "usage-config");
                            return true;
                        }
                        Integer seconds = parseInt(sender, args[3]);
                        if (seconds == null) {
                            return true;
                        }
                        if (seconds < 0) {
                            msg.send(sender, "invalid-number", "input", args[3]);
                            return true;
                        }
                        cfg.setRankCooldown(rank, seconds);
                        msg.send(sender, "cooldown-updated", "rank", rank, "time", Messages.formatTime(seconds));
                    }
                    case "reset" -> {
                        cfg.resetRankCooldown(rank);
                        msg.send(sender, "cooldown-reset", "rank", rank);
                    }
                    default -> msg.send(sender, "usage-config");
                }
            }
            case "cancel-on-damage", "cancel-on-move" -> {
                if (args.length < 2) {
                    msg.send(sender, "usage-config");
                    return true;
                }
                Boolean value = parseBoolean(args[1]);
                if (value == null) {
                    msg.send(sender, "invalid-boolean");
                    return true;
                }
                String option = args[0].toLowerCase(Locale.ROOT);
                if (option.equals("cancel-on-damage")) {
                    cfg.setCancelOnDamage(value);
                } else {
                    cfg.setCancelOnMove(value);
                }
                msg.send(sender, "bool-updated", "option", option, "value", String.valueOf(value));
            }
            default -> msg.send(sender, "usage-config");
        }
        return true;
    }

    private void radiusInvalid(CommandSender sender, RtpConfig cfg) {
        plugin.messages().send(sender, "radius-invalid",
                "min", String.valueOf(cfg.minRadius()), "max", String.valueOf(cfg.maxRadius()));
    }

    private Integer parseInt(CommandSender sender, String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException ex) {
            plugin.messages().send(sender, "invalid-number", "input", input);
            return null;
        }
    }

    private Boolean parseBoolean(String input) {
        if (input.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (input.equalsIgnoreCase("false")) return Boolean.FALSE;
        return null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("rtp.admin")) {
            return Collections.emptyList();
        }
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("radius", "cooldown", "cancel-on-damage", "cancel-on-move"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "radius" -> options.addAll(List.of("min", "max"));
                case "cooldown" -> options.addAll(List.of("add", "reset"));
                case "cancel-on-damage", "cancel-on-move" -> options.addAll(List.of("true", "false"));
                default -> { }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("cooldown")) {
            options.addAll(plugin.rtpConfig().cooldowns().keySet());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return options;
    }
}
