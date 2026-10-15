package com.owrtp;

import com.owrtp.command.RtpCommand;
import com.owrtp.command.RtpConfigCommand;
import com.owrtp.command.RtpReloadCommand;
import com.owrtp.config.Messages;
import com.owrtp.config.RtpConfig;
import com.owrtp.listener.PlayerListener;
import com.owrtp.manager.CooldownManager;
import com.owrtp.manager.RtpManager;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public final class OwRtpPlugin extends JavaPlugin {

    private RtpConfig rtpConfig;
    private Messages messages;
    private CooldownManager cooldownManager;
    private RtpManager rtpManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.rtpConfig = new RtpConfig(this);
        this.messages = new Messages(this);
        this.cooldownManager = new CooldownManager(this);
        this.cooldownManager.load();
        this.rtpManager = new RtpManager(this);

        register("rtp", new RtpCommand(this));
        register("rtpreload", new RtpReloadCommand(this));
        register("rtpconfig", new RtpConfigCommand(this));

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
    }

    @Override
    public void onDisable() {
        if (rtpManager != null) {
            rtpManager.cancelAll();
        }
        if (cooldownManager != null) {
            cooldownManager.save();
        }
    }

    /** Tải lại config.yml và dữ liệu (cooldown). */
    public void reloadAll() {
        cooldownManager.save();
        reloadConfig();
        rtpConfig.load();
        cooldownManager.load();
    }

    private void register(String name, Object executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Không tìm thấy lệnh trong plugin.yml: " + name);
            return;
        }
        cmd.setExecutor((CommandExecutor) executor);
        if (executor instanceof TabCompleter tc) {
            cmd.setTabCompleter(tc);
        }
    }

    public RtpConfig rtpConfig() { return rtpConfig; }
    public Messages messages() { return messages; }
    public CooldownManager cooldowns() { return cooldownManager; }
    public RtpManager rtp() { return rtpManager; }
}
