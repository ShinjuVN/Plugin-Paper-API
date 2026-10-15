package vn.pab;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLoginEvent;

import java.net.InetSocketAddress;

/** Chặn ở tầng đăng nhập/kick: bắt mọi ban đi qua API hoặc plugin tự ban. */
public final class ProtectListener implements Listener {
    private final PAB plugin;

    public ProtectListener(PAB plugin) { this.plugin = plugin; }

    private boolean ip() { return plugin.getConfig().getBoolean("protect-ip", true); }

    // ---------------- AsyncPlayerPreLogin (thread async: không đụng BanList)
    @EventHandler(priority = EventPriority.LOWEST)
    public void preLow(AsyncPlayerPreLoginEvent e) { pre(e); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void preMon(AsyncPlayerPreLoginEvent e) { pre(e); }

    private void pre(AsyncPlayerPreLoginEvent e) {
        PabEntry en = plugin.store().resolve(e.getUniqueId(), e.getName());
        if (en == null) return;
        plugin.store().touch(en, e.getUniqueId(), e.getName(), e.getAddress(), ip());
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED
                && plugin.shouldOverrideLogin(e.getLoginResult().name(), PAB.plain(e.kickMessage()))) {
            e.allow();
        }
    }

    // ---------------- PlayerLogin (main thread: gỡ ban được luôn)
    @EventHandler(priority = EventPriority.LOWEST)
    public void loginLow(PlayerLoginEvent e) { login(e, true); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void loginMon(PlayerLoginEvent e) { login(e, false); }

    private void login(PlayerLoginEvent e, boolean sweep) {
        Player p = e.getPlayer();
        PabEntry en = plugin.store().resolve(p.getUniqueId(), p.getName());
        if (en == null) return;
        plugin.store().touch(en, p.getUniqueId(), p.getName(), e.getAddress(), ip());
        if (sweep) plugin.sweep(en);
        if (e.getResult() != PlayerLoginEvent.Result.ALLOWED
                && plugin.shouldOverrideLogin(e.getResult().name(), PAB.plain(e.kickMessage()))) {
            e.allow();
        }
    }

    // ---------------- Join
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PabEntry en = plugin.store().resolve(p.getUniqueId(), p.getName());
        if (en == null) return;
        InetSocketAddress a = p.getAddress();
        plugin.store().touch(en, p.getUniqueId(), p.getName(), a == null ? null : a.getAddress(), ip());
        plugin.sweep(en);
    }

    // ---------------- Kick do ban (Player#ban, plugin tự kick "banned"...)
    @EventHandler(priority = EventPriority.LOWEST)
    public void kick(PlayerKickEvent e) {
        Player p = e.getPlayer();
        PabEntry en = plugin.store().resolve(p.getUniqueId(), p.getName());
        if (en == null) return;

        String cause = e.getCause().name();
        boolean banCause = cause.equals("BANNED") || cause.equals("IP_BANNED");
        boolean banText = plugin.hasBanText(PAB.plain(e.reason()));
        boolean all = plugin.getConfig().getBoolean("cancel-all-kicks", false);
        if (!(banCause || banText || all)) return;

        e.setCancelled(true);
        plugin.sweep(en);
        Bukkit.getScheduler().runTask(plugin, () -> plugin.sweep(en));
        if (plugin.getConfig().getBoolean("log-blocked", true)) {
            plugin.getLogger().warning("Đã huỷ kick/ban nhắm vào " + en.name() + " (cause=" + cause + ")");
        }
    }
}
