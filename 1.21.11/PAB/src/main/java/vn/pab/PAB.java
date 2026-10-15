package vn.pab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.ban.IpBanList;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.scheduler.BukkitTask;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class PAB extends JavaPlugin {
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private ProtectedStore store;
    private BanGuard guard;
    private CommandInjector injector;
    private BukkitTask sweepTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new ProtectedStore(this);
        store.load();
        guard = new BanGuard(this);
        injector = new CommandInjector(this);

        PabCommand pc = new PabCommand(this);
        PluginCommand cmd = getCommand("pab");
        if (cmd != null) {
            cmd.setExecutor(pc);
            cmd.setTabCompleter(pc);
        }

        getServer().getPluginManager().registerEvents(new CommandListener(this), this);
        getServer().getPluginManager().registerEvents(new ProtectListener(this), this);

        scheduleSweep();
        // chạy sau khi mọi plugin đã nạp xong + lặp lại để bắt lệnh đăng ký muộn
        Bukkit.getScheduler().runTaskTimer(this, this::maintenance, 1L, 100L);
        getLogger().info("PAB đã bật - đang bảo vệ " + store.all().size() + " người chơi.");
    }

    @Override
    public void onDisable() {
        if (injector != null) injector.restore();
        if (store != null) store.save();
    }

    private void scheduleSweep() {
        if (sweepTask != null) sweepTask.cancel();
        long iv = Math.max(1L, getConfig().getLong("sweep-interval-ticks", 2L));
        sweepTask = Bukkit.getScheduler().runTaskTimer(this, this::sweepAll, 1L, iv);
    }

    private void maintenance() {
        if (getConfig().getBoolean("inject-commands", true)) injector.inject();
        store.saveIfDirty();
    }

    public void reloadAll() {
        reloadConfig();
        guard.reload();
        store.load();
        injector.restore();
        if (getConfig().getBoolean("inject-commands", true)) injector.inject();
        scheduleSweep();
        sweepAll();
    }

    // ------------------------------------------------------------ accessors
    public ProtectedStore store() { return store; }
    public BanGuard guard() { return guard; }

    // ------------------------------------------------------------ helpers
    public Component color(String s) { return LEGACY.deserialize(s); }

    public static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    public boolean hasBanText(String text) {
        if (text == null || text.isEmpty()) return false;
        String l = text.toLowerCase(Locale.ROOT);
        List<String> kws = getConfig().getStringList("ban-text-keywords");
        for (String k : kws) if (l.contains(k.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** Có nên ghi đè kết quả từ chối đăng nhập này không. */
    public boolean shouldOverrideLogin(String resultName, String kickText) {
        if (getConfig().getBoolean("override-all-login-denials", false)) return true;
        if (resultName.equals("KICK_BANNED")) return true;
        return resultName.equals("KICK_OTHER") && hasBanText(kickText);
    }

    public void deny(CommandSender sender, PabEntry e, String commandLine) {
        String raw = e.message();
        if (raw == null || raw.isBlank()) raw = getConfig().getString("default-message", "&c[PAB] {player} không thể bị ban!");
        raw = raw.replace("{player}", e.name())
                .replace("{executor}", sender.getName())
                .replace("{command}", commandLine);
        sender.sendMessage(color(raw));
        if (getConfig().getBoolean("log-blocked", true)) {
            getLogger().warning(sender.getName() + " đã bị chặn khi ban " + e.name() + " (" + commandLine + ")");
        }
    }

    // ------------------------------------------------------------ gỡ ban
    public void sweepAll() {
        for (PabEntry e : store.all()) sweep(e);
    }

    /** Phải gọi trên main thread. */
    public void sweep(PabEntry e) {
        try {
            ProfileBanList pbl = Bukkit.getBanList(BanList.Type.PROFILE);
            for (UUID id : e.uuids()) {
                PlayerProfile pr = Bukkit.createPlayerProfile(id, e.name());
                if (pbl.isBanned(pr)) {
                    pbl.pardon(pr);
                    getLogger().warning("Đã gỡ ban của " + e.name() + " (" + id + ")");
                }
            }
            if (getConfig().getBoolean("protect-ip", true) && getConfig().getBoolean("unban-protected-ips", true)) {
                IpBanList ibl = Bukkit.getBanList(BanList.Type.IP);
                for (InetAddress a : e.ips().values()) {
                    if (ibl.isBanned(a)) {
                        ibl.pardon(a);
                        getLogger().warning("Đã gỡ IP-ban " + a.getHostAddress() + " của " + e.name());
                    }
                }
            }
        } catch (Throwable t) {
            getLogger().fine("sweep lỗi: " + t);
        }
    }
}
