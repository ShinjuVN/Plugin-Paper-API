package dev.isend.gui;

import dev.isend.ISendPlugin;
import dev.isend.config.ConfigManager;
import dev.isend.service.MailService;
import dev.isend.service.MailService.ClaimOutcome;
import dev.isend.storage.MailEntry;
import dev.isend.storage.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Paginated mailbox GUI: own inbox, filtered by sender (/isend from) or an admin view (/isend see). */
public final class InboxGui {

    public enum Mode { OWN, FROM, SEE }

    public static final class Holder implements InventoryHolder {
        final UUID ownerId;
        final String ownerName;
        final Mode mode;
        final UUID filterId;
        final String filterName;
        final Map<Integer, UUID> slots = new HashMap<>();
        int page = 0;
        boolean hasNext;
        Inventory inventory;

        Holder(UUID ownerId, String ownerName, Mode mode, UUID filterId, String filterName) {
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.mode = mode;
            this.filterId = filterId;
            this.filterName = filterName;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final ISendPlugin plugin;

    public InboxGui(ISendPlugin plugin) {
        this.plugin = plugin;
    }

    private int claimSlot(int size) {
        return GuiItems.bottomSlot(plugin.config().intValue("gui.inbox.claim-all-button.slot", 53), size);
    }

    private int prevSlot(int size) {
        return GuiItems.bottomSlot(plugin.config().intValue("gui.inbox.previous-button.slot", 48), size);
    }

    private int nextSlot(int size) {
        return GuiItems.bottomSlot(plugin.config().intValue("gui.inbox.next-button.slot", 50), size);
    }

    public void open(Player viewer, UUID ownerId, String ownerName, Mode mode, UUID filterId, String filterName) {
        ConfigManager cfg = plugin.config();
        Holder holder = new Holder(ownerId, ownerName, mode, filterId, filterName);
        if (entries(holder).isEmpty()) {
            cfg.send(viewer, mode == Mode.SEE ? "inbox-empty-other" : "inbox-empty",
                    Placeholder.unparsed("player", ownerName));
            return;
        }
        String raw = switch (mode) {
            case OWN -> cfg.str("gui.inbox.title", "<blue>Hòm thư của bạn");
            case FROM -> cfg.str("gui.inbox.title-from", "<blue>Đồ gửi từ <sender>");
            case SEE -> cfg.str("gui.inbox.title-see", "<red>Hòm thư của <player>");
        };
        Component title = cfg.parse(raw,
                Placeholder.unparsed("player", ownerName),
                Placeholder.unparsed("sender", filterName == null ? "" : filterName));
        int size = GuiItems.clampSize(cfg.intValue("gui.inbox.size", 54));
        holder.inventory = Bukkit.createInventory(holder, size, title);
        render(holder);
        viewer.openInventory(holder.inventory);
    }

    private List<MailEntry> entries(Holder holder) {
        List<MailEntry> list = new ArrayList<>(plugin.storage().get(holder.ownerId).mails());
        if (holder.filterId != null) {
            list.removeIf(mail -> !mail.senderId().equals(holder.filterId));
        }
        list.sort(Comparator.comparingLong(MailEntry::timestamp));
        return list;
    }

    private void render(Holder holder) {
        ConfigManager cfg = plugin.config();
        Inventory inv = holder.inventory;
        int size = inv.getSize();
        int content = size - 9;
        List<MailEntry> entries = entries(holder);
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) content));
        holder.page = Math.max(0, Math.min(holder.page, pages - 1));

        inv.clear();
        holder.slots.clear();
        int start = holder.page * content;
        for (int i = 0; i < content && start + i < entries.size(); i++) {
            MailEntry entry = entries.get(start + i);
            inv.setItem(i, display(entry));
            holder.slots.put(i, entry.id());
        }

        ItemStack filler = GuiItems.build(cfg,
                cfg.material("gui.inbox.filler.material", Material.GRAY_STAINED_GLASS_PANE),
                cfg.str("gui.inbox.filler.name", " "), null);
        for (int i = content; i < size; i++) {
            inv.setItem(i, filler);
        }

        TagResolver[] pageTags = {
                Placeholder.unparsed("page", String.valueOf(holder.page + 1)),
                Placeholder.unparsed("pages", String.valueOf(pages))
        };
        inv.setItem(claimSlot(size), GuiItems.build(cfg,
                cfg.material("gui.inbox.claim-all-button.material", Material.EMERALD),
                cfg.str("gui.inbox.claim-all-button.name", "<green>Nhận tất cả"),
                cfg.stringList("gui.inbox.claim-all-button.lore"), pageTags));
        if (holder.page > 0) {
            inv.setItem(prevSlot(size), GuiItems.build(cfg,
                    cfg.material("gui.inbox.previous-button.material", Material.ARROW),
                    cfg.str("gui.inbox.previous-button.name", "<yellow>Trang trước"),
                    cfg.stringList("gui.inbox.previous-button.lore"), pageTags));
        }
        holder.hasNext = holder.page < pages - 1;
        if (holder.hasNext) {
            inv.setItem(nextSlot(size), GuiItems.build(cfg,
                    cfg.material("gui.inbox.next-button.material", Material.ARROW),
                    cfg.str("gui.inbox.next-button.name", "<yellow>Trang sau"),
                    cfg.stringList("gui.inbox.next-button.lore"), pageTags));
        }
    }

    private ItemStack display(MailEntry entry) {
        ConfigManager cfg = plugin.config();
        ItemStack display = entry.item().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.addAll(cfg.itemLore(cfg.stringList("gui.inbox.item-lore"),
                    Placeholder.unparsed("sender", entry.senderName()),
                    Placeholder.unparsed("time", formatTime(entry.timestamp()))));
            meta.lore(lore);
            display.setItemMeta(meta);
        }
        return display;
    }

    private String formatTime(long millis) {
        Instant instant = Instant.ofEpochMilli(millis);
        try {
            return DateTimeFormatter.ofPattern(plugin.config().dateFormat())
                    .withZone(ZoneId.systemDefault()).format(instant);
        } catch (IllegalArgumentException ex) {
            return instant.toString();
        }
    }

    public void handleClick(Player viewer, Holder holder, int raw) {
        ConfigManager cfg = plugin.config();
        Inventory inv = holder.inventory;
        int size = inv.getSize();
        if (raw < 0 || raw >= size) {
            return;
        }
        if (!holder.ownerId.equals(viewer.getUniqueId()) && !viewer.hasPermission("isend.admin")) {
            cfg.send(viewer, "no-permission");
            Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory());
            return;
        }

        MailService service = plugin.service();
        PlayerData owner = plugin.storage().get(holder.ownerId);

        if (raw == claimSlot(size)) {
            List<MailEntry> all = entries(holder);
            if (all.isEmpty()) {
                cfg.send(viewer, "inbox-empty");
                render(holder);
                return;
            }
            service.claimAll(viewer, owner, all);
            render(holder);
            return;
        }
        if (raw == prevSlot(size) && holder.page > 0) {
            holder.page--;
            render(holder);
            return;
        }
        if (raw == nextSlot(size) && holder.hasNext) {
            holder.page++;
            render(holder);
            return;
        }

        UUID entryId = holder.slots.get(raw);
        if (entryId == null) {
            return;
        }
        MailEntry entry = owner.getMail(entryId);
        if (entry == null) {
            render(holder); // already taken elsewhere
            return;
        }
        ClaimOutcome outcome = service.claim(viewer, owner, entry);
        if (!plugin.storage().save(owner)) {
            cfg.send(viewer, "error-generic");
            render(holder);
            return;
        }
        switch (outcome) {
            case TAKEN -> cfg.send(viewer, "claim-success");
            case PARTIAL, NO_SPACE -> cfg.send(viewer, "inventory-full");
            case GONE -> { }
        }
        render(holder);
    }
}
