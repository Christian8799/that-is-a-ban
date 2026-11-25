package io.github.christian8799.thatsaban;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import java.util.List;

final class FeatureUnavailableCommand implements CommandExecutor, TabCompleter {
    private final String message;
    FeatureUnavailableCommand(String message) { this.message = message; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(message);
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}