package io.github.christian8799.thatsaban;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class AdminSpectatorCommand implements CommandExecutor, TabCompleter {
    private final AdminSpectatorManager manager;
    private final AdminSpectatorMenu menu;

    AdminSpectatorCommand(AdminSpectatorManager manager, AdminSpectatorMenu menu) {
        this.manager = manager;
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("thatsaban.spectator")) {
            sender.sendMessage("Missing permission: thatsaban.spectator.");
            return true;
        }

        // Permanent account management is also available to the server console.
        if (args.length == 3 && args[0].equalsIgnoreCase("permanent")) {
            if (!sender.hasPermission("thatsaban.spectator.permanent")) {
                sender.sendMessage("Missing permission: thatsaban.spectator.permanent.");
                return true;
            }

            UUID uuid = resolvePlayerId(args[2]);
            if (uuid == null) {
                sender.sendMessage("Unknown player. Use an online player, a known offline player, or a UUID.");
                return true;
            }

            if (args[1].equalsIgnoreCase("add")) {
                sender.sendMessage(manager.addPermanent(uuid)
                        ? "Account permanently set to spectator: " + uuid
                        : "Saving failed. The change was rolled back; check the server log.");
            } else if (args[1].equalsIgnoreCase("remove")) {
                sender.sendMessage(manager.removePermanent(uuid)
                        ? "Permanent spectator setting removed: " + uuid
                        : "Saving failed. The change was rolled back; check the server log.");
            } else {
                sender.sendMessage("Usage: /adminspec permanent <add|remove> <player|UUID>");
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Starting and stopping an observation requires a player. Permanent entries can be managed from the console.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("menu")) {
            menu.openMain(player);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("offend")) {
            if (!player.hasPermission("thatsaban.offend")) {
                player.sendMessage("Missing permission: thatsaban.offend.");
                return true;
            }
            Player target = manager.currentTarget(player);
            if (target == null) {
                player.sendMessage("You are not currently spectating a player.");
                return true;
            }
            Bukkit.dispatchCommand(player, "offend " + target.getName());
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                player.sendMessage("The target player must be online.");
                return true;
            }
            if (!manager.switchTarget(player, target)) {
                player.sendMessage("Could not switch spectator target.");
            } else {
                player.sendMessage("Now spectating: " + target.getName());
            }
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("off")) {
            boolean stopped = manager.stop(player, "manually stopped");
            if (stopped) {
                player.sendMessage("Admin observation ended; your previous state has been restored.");
            } else if (manager.isObserving(player)) {
                player.sendMessage("Restoration failed. You remain hidden in spectator mode. Try /adminspec off again or contact staff.");
            } else {
                player.sendMessage("You are not currently in an admin observation.");
            }
            return true;
        }

        if ((args.length == 2 || args.length == 3) && args[0].equalsIgnoreCase("observe")) {
            boolean vanishMode = args.length == 3 && args[2].equalsIgnoreCase("vanish");
            if (args.length == 3 && !vanishMode) {
                player.sendMessage("Unknown extra argument. The optional argument is 'vanish'.");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                player.sendMessage("The target player must be online.");
                return true;
            }
            boolean started = manager.observe(player, target, vanishMode);
            player.sendMessage(started
                    ? "Protected observation started: " + target.getName()
                        + (vanishMode ? " (with SuperVanish)." : " (without SuperVanish).")
                    : "Could not start observation. Check the server log and restoration status.");
            if (started) menu.openMain(player);
            return true;
        }

        player.sendMessage("Usage: /adminspec observe <player> [vanish] | /adminspec menu | /adminspec tp <player> | /adminspec offend | /adminspec off | /adminspec permanent <add|remove> <player|UUID>");
        return true;
    }

    private UUID resolvePlayerId(String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) return online.getUniqueId();

        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
            return cached == null ? null : cached.getUniqueId();
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("thatsaban.spectator")) return List.of();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            java.util.List<String> options = new java.util.ArrayList<>();
            options.add("observe");
            if (sender instanceof Player player) {
                options.add("menu");
                if (manager.isObserving(player)) {
                    options.add("tp");
                    options.add("off");
                    if (sender.hasPermission("thatsaban.offend")) options.add("offend");
                }
            }
            if (sender.hasPermission("thatsaban.spectator.permanent")) {
                options.add("permanent");
            }
            return options.stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("observe")
                || args[0].equalsIgnoreCase("tp")) && sender instanceof Player player) {
            if (args[0].equalsIgnoreCase("tp") && !manager.isObserving(player)) return List.of();
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .filter(target -> !target.equals(player))
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("observe")
                && sender instanceof Player && "vanish".startsWith(args[2].toLowerCase(Locale.ROOT))) {
            return List.of("vanish");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("permanent")
                && sender.hasPermission("thatsaban.spectator.permanent")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("add", "remove").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("permanent")
                && sender.hasPermission("thatsaban.spectator.permanent")) {
            boolean permanentOnly = args[1].equalsIgnoreCase("remove");
            if (!permanentOnly && !args[1].equalsIgnoreCase("add")) return List.of();
            return manager.permanentPlayerNames(args[2], permanentOnly);
        }
        return List.of();
    }
}