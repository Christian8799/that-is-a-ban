package io.github.christian8799.thatsaban;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

final class ReportsCommand implements CommandExecutor, TabCompleter {
    private final ThatsABan plugin;
    private final ReportManager reports;

    ReportsCommand(ThatsABan plugin, ReportManager reports) {
        this.plugin = plugin;
        this.reports = reports;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("thatsaban.reports") && !sender.hasPermission("thatsaban.admin")) {
            sender.sendMessage(Component.text("Dafür hast du keine Berechtigung.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            sendList(sender);
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(Component.text("Benutzung: /reports [list|view|offend|resolve|dismiss] <ID>", NamedTextColor.YELLOW));
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        String id = args[1].toLowerCase(Locale.ROOT);
        ReportManager.ReportEntry entry = reports.find(id);
        if (entry == null) {
            sender.sendMessage(Component.text("Report #" + id + " wurde nicht gefunden.", NamedTextColor.RED));
            return true;
        }

        switch (action) {
            case "view" -> sendDetails(sender, entry);
            case "offend" -> {
                if (!sender.hasPermission("thatsaban.offend")) {
                    sender.sendMessage(Component.text("Dafür benötigst du zusätzlich thatsaban.offend.", NamedTextColor.RED));
                    return true;
                }
                sender.sendMessage(Component.text("Starte /offend für " + entry.targetName() + " …", NamedTextColor.YELLOW));
                plugin.getServer().dispatchCommand(sender, "offend " + entry.targetName());
            }
            case "resolve" -> {
                reports.setStatus(id, ReportManager.Status.RESOLVED);
                sender.sendMessage(Component.text("Report #" + id + " wurde als erledigt markiert.", NamedTextColor.GREEN));
            }
            case "dismiss" -> {
                reports.setStatus(id, ReportManager.Status.DISMISSED);
                sender.sendMessage(Component.text("Report #" + id + " wurde verworfen.", NamedTextColor.GRAY));
            }
            default -> sender.sendMessage(Component.text("Unbekannte Aktion. Nutze /reports.", NamedTextColor.RED));
        }
        return true;
    }

    private void sendList(CommandSender sender) {
        List<ReportManager.ReportEntry> entries = reports.reports().stream()
                .filter(entry -> entry.status() == ReportManager.Status.OPEN
                        || entry.status() == ReportManager.Status.CAPTURING
                        || entry.status() == ReportManager.Status.FAILED)
                .limit(15).toList();
        sender.sendMessage(Component.text("Offene Reports (" + entries.size() + "):", NamedTextColor.GOLD));
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("Keine offenen Reports.", NamedTextColor.GRAY));
            return;
        }
        for (ReportManager.ReportEntry entry : entries) {
            sender.sendMessage(Component.text("#" + entry.id() + " ", NamedTextColor.AQUA)
                    .append(Component.text(entry.targetName() + " — " + entry.status().name(), NamedTextColor.WHITE))
                    .append(Component.text("  /reports view " + entry.id(), NamedTextColor.GRAY)));
        }
    }

    private void sendDetails(CommandSender sender, ReportManager.ReportEntry entry) {
        sender.sendMessage(Component.text("Report #" + entry.id(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Spieler: " + entry.targetName() + " (" + entry.targetId() + ")", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("Gemeldet von: " + entry.reporterName(), NamedTextColor.WHITE));
        sender.sendMessage(Component.text("Zeit: " + Instant.ofEpochMilli(entry.createdAt()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Status: " + entry.status().name(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Grund: " + entry.reason(), NamedTextColor.WHITE));
        if (entry.replayPath() != null) {
            sender.sendMessage(Component.text("Replay-Pfad (anklicken zum Kopieren): ", NamedTextColor.GRAY)
                    .append(Component.text(entry.replayPath(), NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.copyToClipboard(entry.replayPath()))));
        } else {
            sender.sendMessage(Component.text("Replay: noch nicht verfügbar.", NamedTextColor.RED));
        }
        sender.sendMessage(Component.text("Aktionen: /reports offend " + entry.id()
                + " | /reports resolve " + entry.id() + " | /reports dismiss " + entry.id(), NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("thatsaban.reports") && !sender.hasPermission("thatsaban.admin")) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("list", "view", "offend", "resolve", "dismiss").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return reports.reports().stream().map(ReportManager.ReportEntry::id)
                    .filter(id -> id.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
