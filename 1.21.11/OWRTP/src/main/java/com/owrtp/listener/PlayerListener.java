package com.owrtp.listener;

import com.owrtp.OwRtpPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerListener implements Listener {

    private final OwRtpPlugin plugin;

    public PlayerListener(OwRtpPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!plugin.rtpConfig().cancelOnDamage() || event.getFinalDamage() <= 0) {
            return;
        }
        if (plugin.rtp().hasActive(player.getUniqueId())) {
            plugin.rtp().cancelByDamage(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        // Kiểm tra rẻ nhất trước: sự kiện này chạy rất thường xuyên
        if (!plugin.rtp().isCounting(player.getUniqueId()) || !plugin.rtpConfig().cancelOnMove()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        plugin.rtp().cancelByMove(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.rtp().cancelSilently(event.getPlayer().getUniqueId());
    }
}
