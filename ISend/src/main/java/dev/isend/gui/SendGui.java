package dev.isend.gui;

import dev.isend.ISendPlugin;
import dev.isend.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The "put items here and press the button" GUI. Upper rows hold items, bottom row is the control bar. */
public final class SendGui {

    public static final class Holder implements InventoryHolder {
        private final UUID targetId;
        private final String targetName;
        private Inventory inventory;
        private boolean closing;

        Holder(UUID targetId, String targetName) {
            this.targetId = targetId;
            this.targetName = targetName;
        }

        public UUID targetId() { return targetId; }
        public String targetName() { return targetName; }
        public boolean isClosing() { return closing; }
        public void setClosing(boolean closing) { this.closing = closing; }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final ISendPlugin plugin;

    public SendGui(ISendPlugin plugin) {
        this.plugin = plugin;
    }

    public int submitSlot(int size) {
        return GuiItems.bottomSlot(plugin.config().intValue("gui.send.submit-button.slot", 26), size);
    }

    public int cancelSlot(int size) {
        return GuiItems.bottomSlot(plugin.config().intValue("gui.send.cancel-button.slot", 18), size);
    }

    public int statusSlot(int size) {
        return GuiItems.bottomSlot(plugin.config().intValue("gui.send.status-indicator.slot", 22), size);
    }

    public void open(Player player, UUID targetId, String targetName) {
        ConfigManager cfg = plugin.config();
        int size = GuiItems.clampSize(cfg.intValue("gui.send.size", 27));
        Holder holder = new Holder(targetId, targetName);
        Component title = cfg.parse(cfg.str("gui.send.title", "<dark_green>Gửi đồ đến: <player>"),
                Placeholder.unparsed("player", targetName));
        holder.inventory = Bukkit.createInventory(holder, size, title);
        refresh(holder);
        player.openInventory(holder.inventory);
    }

    /** Rebuilds the control bar (submit colour, status, cancel) from the current contents. */
    public void refresh(Holder holder) {
        ConfigManager cfg = plugin.config();
        Inventory inv = holder.getInventory();
        int size = inv.getSize();
        int itemSlots = size - 9;

        ItemStack filler = GuiItems.build(cfg,
                cfg.material("gui.send.filler.material", Material.GRAY_STAINED_GLASS_PANE),
                cfg.str("gui.send.filler.name", " "), null);
        for (int i = itemSlots; i < size; i++) {
            inv.setItem(i, filler);
        }

        List<ItemStack> items = collect(inv);
        int stacks = items.size();
        int total = items.stream().mapToInt(ItemStack::getAmount).sum();
        double cost = plugin.service().calculateCost(total);
        TagResolver[] tags = {
                Placeholder.unparsed("player", holder.targetName()),
                Placeholder.unparsed("cost", cost > 0 ? plugin.service().formatMoney(cost) : "0"),
                Placeholder.unparsed("stacks", String.valueOf(stacks)),
                Placeholder.unparsed("items", String.valueOf(total))
        };

        boolean ready = stacks > 0;
        Material submitMaterial = ready
                ? cfg.material("gui.send.submit-button.material-ready", Material.LIME_STAINED_GLASS_PANE)
                : cfg.material("gui.send.submit-button.material-empty", Material.RED_STAINED_GLASS_PANE);
        String submitName = ready
                ? cfg.str("gui.send.submit-button.name-ready", "<green>Nhấn để gửi đồ")
                : cfg.str("gui.send.submit-button.name-empty", "<red>Chưa có vật phẩm để gửi");
        List<String> submitLore = cfg.stringList(
                ready ? "gui.send.submit-button.lore-ready" : "gui.send.submit-button.lore-empty");
        inv.setItem(submitSlot(size), GuiItems.build(cfg, submitMaterial, submitName, submitLore, tags));

        inv.setItem(cancelSlot(size), GuiItems.build(cfg,
                cfg.material("gui.send.cancel-button.material", Material.BARRIER),
                cfg.str("gui.send.cancel-button.name", "<red>Hủy bỏ"),
                cfg.stringList("gui.send.cancel-button.lore"), tags));

        inv.setItem(statusSlot(size), GuiItems.build(cfg,
                cfg.material("gui.send.status-indicator.material", Material.PAPER),
                cfg.str("gui.send.status-indicator.name", "<yellow>Người nhận: <white><player>"),
                cfg.stringList("gui.send.status-indicator.lore"), tags));
    }

    /** Non-empty stacks currently in the item area (live references, clone before keeping). */
    public List<ItemStack> collect(Inventory inv) {
        List<ItemStack> items = new ArrayList<>();
        int itemSlots = inv.getSize() - 9;
        for (int i = 0; i < itemSlots; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) {
                items.add(item);
            }
        }
        return items;
    }

    public void clearItems(Inventory inv) {
        int itemSlots = inv.getSize() - 9;
        for (int i = 0; i < itemSlots; i++) {
            inv.setItem(i, null);
        }
    }

    /** Gives everything left in the item area back to the player (drops what does not fit). Idempotent. */
    public void returnItems(Holder holder, Player player) {
        Inventory inv = holder.getInventory();
        int itemSlots = inv.getSize() - 9;
        for (int i = 0; i < itemSlots; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            inv.setItem(i, null);
            for (ItemStack rest : player.getInventory().addItem(item.clone()).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
    }
}
