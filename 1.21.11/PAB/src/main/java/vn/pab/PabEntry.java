package vn.pab;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Một người chơi được bảo vệ. */
public final class PabEntry {
    private volatile String name;
    private volatile String message;
    private final Set<String> names = ConcurrentHashMap.newKeySet();
    private final Set<UUID> uuids = ConcurrentHashMap.newKeySet();
    private final Map<String, InetAddress> ips = new ConcurrentHashMap<>();

    public PabEntry(String name) {
        this.name = name;
        this.names.add(name.toLowerCase(java.util.Locale.ROOT));
    }

    public String name() { return name; }
    public void setName(String name) { this.name = name; }
    public String message() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Set<String> names() { return names; }
    public Set<UUID> uuids() { return uuids; }
    public Map<String, InetAddress> ips() { return ips; }
}
