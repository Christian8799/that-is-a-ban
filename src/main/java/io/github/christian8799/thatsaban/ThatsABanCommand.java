package io.github.christian8799.thatsaban;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ThatsABanCommand implements CommandExecutor, TabCompleter {

    private final ThatsABan plugin;
    private final LinkStore store;
    private final CommandExecutor delegate;

    ThatsABanCommand(ThatsABan plugin, LinkStore store, CommandExecutor delegate) {
        this.plugin = plugin;
        this.store = store;
        this.delegate = delegate;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (args.length != 1) {
                sender.sendMessage(plugin.messages().get("reload-usage"));
                return true;
            }
            if (!sender.hasPermission("thatsaban.admin")) {
                sender.sendMessage(plugin.messages().get("reload-no-permission"));
                return true;
            }
            plugin.reloadConfiguration(sender);
            return true;
        }
        return delegate.onCommand(sender, command, label, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            if (sender.hasPermission("thatsaban.admin") && "reload".startsWith(prefix)) {
                suggestions.add("reload");
            }
            if (delegate instanceof TabCompleter completer) {
                suggestions.addAll(completer.onTabComplete(sender, command, alias, args));
            }
            return suggestions.stream().distinct().toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unoffend")
                && (sender.hasPermission("thatsaban.layer2.remove")
                || sender.hasPermission("thatsaban.admin"))) {
            return store.offenderNames(args[1]);
        }
        if (delegate instanceof TabCompleter completer) {
            return completer.onTabComplete(sender, command, alias, args);
        }
        return List.of();
    }
}
