package dev.isend.listener;

import dev.isend.ISendPlugin;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerListener implements Listener {

    private final ISendPlugin plugin;

    public PlayerListener(ISendPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.config().notifyOnJoin()) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            int count = plugin.storage().get(player.getUniqueId()).mailCount();
            if (count > 0) {
                plugin.config().sendRaw(player, "join-notification",
                        Placeholder.unparsed("count", String.valueOf(count)));
            }
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.storage().unload(event.getPlayer().getUniqueId());
    }
}
