package dev.isend.service;

import dev.isend.ISendPlugin;
import dev.isend.config.ConfigManager;
import dev.isend.economy.EconomyHook;
import dev.isend.storage.MailEntry;
import dev.isend.storage.PlayerData;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sending, restriction checks and claiming. Keeps the GUI classes free of business logic. */
public final class MailService {

    public enum SendResult {
        /** Mail stored. */
        SUCCESS,
        /** Not sent, keep the GUI open so the player can fix it. */
        RETRY,
        /** Not sent, close the GUI (items are returned). */
        ABORT
    }

    public enum ClaimOutcome { TAKEN, PARTIAL, NO_SPACE, GONE }

    private final ISendPlugin plugin;

    public MailService(ISendPlugin plugin) {
        this.plugin = plugin;
    }

    public double calculateCost(int totalItems) {
        ConfigManager cfg = plugin.config();
        return cfg.deliveryFee() + cfg.chargePerItem() * totalItems;
    }

    public String formatMoney(double amount) {
        return plugin.economy().format(amount);
    }

    /**
     * Returns the message key explaining why the sender may not mail the target, or null if allowed.
     * @param atSubmit true when re-validating on submit (uses the "you-are-blocked" wording)
     */
    public String checkRestrictions(UUID senderId, UUID targetId, boolean atSubmit) {
        if (senderId.equals(targetId)) {
            return plugin.config().allowSendingToSelf() ? null : "cannot-send-self";
        }
        PlayerData target = plugin.storage().get(targetId);
        if (target.hasException(senderId)) {
            return null; // exceptions bypass both "receive off" and blocks
        }
        if (!target.isReceiving()) {
            return "receiver-toggled-off";
        }
        if (target.isBlocked(senderId)) {
            return atSubmit ? "you-are-blocked" : "receiver-blocked-you";
        }
        return null;
    }

    public SendResult send(Player sender, UUID targetId, String targetName, List<ItemStack> items) {
        ConfigManager cfg = plugin.config();
        TagResolver playerTag = Placeholder.unparsed("player", targetName);

        if (items.isEmpty()) {
            cfg.send(sender, "inventory-empty-send");
            return SendResult.RETRY;
        }
        int max = cfg.maxItemsPerMail();
        if (items.size() > max) {
            cfg.send(sender, "too-many-items", Placeholder.unparsed("max", String.valueOf(max)));
            return SendResult.RETRY;
        }
        String denial = checkRestrictions(sender.getUniqueId(), targetId, true);
        if (denial != null) {
            cfg.send(sender, denial, playerTag);
            return SendResult.ABORT;
        }

        int totalItems = items.stream().mapToInt(ItemStack::getAmount).sum();
        double cost = calculateCost(totalItems);
        TagResolver costTag = Placeholder.unparsed("cost", cost > 0 ? formatMoney(cost) : "0");
        EconomyHook economy = plugin.economy();
        boolean charged = false;
        if (cost > 0) {
            if (!economy.isAvailable()) {
                cfg.send(sender, "economy-unavailable");
                return SendResult.RETRY;
            }
            if (!economy.has(sender, cost) || !economy.withdraw(sender, cost)) {
                cfg.send(sender, "not-enough-money", costTag);
                return SendResult.RETRY;
            }
            charged = true;
        }

        PlayerData target = plugin.storage().get(targetId);
        long now = System.currentTimeMillis();
        for (ItemStack item : items) {
            target.addMail(new MailEntry(UUID.randomUUID(), sender.getUniqueId(), sender.getName(), now, item.clone()));
        }
        if (!plugin.storage().save(target)) {
            if (charged) {
                economy.deposit(sender, cost); // refund
            }
            cfg.send(sender, "error-generic");
            return SendResult.ABORT;
        }

        cfg.send(sender, "mail-sent-success", playerTag, costTag);
        Player online = Bukkit.getPlayer(targetId);
        if (online != null && online.isOnline()) {
            cfg.sendRaw(online, "mail-notification", Placeholder.unparsed("sender", sender.getName()));
        }
        return SendResult.SUCCESS;
    }

    /** Moves one mail into the claimer's inventory. Caller must save {@code owner} afterwards. */
    public ClaimOutcome claim(Player claimer, PlayerData owner, MailEntry entry) {
        if (owner.getMail(entry.id()) == null) {
            return ClaimOutcome.GONE;
        }
        ItemStack original = entry.item();
        Map<Integer, ItemStack> leftover = claimer.getInventory().addItem(original.clone());
        if (leftover.isEmpty()) {
            owner.removeMail(entry.id());
            return ClaimOutcome.TAKEN;
        }
        if (plugin.config().dropOnFull()) {
            for (ItemStack rest : leftover.values()) {
                claimer.getWorld().dropItemNaturally(claimer.getLocation(), rest);
            }
            owner.removeMail(entry.id());
            return ClaimOutcome.TAKEN;
        }
        int remaining = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (remaining >= original.getAmount()) {
            return ClaimOutcome.NO_SPACE;
        }
        ItemStack rest = original.clone();
        rest.setAmount(remaining);
        owner.replaceMail(entry.withItem(rest));
        return ClaimOutcome.PARTIAL;
    }

    /** Claims every given mail and saves. Returns true if all of them were fully taken. */
    public boolean claimAll(Player claimer, PlayerData owner, List<MailEntry> entries) {
        boolean all = true;
        for (MailEntry entry : entries) {
            ClaimOutcome outcome = claim(claimer, owner, entry);
            if (outcome == ClaimOutcome.PARTIAL || outcome == ClaimOutcome.NO_SPACE) {
                all = false;
            }
        }
        if (!plugin.storage().save(owner)) {
            plugin.config().send(claimer, "error-generic");
            return false;
        }
        plugin.config().send(claimer, all ? "claim-all-success" : "inventory-full");
        return all;
    }
}
