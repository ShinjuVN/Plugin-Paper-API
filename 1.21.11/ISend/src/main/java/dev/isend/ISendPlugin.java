package dev.isend;

import dev.isend.command.ISendCommand;
import dev.isend.config.ConfigManager;
import dev.isend.economy.EconomyHook;
import dev.isend.gui.InboxGui;
import dev.isend.gui.SendGui;
import dev.isend.listener.GuiListener;
import dev.isend.listener.PlayerListener;
import dev.isend.service.MailService;
import dev.isend.storage.StorageManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

public final class ISendPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private EconomyHook economyHook;
    private StorageManager storageManager;
    private MailService mailService;
    private SendGui sendGui;
    private InboxGui inboxGui;

    @Override
    public void onEnable() {
        if (!isSupportedServer()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        configManager = new ConfigManager(this);
        configManager.load();

        economyHook = new EconomyHook(this);
        if (!economyHook.setup()) {
            getLogger().warning("Vault or an economy provider was not found yet. "
                    + "Delivery fees cannot be charged until one is available.");
        }

        storageManager = new StorageManager(this);
        mailService = new MailService(this);
        sendGui = new SendGui(this);
        inboxGui = new InboxGui(this);

        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        PluginCommand command = getCommand("isend");
        if (command != null) {
            ISendCommand handler = new ISendCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
    }

    @Override
    public void onDisable() {
        if (sendGui != null) {
            // Give items back to players who still have the send GUI open.
            for (Player player : getServer().getOnlinePlayers()) {
                InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder(false);
                if (holder instanceof SendGui.Holder sendHolder) {
                    sendGui.returnItems(sendHolder, player);
                    player.closeInventory();
                } else if (holder instanceof InboxGui.Holder) {
                    player.closeInventory();
                }
            }
        }
        if (storageManager != null) {
            storageManager.saveAll();
        }
    }

    /** Needs Paper (or a fork such as Purpur/Leaf) 1.21+; Folia's regionised scheduler is not supported. */
    private boolean isSupportedServer() {
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
        } catch (ClassNotFoundException ex) {
            getLogger().severe("ISend requires Paper, Purpur or Leaf 1.21+ (Spigot/CraftBukkit are not supported).");
            return false;
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            getLogger().severe("ISend does not support Folia. Use Paper, Purpur or Leaf.");
            return false;
        } catch (ClassNotFoundException ignored) {
            return true;
        }
    }

    public ConfigManager config() { return configManager; }
    public EconomyHook economy() { return economyHook; }
    public StorageManager storage() { return storageManager; }
    public MailService service() { return mailService; }
    public SendGui sendGui() { return sendGui; }
    public InboxGui inboxGui() { return inboxGui; }
}
