package dev.isend.storage;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public record MailEntry(UUID id, UUID senderId, String senderName, long timestamp, ItemStack item) {

    public MailEntry withItem(ItemStack newItem) {
        return new MailEntry(id, senderId, senderName, timestamp, newItem);
    }
}
