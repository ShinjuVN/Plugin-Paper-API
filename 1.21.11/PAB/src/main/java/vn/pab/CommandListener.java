package vn.pab;

import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Arrays;

/** Chặn ở tầng sự kiện: người chơi, console, RCON, command block. */
public final class CommandListener implements Listener {
    private final PAB plugin;

    public CommandListener(PAB plugin) { this.plugin = plugin; }

    // ---- người chơi
    @EventHandler(priority = EventPriority.LOWEST)
    public void playerLow(PlayerCommandPreprocessEvent e) { handle(e.getPlayer(), e.getMessage(), e, false); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void playerHigh(PlayerCommandPreprocessEvent e) { handle(e.getPlayer(), e.getMessage(), e, true); }

    // ---- console / rcon / command block
    @EventHandler(priority = EventPriority.LOWEST)
    public void serverLow(ServerCommandEvent e) { handle(e.getSender(), e.getCommand(), e, false); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void serverHigh(ServerCommandEvent e) { handle(e.getSender(), e.getCommand(), e, true); }

    private void handle(CommandSender sender, String line, Cancellable ev, boolean skipIfCancelled) {
        if (skipIfCancelled && ev.isCancelled()) return;
        if (line == null) return;
        String s = line.trim();
        while (s.startsWith("/")) s = s.substring(1);
        if (s.isEmpty()) return;
        String[] parts = s.split("\\s+");
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        PabEntry hit = plugin.guard().inspect(sender, parts[0], args);
        if (hit != null) {
            ev.setCancelled(true);
            plugin.deny(sender, hit, s);
        }
    }
}
