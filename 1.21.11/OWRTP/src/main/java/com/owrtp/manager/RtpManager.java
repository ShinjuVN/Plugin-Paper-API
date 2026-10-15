package com.owrtp.manager;

import com.owrtp.OwRtpPlugin;
import com.owrtp.config.Messages;
import com.owrtp.config.RtpConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Quản lý các phiên RTP đang chạy (chỉ truy cập ở main thread). */
public final class RtpManager {

    private final OwRtpPlugin plugin;
    private final LocationFinder finder;
    private final Map<UUID, RtpSession> sessions = new HashMap<>();

    public RtpManager(OwRtpPlugin plugin) {
        this.plugin = plugin;
        this.finder = new LocationFinder(plugin);
    }

    // ---------------------------------------------------------------- bắt đầu

    public void start(Player player) {
        Messages msg = plugin.messages();
        RtpConfig cfg = plugin.rtpConfig();
        UUID id = player.getUniqueId();

        if (!player.hasPermission("rtp.default")) {
            msg.send(player, "no-permission");
            return;
        }
        if (!cfg.isWorldEnabled(player.getWorld().getName())) {
            msg.send(player, "world-disabled");
            return;
        }
        if (sessions.containsKey(id)) {
            msg.send(player, "already-active");
            return;
        }
        if (!player.hasPermission("rtp.bypass.cooldown")) {
            long left = plugin.cooldowns().remainingSeconds(id);
            if (left > 0) {
                msg.send(player, "cooldown", "time", Messages.formatTime(left));
                return;
            }
        }

        RtpSession session = new RtpSession(id);
        sessions.put(id, session);

        int countdown = cfg.countdown();
        if (countdown <= 0 || player.hasPermission("rtp.bypass.countdown")) {
            beginSearch(player, session);
            return;
        }

        session.state = RtpSession.State.COUNTDOWN;
        session.remaining = countdown;
        session.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(session), 0L, 20L);
    }

    private void tick(RtpSession session) {
        if (sessions.get(session.playerId) != session) {
            session.task.cancel();
            return;
        }
        Player player = Bukkit.getPlayer(session.playerId);
        if (player == null || !player.isOnline()) {
            cancelSilently(session.playerId);
            return;
        }
        if (session.remaining <= 0) {
            session.task.cancel();
            beginSearch(player, session);
            return;
        }
        plugin.messages().actionBar(player, "countdown", "time", String.valueOf(session.remaining));
        session.remaining--;
    }

    // ---------------------------------------------------------------- tìm vị trí + dịch chuyển

    private void beginSearch(Player player, RtpSession session) {
        session.state = RtpSession.State.SEARCHING;
        plugin.messages().actionBar(player, "searching");

        final UUID id = session.playerId;
        final World world = player.getWorld();

        finder.find(world, player.getLocation(),
                () -> sessions.get(id) == session && session.state == RtpSession.State.SEARCHING,
                loc -> {
                    if (sessions.get(id) != session) {
                        return; // đã bị hủy
                    }
                    Player p = Bukkit.getPlayer(id);
                    if (p == null || !p.isOnline()) {
                        sessions.remove(id);
                        return;
                    }
                    if (loc == null) {
                        sessions.remove(id);
                        plugin.messages().send(p, "location-not-found");
                        return;
                    }
                    teleport(p, session, loc);
                });
    }

    private void teleport(Player player, RtpSession session, Location loc) {
        session.state = RtpSession.State.TELEPORTING;
        final UUID id = session.playerId;
        final World world = loc.getWorld();
        final int cx = loc.getBlockX() >> 4;
        final int cz = loc.getBlockZ() >> 4;

        // Giữ chunk đích không bị unload trong lúc dịch chuyển
        world.addPluginChunkTicket(cx, cz, plugin);

        player.teleportAsync(loc, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, err) -> {
            Runnable finish = () -> {
                world.removePluginChunkTicket(cx, cz, plugin);
                if (sessions.get(id) == session) {
                    sessions.remove(id);
                }
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline()) {
                    return;
                }
                if (err == null && Boolean.TRUE.equals(ok)) {
                    if (!p.hasPermission("rtp.bypass.cooldown")) {
                        plugin.cooldowns().apply(p);
                    }
                    plugin.messages().send(p, "success",
                            "x", String.valueOf(loc.getBlockX()),
                            "y", String.valueOf(loc.getBlockY()),
                            "z", String.valueOf(loc.getBlockZ()),
                            "world", world.getName());
                } else {
                    plugin.messages().send(p, "teleport-failed");
                }
            };
            if (Bukkit.isPrimaryThread()) {
                finish.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, finish);
            }
        });
    }

    // ---------------------------------------------------------------- hủy

    /** Hủy do nhận sát thương: áp dụng ở cả giai đoạn đếm ngược và tìm vị trí. */
    public void cancelByDamage(Player player) {
        RtpSession s = sessions.get(player.getUniqueId());
        if (s == null || s.state == RtpSession.State.TELEPORTING) {
            return;
        }
        remove(s);
        plugin.messages().send(player, "cancelled-damage");
    }

    /** Hủy do di chuyển: chỉ áp dụng khi đang đếm ngược. */
    public void cancelByMove(Player player) {
        RtpSession s = sessions.get(player.getUniqueId());
        if (s == null || s.state != RtpSession.State.COUNTDOWN) {
            return;
        }
        remove(s);
        plugin.messages().send(player, "cancelled-move");
    }

    public void cancelSilently(UUID id) {
        RtpSession s = sessions.get(id);
        if (s != null && s.state != RtpSession.State.TELEPORTING) {
            remove(s);
        }
    }

    public void cancelAll() {
        Iterator<RtpSession> it = sessions.values().iterator();
        while (it.hasNext()) {
            RtpSession s = it.next();
            if (s.task != null) {
                s.task.cancel();
            }
            it.remove();
        }
    }

    public boolean isCounting(UUID id) {
        RtpSession s = sessions.get(id);
        return s != null && s.state == RtpSession.State.COUNTDOWN;
    }

    public boolean hasActive(UUID id) {
        RtpSession s = sessions.get(id);
        return s != null && s.state != RtpSession.State.TELEPORTING;
    }

    private void remove(RtpSession s) {
        if (s.task != null) {
            s.task.cancel();
        }
        sessions.remove(s.playerId);
    }
}
