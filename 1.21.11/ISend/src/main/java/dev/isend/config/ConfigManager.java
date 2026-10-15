package dev.isend.config;

import dev.isend.ISendPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

public final class ConfigManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ISendPlugin plugin;
    private FileConfiguration config;

    public ConfigManager(ISendPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig(); // missing keys fall back to the defaults bundled in the jar
    }

    // ---------------------------------------------------------------- settings

    public double deliveryFee() {
        return Math.max(0.0, config.getDouble("settings.delivery-fee", 500.0));
    }

    public double chargePerItem() {
        return Math.max(0.0, config.getDouble("settings.charge-per-item", 0.0));
    }

    public int maxItemsPerMail() {
        return Math.max(1, config.getInt("settings.max-items-per-mail", 18));
    }

    public boolean allowSendingToSelf() {
        return config.getBoolean("settings.allow-sending-to-self", false);
    }

    public boolean dropOnFull() {
        return config.getBoolean("settings.drop-on-full", true);
    }

    public boolean notifyOnJoin() {
        return config.getBoolean("settings.notify-on-join", true);
    }

    public String dateFormat() {
        return config.getString("settings.date-format", "dd/MM/yyyy HH:mm");
    }

    // ---------------------------------------------------------------- raw getters

    public String str(String path, String def) {
        return config.getString(path, def);
    }

    public int intValue(String path, int def) {
        return config.getInt(path, def);
    }

    public List<String> stringList(String path) {
        return config.getStringList(path);
    }

    public Material material(String path, Material def) {
        String name = config.getString(path);
        if (name == null) {
            return def;
        }
        Material material = Material.matchMaterial(name.trim());
        return material == null || material.isAir() || !material.isItem() ? def : material;
    }

    // ---------------------------------------------------------------- text

    public Component parse(String raw, TagResolver... resolvers) {
        return MM.deserialize(Text.legacyToMini(raw), resolvers);
    }

    /** Text for item names/lore: italics are off unless the config turns them on. */
    public Component item(String raw, TagResolver... resolvers) {
        return Component.empty().decoration(TextDecoration.ITALIC, false).append(parse(raw, resolvers));
    }

    public List<Component> itemLore(List<String> raw, TagResolver... resolvers) {
        List<Component> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            out.add(item(line, resolvers));
        }
        return out;
    }

    /** Sends messages.<key> with the prefix. A blank message disables it. */
    public void send(CommandSender to, String key, TagResolver... resolvers) {
        String message = config.getString("messages." + key);
        if (message == null || message.isBlank()) {
            return;
        }
        to.sendMessage(parse(config.getString("messages.prefix", "") + message, resolvers));
    }

    /** Sends messages.<key> without the prefix. */
    public void sendRaw(CommandSender to, String key, TagResolver... resolvers) {
        String message = config.getString("messages." + key);
        if (message == null || message.isBlank()) {
            return;
        }
        to.sendMessage(parse(message, resolvers));
    }
}
