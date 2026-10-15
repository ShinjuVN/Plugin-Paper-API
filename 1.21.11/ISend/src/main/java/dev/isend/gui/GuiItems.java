package dev.isend.gui;

import dev.isend.config.ConfigManager;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

final class GuiItems {

    private GuiItems() {
    }

    static ItemStack build(ConfigManager cfg, Material material, String name, List<String> lore,
                           TagResolver... resolvers) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(cfg.item(name, resolvers));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(cfg.itemLore(lore, resolvers));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Maps any configured slot onto the bottom row of an inventory by its column. */
    static int bottomSlot(int configured, int size) {
        return Math.floorMod(configured, 9) + size - 9;
    }

    /** Multiple of 9 between 18 and 54. */
    static int clampSize(int value) {
        return Math.max(18, Math.min(54, (value / 9) * 9));
    }
}
