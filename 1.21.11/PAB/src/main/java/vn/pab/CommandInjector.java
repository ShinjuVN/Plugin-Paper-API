package vn.pab;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** Bọc các lệnh ban trong CommandMap (bắt cả Bukkit.dispatchCommand của plugin khác). */
public final class CommandInjector {
    private final PAB plugin;
    private final Map<Command, WrappedCommand> wrappers = new IdentityHashMap<>();

    public CommandInjector(PAB plugin) { this.plugin = plugin; }

    public void inject() {
        try {
            Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
            for (Map.Entry<String, Command> en : new ArrayList<>(known.entrySet())) {
                Command c = en.getValue();
                if (c == null || c instanceof WrappedCommand) continue;
                if (!plugin.guard().isInjectTarget(c)) continue;
                WrappedCommand w = wrappers.computeIfAbsent(c, k -> new WrappedCommand(plugin, k));
                known.put(en.getKey(), w);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Không bọc được CommandMap: " + t);
        }
    }

    public void restore() {
        try {
            Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
            for (Map.Entry<String, Command> en : new ArrayList<>(known.entrySet())) {
                if (en.getValue() instanceof WrappedCommand w) known.put(en.getKey(), w.original());
            }
        } catch (Throwable ignored) { }
        wrappers.clear();
    }
}
