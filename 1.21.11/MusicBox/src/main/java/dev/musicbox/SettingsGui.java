package dev.musicbox;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class SettingsGui implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int SLOT_LISTEN = 10;
    private static final int SLOT_REPEAT = 12;
    private static final int SLOT_MODE = 14;
    private static final int SLOT_CANCEL = 16;

    private final MusicBoxPlugin plugin;
    private final UUID owner;
    private Inventory inventory;

    private SettingsGui(MusicBoxPlugin plugin, UUID owner) {
        this.plugin = plugin;
        this.owner = owner;
    }

    public static void open(MusicBoxPlugin plugin, Player player) {
        SettingsGui gui = new SettingsGui(plugin, player.getUniqueId());
        String title = plugin.color(plugin.getConfig().getString("gui.title", "MusicBox"));
        gui.inventory = Bukkit.createInventory(gui, SIZE, title);
        gui.render(player);
        player.openInventory(gui.inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // ---------------------------------------------------------------- render

    private void render(Player p) {
        FileConfiguration cfg = plugin.getConfig();
        PlayerData d = plugin.data().getOrCreate(p);

        inventory.clear();
        if (cfg.getBoolean("gui.filler", true)) {
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = filler.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(" ");
                filler.setItemMeta(meta);
            }
            for (int i = 0; i < SIZE; i++) inventory.setItem(i, filler);
        }

        String last = d.lastPlayed != null ? d.lastPlayed : plugin.color(cfg.getString("gui.none", "-"));
        String modeLabel = plugin.color(cfg.getString("gui.modes." + d.mode.name().toLowerCase(Locale.ROOT), d.mode.name()));

        inventory.setItem(SLOT_LISTEN, build(Material.NOTE_BLOCK, "listen", "state", state(d.listenOthers)));
        inventory.setItem(SLOT_REPEAT, build(Material.REPEATER, "repeat", "state", state(d.repeat)));
        inventory.setItem(SLOT_MODE, build(Material.JUKEBOX, "mode", "mode", modeLabel, "last", last));
        inventory.setItem(SLOT_CANCEL, build(Material.BARRIER, "cancel"));
    }

    private String state(boolean on) {
        return plugin.color(plugin.getConfig().getString(on ? "gui.state-on" : "gui.state-off", on ? "ON" : "OFF"));
    }

    private ItemStack build(Material material, String key, String... kv) {
        FileConfiguration cfg = plugin.getConfig();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.color(plugin.fill(cfg.getString("gui.items." + key + ".name", key), kv)));
        List<String> lore = new ArrayList<>();
        for (String line : cfg.getStringList("gui.items." + key + ".lore")) {
            lore.add(plugin.color(plugin.fill(line, kv)));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ----------------------------------------------------------------- click

    public void handleClick(Player p, int slot) {
        if (!p.getUniqueId().equals(owner)) return;
        PlayerData d = plugin.data().getOrCreate(p);

        switch (slot) {
            case SLOT_LISTEN -> d.listenOthers = !d.listenOthers;
            case SLOT_REPEAT -> d.repeat = !d.repeat;
            case SLOT_MODE -> d.mode = d.mode.next();
            case SLOT_CANCEL -> {
                click(p);
                if (plugin.playback().cancel(p.getUniqueId())) plugin.send(p, "cancelled");
                else plugin.send(p, "nothing-playing");
                return;
            }
            default -> {
                return;
            }
        }

        plugin.data().saveAsync();
        render(p);
        click(p);
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
    }
}


