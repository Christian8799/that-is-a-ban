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
            sender.sendMessage("Usage: /thatsaban <info|network|unoffend|forget> <player|UUID> [depth|confirm]");
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
            sender.sendMessage("Usage: /thatsaban <info|network|unoffend|forget> <player|UUID> [depth|confirm]");
            return true;
        }
        if (!sender.hasPermission(permission) && !sender.hasPermission("thatsaban.admin")) {
            sender.sendMessage("Missing permission: " + permission + ".");
            return true;
        }

        UUID target = resolve(args[1]);
        if (target == null) {
            sender.sendMessage("Player not found. Use a known name or UUID.");
            return true;
        }

        switch (action) {
            case "info" -> {
                LinkStore.PlayerSnapshot snapshot = store.snapshot(target);
                sender.sendMessage("Data export for UUID " + target + ":");
                sender.sendMessage("Stored name: " + (snapshot.name() == null ? "none" : snapshot.name()));
                sender.sendMessage("Linked IP hash entries: " + snapshot.linkedIpCount());
                sender.sendMessage("Offender entry: " + (snapshot.offender() ? "present" : "not present"));
                sender.sendMessage("Layer 2 retention entry: "
                        + (snapshot.layer2Tracked() ? "present" : "not present"));
                sender.sendMessage("Plugin record exists: " + (snapshot.hasStoredData() ? "yes" : "no"));
                sender.sendMessage("LuckPerms details unavailable: LuckPerms is not enabled.");
                if (store.isWriteBlocked()) {
                    sender.sendMessage("WARNING: LinkStore is read-only; this information may be incomplete.");
                }
            }
            case "network" -> {
                int depth = 2;
                if (args.length >= 3) {
                    try {
                        depth = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage("Depth must be a number between 1 and 3.");
                        return true;
                    }
                }
                if (depth < 1 || depth > 3) {
                    sender.sendMessage("Depth must be between 1 and 3.");
                    return true;
                }
                if (!plugin.getConfig().getBoolean("alt-detection.enabled", false)) {
                    sender.sendMessage("Alt-account detection is disabled. Enable alt-detection.enabled in config.yml.");
                    return true;
                }
                if (store.isWriteBlocked()) {
                    sender.sendMessage("Account network unavailable: LinkStore is read-only.");
                    return true;
                }
                LinkStore.NetworkSnapshot graph = store.network(target, depth, 50);
                boolean showNames = sender.hasPermission("thatsaban.view-linked-accounts")
                        || sender.hasPermission("thatsaban.admin");
                sender.sendMessage("Account network for " + target + " (maximum depth " + depth + "):");
                sender.sendMessage("Start: " + (showNames && store.nameOf(target) != null
                        ? store.nameOf(target) : target.toString()));
                if (graph.accounts().isEmpty()) {
                    sender.sendMessage("No stored connections found within the retention period.");
                } else {
                    for (LinkStore.NetworkEntry entry : graph.accounts()) {
                        String identity = showNames && entry.name() != null
                                ? entry.name() : entry.uuid().toString();
                        String lastSeen = entry.lastSeen() > 0L
                                ? java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                                    .withZone(java.time.ZoneId.systemDefault())
                                    .format(java.time.Instant.ofEpochMilli(entry.lastSeen()))
                                : "unknown";
                        String relation = entry.depth() == 1 ? "direct" : "indirect";
                        String viaName = showNames && store.nameOf(entry.connectedVia()) != null
                                ? store.nameOf(entry.connectedVia()) : entry.connectedVia().toString();
                        sender.sendMessage("  " + (entry.depth() == 1 ? "├─ " : "└─ ")
                                + identity + " [via " + viaName + ", " + relation + ", depth " + entry.depth()
                                + ", shared IP HMACs: " + entry.sharedIpCount()
                                + ", last seen: " + lastSeen + "]");
                    }
                    if (graph.truncated()) {
                        sender.sendMessage("Display limited to 50 accounts. Choose a narrower starting point for more results.");
                    }
                }
                sender.sendMessage("Important: Shared IP addresses do not prove identity. Indirect connections are especially unreliable. Do not derive automatic sanctions from this data.");
                sender.sendMessage("Note: New login links are not recorded without LuckPerms.");
            }
            case "unoffend", "forget" -> sender.sendMessage(
                    "This action requires LuckPerms and is currently disabled. Other ThatsABan features remain available.");
            default -> sender.sendMessage("Unknown action.");
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
            String action = args[0].toLowerCase(Locale.ROOT);
            String permission = switch (action) {
                case "info" -> "thatsaban.privacy.read";
                case "network" -> "thatsaban.network";
                case "unoffend" -> "thatsaban.layer2.remove";
                case "forget" -> "thatsaban.privacy.erase";
                default -> null;
            };
            if (permission == null || (!sender.hasPermission(permission)
                    && !sender.hasPermission("thatsaban.admin"))) return List.of();
            String prefix = args[1].toLowerCase(Locale.ROOT);
            if (action.equals("unoffend")) {
                return store.offenderNames(prefix);
            }
            return store.knownPlayerNames(prefix);
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