package vn.pab;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/** Vỏ bọc quanh lệnh gốc: chặn trước khi lệnh gốc được thực thi. */
public final class WrappedCommand extends Command {
    private final PAB plugin;
    private final Command original;

    public WrappedCommand(PAB plugin, Command original) {
        super(original.getName(), original.getDescription(), original.getUsage(),
                new ArrayList<>(original.getAliases()));
        this.plugin = plugin;
        this.original = original;
    }

    public Command original() { return original; }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        PabEntry hit = plugin.guard().inspect(sender, label, args);
        if (hit != null) {
            plugin.deny(sender, hit, label + " " + String.join(" ", args));
            return true;
        }
        return original.execute(sender, label, args);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) throws IllegalArgumentException {
        return original.tabComplete(sender, alias, args);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args, Location location)
            throws IllegalArgumentException {
        return original.tabComplete(sender, alias, args, location);
    }

    @Override
    public String getPermission() { return original.getPermission(); }

    @Override
    public boolean testPermission(CommandSender target) { return original.testPermission(target); }

    @Override
    public boolean testPermissionSilent(CommandSender target) { return original.testPermissionSilent(target); }
}
