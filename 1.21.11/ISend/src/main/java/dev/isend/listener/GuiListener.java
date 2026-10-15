package dev.isend.listener;

import dev.isend.ISendPlugin;
import dev.isend.gui.InboxGui;
import dev.isend.gui.SendGui;
import dev.isend.service.MailService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class GuiListener implements Listener {

    private final ISendPlugin plugin;

    public GuiListener(ISendPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder(false);
        if (holder instanceof SendGui.Holder sendHolder) {
            handleSendClick(event, player, top, sendHolder);
        } else if (holder instanceof InboxGui.Holder inboxHolder) {
            event.setCancelled(true);
            if (top.equals(event.getClickedInventory())) {
                plugin.inboxGui().handleClick(player, inboxHolder, event.getRawSlot());
            }
        }
    }

    private void handleSendClick(InventoryClickEvent event, Player player, Inventory top, SendGui.Holder holder) {
        // Double-click "collect to cursor" could pull control-bar items, so it is disabled here.
        if (holder.isClosing() || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }
        int size = top.getSize();
        int itemSlots = size - 9;
        int raw = event.getRawSlot();

        if (raw >= itemSlots && raw < size) { // control bar
            event.setCancelled(true);
            if (raw == plugin.sendGui().submitSlot(size)) {
                submit(player, holder);
            } else if (raw == plugin.sendGui().cancelSlot(size)) {
                holder.setClosing(true);
                closeLater(player, holder);
            }
            return;
        }
        scheduleRefresh(holder);
    }

    private void submit(Player player, SendGui.Holder holder) {
        Inventory inv = holder.getInventory();
        List<ItemStack> items = plugin.sendGui().collect(inv);
        MailService.SendResult result = plugin.service().send(player, holder.targetId(), holder.targetName(), items);
        switch (result) {
            case SUCCESS -> {
                plugin.sendGui().clearItems(inv);
                holder.setClosing(true);
                closeLater(player, holder);
            }
            case ABORT -> {
                holder.setClosing(true);
                closeLater(player, holder); // close handler returns the items
            }
            case RETRY -> scheduleRefresh(holder);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder(false);
        if (holder instanceof InboxGui.Holder) {
            event.setCancelled(true);
        } else if (holder instanceof SendGui.Holder sendHolder) {
            int size = top.getSize();
            for (int raw : event.getRawSlots()) {
                if (raw >= size - 9 && raw < size) {
                    event.setCancelled(true);
                    return;
                }
            }
            scheduleRefresh(sendHolder);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof SendGui.Holder holder
                && event.getPlayer() instanceof Player player) {
            plugin.sendGui().returnItems(holder, player);
        }
    }

    private void scheduleRefresh(SendGui.Holder holder) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!holder.getInventory().getViewers().isEmpty()) {
                plugin.sendGui().refresh(holder);
            }
        });
    }

    private void closeLater(Player player, InventoryHolder expected) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()
                    && player.getOpenInventory().getTopInventory().getHolder(false) == expected) {
                player.closeInventory();
            }
        });
    }
}
