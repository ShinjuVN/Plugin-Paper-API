package vn.smboss;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class SMBossPlugin extends JavaPlugin {

    private BossManager bossManager;

    @Override
    public void onEnable() {
        bossManager = new BossManager(this);
        bossManager.reload();

        getServer().getPluginManager().registerEvents(new BossListener(bossManager), this);

        BossCommands commands = new BossCommands(bossManager);
        for (String name : new String[]{"smbreload", "smboss", "cboss", "delboss", "cdnbt"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(commands);
                cmd.setTabCompleter(commands);
            }
        }
        getLogger().info("Da tai " + bossManager.ids().size() + " boss.");
    }
}
