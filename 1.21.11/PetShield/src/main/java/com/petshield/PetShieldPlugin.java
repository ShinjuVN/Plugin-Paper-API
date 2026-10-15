package com.petshield;

import com.petshield.command.PetCommand;
import com.petshield.command.ReloadCommand;
import com.petshield.config.Messages;
import com.petshield.config.PetConfig;
import com.petshield.data.DataManager;
import com.petshield.listener.PetListener;
import com.petshield.manager.PetManager;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public final class PetShieldPlugin extends JavaPlugin {

    private PetConfig petConfig;
    private Messages messages;
    private DataManager dataManager;
    private PetManager petManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.petConfig = new PetConfig(this);
        this.messages = new Messages(this);
        this.dataManager = new DataManager(this);
        this.petManager = new PetManager(this);

        dataManager.loadAsync(null);
        dataManager.startAutoFlush();
        petManager.restartSnapshotTask();

        PetCommand petCommand = new PetCommand(this);
        register("mypet", petCommand);
        register("pet", petCommand);
        register("mypetreload", new ReloadCommand(this));

        getServer().getPluginManager().registerEvents(new PetListener(this), this);
    }

    @Override
    public void onDisable() {
        if (petManager != null) {
            petManager.snapshotAll();
        }
        if (dataManager != null) {
            dataManager.flushSync();
        }
    }

    /** Tải lại config.yml và dữ liệu pet. */
    public void reloadAll() {
        petManager.snapshotAll();
        dataManager.flushSync();
        reloadConfig();
        petConfig.load();
        dataManager.loadAsync(null);
        petManager.restartSnapshotTask();
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

    public PetConfig petConfig() { return petConfig; }
    public Messages messages() { return messages; }
    public DataManager data() { return dataManager; }
    public PetManager pets() { return petManager; }
}
