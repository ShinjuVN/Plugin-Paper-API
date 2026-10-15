package dev.isend.economy;

import dev.isend.ISendPlugin;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** All Vault access lives here so the rest of the plugin never touches Vault classes. */
public final class EconomyHook {

    private final ISendPlugin plugin;
    private Economy economy;

    public EconomyHook(ISendPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean setup() {
        economy = null;
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> provider =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            return false;
        }
        economy = provider.getProvider();
        return economy != null;
    }

    /** Lazily retries, because economy providers may register after this plugin enabled. */
    public boolean isAvailable() {
        if (economy == null) {
            setup();
        }
        return economy != null;
    }

    public boolean has(OfflinePlayer player, double amount) {
        return isAvailable() && economy.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        return isAvailable() && economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    public void deposit(OfflinePlayer player, double amount) {
        if (isAvailable()) {
            economy.depositPlayer(player, amount);
        }
    }

    public String format(double amount) {
        if (isAvailable()) {
            try {
                return economy.format(amount);
            } catch (RuntimeException ignored) {
                // fall through to plain format
            }
        }
        return String.format(java.util.Locale.US, "%,.2f", amount);
    }
}
