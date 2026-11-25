package io.github.christian8799.thatsaban;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Store-only subset of /thatsaban that remains usable without LuckPerms. */
final class PrivacyStoreCommand implements CommandExecutor, TabCompleter {
    private final ThatsABan plugin;
    private final LinkStore store;

    PrivacyStoreCommand(ThatsABan plugin, LinkStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Benutzung: /thatsaban <info|network|unoffend|forget> <Spieler|UUID> [depth|confirm]");
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        String permission = switch (action) {
            case "info" -> "thatsaban.privacy.read";
            case "network" -> "thatsaban.network";
            case "unoffend" -> "thatsaban.layer2.remove";
            case "forget" -> "thatsaban.privacy.erase";
            default -> null;
        };
        if (permission == null) {
            sender.sendMessage("Benutzung: /thatsaban <info|network|unoffend|forget> <Spieler|UUID> [depth|confirm]");
            return true;
        }
        if (!sender.hasPermission(permission) && !sender.hasPermission("thatsaban.admin")) {
            sender.sendMessage("Dafür fehlt die Berechtigung " + permission + ".");
            return true;
        }

        UUID target = resolve(args[1]);
        if (target == null) {
            sender.sendMessage("Spieler nicht gefunden. Verwende einen bekannten Namen oder eine UUID.");
            return true;
        }

        switch (action) {
            case "info" -> {
                LinkStore.PlayerSnapshot snapshot = store.snapshot(target);
                sender.sendMessage("Datenauskunft für UUID " + target + ":");
                sender.sendMessage("Gespeicherter Name: " + (snapshot.name() == null ? "keiner" : snapshot.name()));
                sender.sendMessage("Verknüpfte IP-Hash-Einträge: " + snapshot.linkedIpCount());
                sender.sendMessage("Offender-Eintrag: " + (snapshot.offender() ? "vorhanden" : "nicht vorhanden"));
                sender.sendMessage("Layer-2-Aufbewahrungseintrag: "
                        + (snapshot.layer2Tracked() ? "vorhanden" : "nicht vorhanden"));
                sender.sendMessage("Plugin-Datensatz vorhanden: " + (snapshot.hasStoredData() ? "ja" : "nein"));
                sender.sendMessage("LuckPerms-Details nicht verfügbar: LuckPerms ist nicht aktiviert.");
                if (store.isWriteBlocked()) {
                    sender.sendMessage("WARNUNG: LinkStore ist schreibgeschützt; die Auskunft kann unvollständig sein.");
                }
            }
            case "network" -> {
                int depth = 2;
                if (args.length >= 3) {
                    try {
                        depth = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage("Tiefe muss eine Zahl zwischen 1 und 3 sein.");
                        return true;
                    }
                }
                if (depth < 1 || depth > 3) {
                    sender.sendMessage("Tiefe muss zwischen 1 und 3 liegen.");
                    return true;
                }
                if (!plugin.getConfig().getBoolean("alt-detection.enabled", false)) {
                    sender.sendMessage("Alt-Account-Erkennung ist deaktiviert. Aktiviere alt-detection.enabled in config.yml.");
                    return true;
                }
                if (store.isWriteBlocked()) {
                    sender.sendMessage("Account-Netzwerk nicht verfügbar: LinkStore ist schreibgeschützt.");
                    return true;
                }
                LinkStore.NetworkSnapshot graph = store.network(target, depth, 50);
                boolean showNames = sender.hasPermission("thatsaban.view-linked-accounts")
                        || sender.hasPermission("thatsaban.admin");
                sender.sendMessage("Account-Netzwerk für " + target + " (max. Tiefe " + depth + "):");
                sender.sendMessage("Start: " + (showNames && store.nameOf(target) != null
                        ? store.nameOf(target) : target.toString()));
                if (graph.accounts().isEmpty()) {
                    sender.sendMessage("Keine gespeicherten Verbindungen innerhalb der Aufbewahrungsfrist gefunden.");
                } else {
                    for (LinkStore.NetworkEntry entry : graph.accounts()) {
                        String identity = showNames && entry.name() != null
                                ? entry.name() : entry.uuid().toString();
                        String lastSeen = entry.lastSeen() > 0L
                                ? java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                                    .withZone(java.time.ZoneId.systemDefault())
                                    .format(java.time.Instant.ofEpochMilli(entry.lastSeen()))
                                : "unbekannt";
                        String relation = entry.depth() == 1 ? "direkt" : "indirekt";
                        String viaName = showNames && store.nameOf(entry.connectedVia()) != null
                                ? store.nameOf(entry.connectedVia()) : entry.connectedVia().toString();
                        sender.sendMessage("  " + (entry.depth() == 1 ? "├─ " : "└─ ")
                                + identity + " [über " + viaName + ", " + relation + ", Tiefe " + entry.depth()
                                + ", gemeinsame IP-HMACs: " + entry.sharedIpCount()
                                + ", zuletzt gesehen: " + lastSeen + "]");
                    }
                    if (graph.truncated()) {
                        sender.sendMessage("Anzeige auf 50 Accounts begrenzt. Für weitere Ergebnisse engeren Startpunkt wählen.");
                    }
                }
                sender.sendMessage("Wichtig: Gemeinsame IP-Adressen sind kein Identitätsnachweis. Indirekte Verbindungen sind besonders unsicher. Keine automatische Sanktion daraus ableiten.");
                sender.sendMessage("Hinweis: Ohne LuckPerms werden keine neuen Login-Verknüpfungen erfasst.");
            }
            case "unoffend", "forget" -> sender.sendMessage(
                    "Diese Aktion benötigt LuckPerms und ist deshalb derzeit deaktiviert. Andere ThatsABan-Funktionen bleiben aktiv.");
            default -> sender.sendMessage("Unbekannte Aktion.");
        }
        return true;
    }

    private UUID resolve(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) { }
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) return online.getUniqueId();
        UUID known = store.uuidByName(input);
        if (known != null) return known;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
        return cached == null ? null : cached.getUniqueId();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("thatsaban.privacy.read") || sender.hasPermission("thatsaban.admin")) options.add("info");
            if (sender.hasPermission("thatsaban.network") || sender.hasPermission("thatsaban.admin")) options.add("network");
            if (sender.hasPermission("thatsaban.layer2.remove") || sender.hasPermission("thatsaban.admin")) options.add("unoffend");
            if (sender.hasPermission("thatsaban.privacy.erase") || sender.hasPermission("thatsaban.admin")) options.add("forget");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("network")) return List.of("1", "2", "3");
        if (args.length == 3 && args[0].equalsIgnoreCase("forget")) return List.of("confirm");
        return List.of();
    }
}