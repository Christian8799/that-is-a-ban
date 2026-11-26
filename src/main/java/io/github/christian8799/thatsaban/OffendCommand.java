package io.github.christian8799.thatsaban;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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

final class OffendCommand implements CommandExecutor, TabCompleter {

    private final ThatsABan plugin;
    private final Layer2Service layer2;
    private final BedrockDetector bedrock;
    private final LinkStore store;

    OffendCommand(ThatsABan plugin, Layer2Service layer2, BedrockDetector bedrock, LinkStore store) {
        this.plugin = plugin;
        this.layer2 = layer2;
        this.bedrock = bedrock;
        this.store = store;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages msg = plugin.messages();
        if (args.length != 1) {
            sender.sendMessage(msg.get("usage"));
            return true;
        }

        String input = args[0];
        UUID resolvedId;
        String resolvedName;

        Player online = Bukkit.getPlayerExact(input);
        UUID known = store.uuidByName(input);
        if (online != null) {
            resolvedId = online.getUniqueId();
            resolvedName = online.getName();
        } else {
            UUID suppliedUuid = null;
            try {
                suppliedUuid = UUID.fromString(input);
            } catch (IllegalArgumentException ignored) {
                // Continue with the cached name lookups below.
            }

            if (suppliedUuid != null) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(suppliedUuid);
                resolvedId = suppliedUuid;
                resolvedName = offline.getName() != null ? offline.getName() : suppliedUuid.toString();
            } else if (known != null) {
                resolvedId = known;
                resolvedName = store.nameOf(known);
            } else {
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
                if (cached == null) {
                    sender.sendMessage(msg.get("player-not-found", Placeholder.unparsed("player", input)));
                    return true;
                }
                resolvedId = cached.getUniqueId();
                resolvedName = cached.getName() != null ? cached.getName() : input;
            }
        }

        final UUID targetId = resolvedId;
        final String targetName = resolvedName;
        var nameTag = Placeholder.unparsed("player", targetName);

        if (bedrock.isBedrock(targetId, targetName)) {
            sender.sendMessage(msg.get("bedrock-immune", nameTag));
            return true;
        }

        layer2.offend(targetId).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || result == null) {
                        plugin.getLogger().warning("/offend konnte nicht abgeschlossen werden.");
                        sender.sendMessage(msg.get("error"));
                        return;
                    }
                    switch (result.status()) {
                        case EXEMPT -> sender.sendMessage(msg.get("exempt", nameTag));
                        case ALREADY -> sender.sendMessage(msg.get("already", nameTag));
                        case DONE -> {
                            sender.sendMessage(msg.get("done", nameTag));
                            if (plugin.getConfig().getBoolean("privacy.audit-logging", false)) {
                                String actor = sender instanceof Player player
                                        ? player.getUniqueId().toString() : "CONSOLE";
                                plugin.getLogger().info("Layer-2-Zuweisung; actor=" + actor
                                        + "; target=" + targetId);
                            }
                            if (plugin.getConfig().getBoolean("offend.kick-online-target", false)) {
                                Player target = Bukkit.getPlayer(targetId);
                                if (target != null) {
                                    target.kick(msg.plain("kick-relogin"));
                                }
                            }
                        }
                    }
                    if (!result.alts().isEmpty()
                            && sender.hasPermission("thatsaban.view-linked-accounts")) {
                        sender.sendMessage(msg.get("done-alts",
                                Placeholder.unparsed("alts", String.join(", ", result.alts()))));
                    }
                }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .filter(p -> !bedrock.isBedrock(p.getUniqueId(), p.getName()))
                .map(Player::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
