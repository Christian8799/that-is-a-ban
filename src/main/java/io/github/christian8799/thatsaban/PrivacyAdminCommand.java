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
            sender.sendMessage("Usage: /thatsaban <info|network|unoffend|forget> <player|UUID> [depth|confirm]");
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
            sender.sendMessage("Usage: /thatsaban <info|network|unoffend|forget> <player|UUID> [depth|confirm]");
            return true;
        }
        if (!sender.hasPermission(permission) && !sender.hasPermission("thatsaban.admin")) {
            sender.sendMessage("Missing permission: " + permission + ".");
            return true;
        }

        String input = args[1];
        UUID target = resolve(input);
        if (target == null) {
            sender.sendMessage("Player not found. Use a known name or UUID.");
            return true;
        }

        switch (action) {
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
            }
            case "info" -> {
                LinkStore.PlayerSnapshot snapshot = store.snapshot(target);
                if (store.isWriteBlocked()) {
                    sender.sendMessage("WARNING: LinkStore is read-only; this information may be incomplete.");
                }
                hook.users().loadUser(target).whenComplete((user, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error != null || user == null) {
                                sender.sendMessage("Could not load LuckPerms information.");
                                return;
                            }
                            sender.sendMessage("Data export for UUID " + target + ":");
                            sender.sendMessage("Stored name: "
                                    + (snapshot.name() == null ? "none" : snapshot.name()));
                            sender.sendMessage("Linked IP hash entries: " + snapshot.linkedIpCount());
                            sender.sendMessage("Offender entry: "
                                    + (snapshot.offender() ? "present" : "not present"));
                            sender.sendMessage("Layer 2 retention entry: "
                                    + (snapshot.layer2Tracked() ? "present" : "not present"));
                            sender.sendMessage("Plugin record exists: "
                                    + (snapshot.hasStoredData() ? "yes" : "nein"));
                            sender.sendMessage("Effective LuckPerms Layer 2 marker: "
                                    + hook.has(user, hook.markerNode()));
                            for (String node : hook.negatedNodes()) {
                                sender.sendMessage("LuckPerms node " + node + " effective: "
                                        + hook.has(user, node));
                            }
                        }));
            }
            case "unoffend" -> {
                layer2.removeLayer2(target).whenComplete((stillMarked, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error != null) {
                                sender.sendMessage("Could not remove Layer 2.");
                            } else if (Boolean.TRUE.equals(stillMarked)) {
                                sender.sendMessage("Direct Layer 2 nodes were removed, but the marker is "
                                        + "still effective (possibly through a LuckPerms group or context).");
                            } else {
                                sender.sendMessage("Layer 2 and the offender entry were removed: " + target);
                            }
                        }));
            }
            case "forget" -> {
                if (args.length != 3 || !args[2].equalsIgnoreCase("confirm")) {
                    sender.sendMessage("Deleting all plugin data is permanent. Confirm with: "
                            + "/thatsaban forget " + input + " confirm");
                    return true;
                }
                layer2.forgetPlayer(target).whenComplete((removedHashes, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error != null) {
                                sender.sendMessage("Could not clean up LuckPerms data. "
                                        + "The plugin record was left unchanged.");
                            } else {
                                sender.sendMessage("Plugin data deleted; removed IP hash links: "
                                        + removedHashes + ". Plugin Layer 2 nodes were removed.");
                                sender.sendMessage("Shared IP hashes are retained if they are still assigned to other "
                                        + "players. Other LuckPerms data remains untouched.");
                            }
                        }));
            }
            default -> sender.sendMessage("Usage: /thatsaban <info|network|unoffend|forget> <player|UUID> [depth|confirm]");
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
