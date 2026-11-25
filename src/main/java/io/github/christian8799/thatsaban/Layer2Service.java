package io.github.christian8799.thatsaban;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class Layer2Service {

    enum Status { DONE, ALREADY, EXEMPT }

    record OffendResult(Status status, List<String> alts) {}

    private final ThatsABan plugin;
    private final LinkStore store;
    private final LuckPermsHook hook;

    Layer2Service(ThatsABan plugin, LinkStore store, LuckPermsHook hook) {
        this.plugin = plugin;
        this.store = store;
        this.hook = hook;
    }

    CompletableFuture<OffendResult> offend(UUID uuid) {
        return hook.users().loadUser(uuid).<OffendResult>thenCompose(user -> {
            if (hook.has(user, hook.exemptNode())) {
                return CompletableFuture.completedFuture(new OffendResult(Status.EXEMPT, List.of()));
            }
            boolean already = hook.has(user, hook.markerNode());
            Set<UUID> others = store.markOffender(uuid);
            return hook.applyLayer2Async(uuid)
                    .thenCompose(v -> flagAlts(others))
                    .thenApply(alts -> new OffendResult(already ? Status.ALREADY : Status.DONE, alts));
        });
    }

    CompletableFuture<Void> removeLayer2(UUID uuid) {
        return hook.clearLayer2Async(uuid).thenRun(() -> store.unmarkOffender(uuid));
    }

    CompletableFuture<Integer> forgetPlayer(UUID uuid) {
        return hook.clearLayer2Async(uuid).thenApply(v -> store.forgetPlayer(uuid));
    }

    /** Entfernt abgelaufene Offender-Marker auch aus LuckPerms, einschließlich Offline-Usern. */
    void cleanupExpired() {
        for (UUID uuid : store.drainExpiredOffenders()) {
            if (store.isOffender(uuid)) {
                continue;
            }
            hook.clearLayer2Async(uuid).whenComplete((ignored, error) -> {
                if (error != null && !store.isOffender(uuid)) {
                    store.requeueExpiredOffender(uuid);
                    plugin.getLogger().warning("Ein abgelaufener Layer-2-Marker konnte nicht aus LuckPerms entfernt werden.");
                }
            });
        }
    }

    /** Läuft beim Async-Pre-Login für jeden Java-Spieler. */
    void handleLogin(UUID uuid, String name, InetAddress address) {
        User user = hook.userNow(uuid);

        if (hook.has(user, hook.exemptNode())) {
            return;
        }

        boolean offender = store.isOffender(uuid);
        if (offender) {
            store.renewOffender(uuid);
        }

        boolean marked = hook.has(user, hook.markerNode());
        if (marked && hook.applyLayer2(user)) {
            hook.save(user);
        }

        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("alt-detection.enabled", true) || ignored(address)) {
            return;
        }

        String hash = store.hash(address);
        store.record(uuid, name, hash);

        if (offender) {
            store.flagIp(hash);
            return;
        }
        if (marked) {
            return;
        }
        if (store.isLayer2Ip(hash)) {
            if (cfg.getBoolean("alt-detection.auto-apply", false)) {
                if (hook.applyLayer2(user)) {
                    hook.save(user);
                }
                notifyStaff("alt-auto-applied-staff", name);
            } else {
                notifyStaff("alt-suggestion-staff", name);
            }
        }
    }

    private CompletableFuture<List<String>> flagAlts(Set<UUID> others) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("alt-detection.enabled", true)
                || !cfg.getBoolean("alt-detection.retroactive", false)
                || others.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        boolean autoApply = cfg.getBoolean("alt-detection.auto-apply", false);
        List<CompletableFuture<String>> jobs = new ArrayList<>();
        for (UUID other : others) {
            jobs.add(hook.users().loadUser(other).<String>thenCompose(user -> {
                if (hook.has(user, hook.exemptNode())) {
                    return CompletableFuture.completedFuture((String) null);
                }
                String name = store.nameOf(other);
                if (!autoApply) {
                    notifyStaff("alt-suggestion-staff", name != null ? name : other.toString());
                    return CompletableFuture.completedFuture((String) null);
                }
                if (hook.has(user, hook.markerNode())) {
                    return hook.applyLayer2Async(other).thenApply(v -> (String) null);
                }
                return hook.applyLayer2Async(other).thenApply(v ->
                        name != null ? name : other.toString());
            }));
        }
        return CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0]))
                .thenApply(v -> jobs.stream().map(CompletableFuture::join)
                        .filter(Objects::nonNull).toList());
    }

    private void notifyStaff(String messageKey, String player) {
        if (player == null) {
            player = "unbekannt";
        }
        var message = plugin.messages().get(messageKey, Placeholder.unparsed("player", player));
        Bukkit.getScheduler().runTask(plugin,
                () -> Bukkit.broadcast(message, "thatsaban.notify"));
    }

    private boolean ignored(InetAddress address) {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getBoolean("alt-detection.ignore-private-addresses", true)
                && (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress())) {
            return true;
        }
        return cfg.getStringList("alt-detection.ignored-ips").contains(address.getHostAddress());
    }
}
