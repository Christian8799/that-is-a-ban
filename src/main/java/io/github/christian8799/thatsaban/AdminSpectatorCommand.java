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

    AdminSpectatorCommand(AdminSpectatorManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("thatsaban.spectator")) {
            sender.sendMessage("Dafür fehlt thatsaban.spectator.");
            return true;
        }

        // Permanent account management is also available to the server console.
        if (args.length == 3 && args[0].equalsIgnoreCase("permanent")) {
            if (!sender.hasPermission("thatsaban.spectator.permanent")) {
                sender.sendMessage("Dafür fehlt thatsaban.spectator.permanent.");
                return true;
            }

            UUID uuid = resolvePlayerId(args[2]);
            if (uuid == null) {
                sender.sendMessage("Spieler unbekannt; verwende einen Online-Spieler, einen bekannten Offline-Spieler oder eine UUID.");
                return true;
            }

            if (args[1].equalsIgnoreCase("add")) {
                sender.sendMessage(manager.addPermanent(uuid)
                        ? "Account dauerhaft auf Spectator gesetzt: " + uuid
                        : "Speichern fehlgeschlagen. Die Änderung wurde zurückgenommen; siehe Serverlog.");
            } else if (args[1].equalsIgnoreCase("remove")) {
                sender.sendMessage(manager.removePermanent(uuid)
                        ? "Permanente Spectator-Festlegung entfernt: " + uuid
                        : "Speichern fehlgeschlagen. Die Änderung wurde zurückgenommen; siehe Serverlog.");
            } else {
                sender.sendMessage("Benutzung: /adminspec permanent <add|remove> <Spieler|UUID>");
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Beobachten und Beenden einer Beobachtung erfordern einen Spieler. Permanente Einträge können über die Konsole verwaltet werden.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("off")) {
            boolean stopped = manager.stop(player, "manuell beendet");
            if (stopped) {
                player.sendMessage("Admin-Beobachtung beendet; dein vorheriger Zustand wurde wiederhergestellt.");
            } else if (manager.isObserving(player)) {
                player.sendMessage("Wiederherstellung fehlgeschlagen. Du bleibst verborgen im Spectator-Modus; bitte /adminspec off erneut versuchen oder Staff informieren.");
            } else {
                player.sendMessage("Du befindest dich gerade nicht in einer Admin-Beobachtung.");
            }
            return true;
        }

        if ((args.length == 2 || args.length == 3) && args[0].equalsIgnoreCase("observe")) {
            boolean vanishMode = args.length == 3 && args[2].equalsIgnoreCase("vanish");
            if (args.length == 3 && !vanishMode) {
                player.sendMessage("Zusatzargument unbekannt. Verwende optional 'vanish'.");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                player.sendMessage("Der Zielspieler muss online sein.");
                return true;
            }
            player.sendMessage(manager.observe(player, target, vanishMode)
                    ? "Geschützte Beobachtung gestartet: " + target.getName()
                        + (vanishMode ? " (mit SuperVanish)." : " (ohne SuperVanish).")
                    : "Beobachtung konnte nicht gestartet werden. Prüfe Serverlog und Wiederherstellungsstatus.");
            return true;
        }

        player.sendMessage("Benutzung: /adminspec observe <Spieler> [vanish] | /adminspec off | /adminspec permanent <add|remove> <Spieler|UUID>");
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
            return List.of("observe", "off", "permanent").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("observe")
                && sender instanceof Player) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
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
            return List.of("add", "remove");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("permanent")
                && sender.hasPermission("thatsaban.spectator.permanent")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
