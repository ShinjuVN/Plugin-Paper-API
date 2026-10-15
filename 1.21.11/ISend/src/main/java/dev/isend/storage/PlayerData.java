package dev.isend.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerData {

    private final UUID uuid;
    private boolean receiving = true;
    private final Set<UUID> blocked = new LinkedHashSet<>();
    private final Set<UUID> exceptions = new LinkedHashSet<>();
    private final Map<UUID, MailEntry> mails = new LinkedHashMap<>();
    /** Mail sections that could not be read (kept verbatim and written back on save). */
    private final Map<String, Map<String, Object>> unreadable = new LinkedHashMap<>();

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID uuid() { return uuid; }

    public boolean isReceiving() { return receiving; }
    public void setReceiving(boolean receiving) { this.receiving = receiving; }

    public Set<UUID> blocked() { return blocked; }
    public Set<UUID> exceptions() { return exceptions; }

    public boolean isBlocked(UUID other) { return blocked.contains(other); }
    public boolean hasException(UUID other) { return exceptions.contains(other); }

    public List<MailEntry> mails() { return new ArrayList<>(mails.values()); }
    public int mailCount() { return mails.size(); }
    public MailEntry getMail(UUID id) { return mails.get(id); }
    public void addMail(MailEntry entry) { mails.put(entry.id(), entry); }
    public boolean removeMail(UUID id) { return mails.remove(id) != null; }
    public void replaceMail(MailEntry entry) { mails.replace(entry.id(), entry); }

    public Map<String, Map<String, Object>> unreadable() { return unreadable; }

    public boolean isDefault() {
        return receiving && blocked.isEmpty() && exceptions.isEmpty() && mails.isEmpty()
                && unreadable.isEmpty();
    }
}
