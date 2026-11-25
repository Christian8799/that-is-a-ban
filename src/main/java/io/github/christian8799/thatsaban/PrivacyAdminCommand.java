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

final class PrivacyAdminCommand implements CommandExecutor, TabCompleter {

    private final ThatsABan plugin;
    private final LinkStore store;
    private final Layer2Service layer2;
    private final LuckPermsHook hook;

    PrivacyAdminCommand(ThatsABan plugin, LinkStore store, Layer2Service layer2, LuckPermsHook hook) {
        this.plugin = plugin;
        this.store = store;
        this.layer2 = layer2;
        this.hook = hook;
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
            case "unoffend" -> "thatsaban.layer2.remove";
            case "forget" -> "thatsaban.privacy.erase";
            case "network" -> "thatsaban.network";
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

        String input = args[1];
        UUID target = resolve(input);
        if (target == null) {
            sender.sendMessage("Spieler nicht gefunden. Verwende einen bekannten Namen oder eine UUID.");
            return true;
        }

        switch (action) {
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
            }
            case "info" -> {
                LinkStore.PlayerSnapshot snapshot = store.snapshot(target);
                if (store.isWriteBlocked()) {
                    sender.sendMessage("WARNUNG: LinkStore ist schreibgeschützt; die Auskunft kann unvollständig sein.");
                }
                hook.users().loadUser(target).whenComplete((user, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error != null || user == null) {
                                sender.sendMessage("LuckPerms-Auskunft konnte nicht geladen werden.");
                                return;
                            }
                            sender.sendMessage("Datenauskunft für UUID " + target + ":");
                            sender.sendMessage("Gespeicherter Name: "
                                    + (snapshot.name() == null ? "keiner" : snapshot.name()));
                            sender.sendMessage("Verknüpfte IP-Hash-Einträge: " + snapshot.linkedIpCount());
                            sender.sendMessage("Offender-Eintrag: "
                                    + (snapshot.offender() ? "vorhanden" : "nicht vorhanden"));
                            sender.sendMessage("Layer-2-Aufbewahrungseintrag: "
                                    + (snapshot.layer2Tracked() ? "vorhanden" : "nicht vorhanden"));
                            sender.sendMessage("Plugin-Datensatz vorhanden: "
                                    + (snapshot.hasStoredData() ? "ja" : "nein"));
                            sender.sendMessage("LuckPerms-Layer-2-Marker effektiv: "
                                    + hook.has(user, hook.markerNode()));
                            for (String node : hook.negatedNodes()) {
                                sender.sendMessage("LuckPerms-Node " + node + " effektiv: "
                                        + hook.has(user, node));
                            }
                        }));
            }
            case "unoffend" -> {
                layer2.removeLayer2(target).whenComplete((stillMarked, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error != null) {
                                sender.sendMessage("Layer 2 konnte nicht entfernt werden.");
                            } else if (Boolean.TRUE.equals(stillMarked)) {
                                sender.sendMessage("Direkte Layer-2-Nodes wurden entfernt, aber der Marker ist "
                                        + "weiterhin effektiv (möglicherweise über eine LuckPerms-Gruppe oder einen Kontext).");
                            } else {
                                sender.sendMessage("Layer 2 und der Offender-Eintrag wurden entfernt: " + target);
                            }
                        }));
            }
            case "forget" -> {
                if (args.length != 3 || !args[2].equalsIgnoreCase("confirm")) {
                    sender.sendMessage("Vollständiges Löschen der Plugin-Daten ist endgültig. Bestätige mit: "
                            + "/thatsaban forget " + input + " confirm");
                    return true;
                }
                layer2.forgetPlayer(target).whenComplete((removedHashes, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error != null) {
                                sender.sendMessage("Die LuckPerms-Daten konnten nicht bereinigt werden. "
                                        + "Der Plugin-Datensatz blieb unverändert.");
                            } else {
                                sender.sendMessage("Plugin-Daten gelöscht; entfernte IP-Hash-Verknüpfungen: "
                                        + removedHashes + ". Plugin-Layer-2-Nodes wurden entfernt.");
                                sender.sendMessage("Geteilte IP-Hashes bleiben erhalten, falls sie noch anderen "
                                        + "Spielern zugeordnet sind. Andere LuckPerms-Daten bleiben unberührt.");
                            }
                        }));
            }
            default -> sender.sendMessage("Benutzung: /thatsaban <info|network|unoffend|forget> <Spieler|UUID> [depth|confirm]");
        }
        return true;
    }

    private UUID resolve(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
            // Weiter mit Namen.
        }
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return online.getUniqueId();
        }
        UUID known = store.uuidByName(input);
        if (known != null) {
            return known;
        }
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
            return options.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2) {
            String action = args[0].toLowerCase(Locale.ROOT);
            String permission = switch (action) {
                case "info" -> "thatsaban.privacy.read";
                case "unoffend" -> "thatsaban.layer2.remove";
                case "forget" -> "thatsaban.privacy.erase";
                case "network" -> "thatsaban.network";
                default -> null;
            };
            if (permission == null || (!sender.hasPermission(permission)
                    && !sender.hasPermission("thatsaban.admin"))) {
                return List.of();
            }
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("network")
                && (sender.hasPermission("thatsaban.network") || sender.hasPermission("thatsaban.admin"))) {
            return List.of("1", "2", "3");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("forget")
                && (sender.hasPermission("thatsaban.privacy.erase") || sender.hasPermission("thatsaban.admin"))) {
            return List.of("confirm");
        }
        return List.of();
    }
}
