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
    private final ThatsABan plugin;
    private final AdminSpectatorManager manager;

    AdminSpectatorCommand(ThatsABan plugin, AdminSpectatorManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieser Befehl kann nur von einem Spieler ausgeführt werden.");
            return true;
        }
        if (!player.hasPermission("thatsaban.spectator")) {
            player.sendMessage("Dafür fehlt thatsaban.spectator.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("off")) {
            boolean stopped = manager.stop(player, "manuell beendet");
            if (stopped) {
                player.sendMessage("Admin-Beobachtung beendet; dein vorheriger Zustand wurde wiederhergestellt.");
            } else if (manager.isObserving(player)) {
                player.sendMessage("Rückteleport fehlgeschlagen. Du bleibst verborgen im Spectator-Modus; bitte erneut /adminspec off versuchen oder Staff informieren.");
            } else {
                player.sendMessage("Du befindest dich gerade nicht in einer Admin-Beobachtung.");
            }
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("observe")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                player.sendMessage("Der Zielspieler muss online sein.");
                return true;
            }
            player.sendMessage(manager.observe(player, target)
                    ? "Geschützte Beobachtung gestartet: " + target.getName()
                    : "Beobachtung konnte nicht gestartet werden.");
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("permanent")) {
            if (!player.hasPermission("thatsaban.spectator.permanent")) {
                player.sendMessage("Dafür fehlt thatsaban.spectator.permanent.");
                return true;
            }
            UUID uuid;
            Player online = Bukkit.getPlayerExact(args[2]);
            if (online != null) uuid = online.getUniqueId();
            else {
                try { uuid = UUID.fromString(args[2]); }
                catch (IllegalArgumentException ex) {
                    OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(args[2]);
                    if (offline == null) {
                        player.sendMessage("Spieler unbekannt; verwende einen Online-Spieler oder eine bekannte UUID.");
                        return true;
                    }
                    uuid = offline.getUniqueId();
                }
            }
            if (args[1].equalsIgnoreCase("add")) {
                manager.addPermanent(uuid);
                player.sendMessage("Account dauerhaft auf Spectator gesetzt: " + uuid);
            } else if (args[1].equalsIgnoreCase("remove")) {
                manager.removePermanent(uuid);
                player.sendMessage("Permanente Spectator-Festlegung entfernt: " + uuid);
            } else player.sendMessage("Benutzung: /adminspec permanent <add|remove> <Spieler|UUID>");
            return true;
        }
        player.sendMessage("Benutzung: /adminspec observe <Spieler> | /adminspec off | /adminspec permanent <add|remove> <Spieler|UUID>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("observe", "off", "permanent").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("observe")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(s -> s.startsWith(args[1])).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("permanent")
                && sender.hasPermission("thatsaban.spectator.permanent")) return List.of("add", "remove");
        if (args.length == 3 && args[0].equalsIgnoreCase("permanent")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
