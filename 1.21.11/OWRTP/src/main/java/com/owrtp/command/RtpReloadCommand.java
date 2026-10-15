package com.owrtp.command;

import com.owrtp.OwRtpPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public final class RtpReloadCommand implements CommandExecutor, TabCompleter {

    private final OwRtpPlugin plugin;

    public RtpReloadCommand(OwRtpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("rtp.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        plugin.reloadAll();
        plugin.messages().send(sender, "reload-success");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
