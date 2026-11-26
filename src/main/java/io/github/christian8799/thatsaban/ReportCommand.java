package io.github.christian8799.thatsaban;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class ReportCommand implements CommandExecutor, TabCompleter {
    private final ReportManager reports;

    ReportCommand(ReportManager reports) {
        this.reports = reports;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player reporter)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (!reporter.hasPermission("thatsaban.report")) {
            reporter.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            reporter.sendMessage(Component.text("Usage: /report <player> <reason>", NamedTextColor.YELLOW));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            reporter.sendMessage(Component.text("That player is not online.", NamedTextColor.RED));
            return true;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        reports.submit(reporter, target, reason);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("thatsaban.report")) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> !(sender instanceof Player p) || !p.getUniqueId().equals(player.getUniqueId()))
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
