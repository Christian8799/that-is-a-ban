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
        if (store.isWriteBlocked()) {
            return CompletableFuture.failedFuture(new IllegalStateException("LinkStore write-blocked"));
        }
        return hook.users().loadUser(uuid).<OffendResult>thenCompose(user -> {
            if (hook.has(user, hook.exemptNode())) {
                return CompletableFuture.completedFuture(new OffendResult(Status.EXEMPT, List.of()));
            }
            boolean already = hook.has(user, hook.markerNode());
            return hook.applyLayer2Async(uuid)
                    .thenCompose(v -> flagAlts(uuid, store.markOffender(uuid)))
                    .thenApply(alts -> new OffendResult(already ? Status.ALREADY : Status.DONE, alts));
        });
    }

    CompletableFuture<Boolean> removeLayer2(UUID uuid) {
        if (store.isWriteBlocked()) {
            return CompletableFuture.failedFuture(new IllegalStateException("LinkStore write-blocked"));
        }
        Set<UUID> affected = store.autoOffenderGroup(uuid);
        CompletableFuture<?>[] removals = affected.stream()
                .map(hook::clearLayer2Async)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(removals)
                .thenApply(ignored -> {
                    store.unmarkOffenderGroup(uuid);
                    return null;
                })
                .thenCompose(ignored -> hook.users().loadUser(uuid))
                .thenApply(user -> hook.has(user, hook.markerNode()));
    }

    CompletableFuture<Integer> forgetPlayer(UUID uuid) {
        if (store.isWriteBlocked()) {
            return CompletableFuture.failedFuture(new IllegalStateException("LinkStore write-blocked"));
        }
        return hook.clearLayer2Async(uuid).thenApply(v -> store.forgetPlayer(uuid));
    }

    /** Also removes expired offender markers from LuckPerms, including offline users. */
    void cleanupExpired() {
        if (store.isWriteBlocked()) {
            return;
        }
        for (UUID uuid : store.pendingExpiredOffenders()) {
            if (store.hasLayer2Tracking(uuid)) {
                store.completeExpiredOffender(uuid);
                continue;
            }
            hook.users().loadUser(uuid).thenCompose(user -> {
                if (store.hasLayer2Tracking(uuid) || !hook.clearLayer2(user)) {
                    return CompletableFuture.completedFuture(null);
                }
                return hook.save(user);
            }).whenComplete((ignored, error) -> {
                if (error == null) {
                    store.completeExpiredOffender(uuid);
                } else if (!store.hasLayer2Tracking(uuid)) {
                    plugin.getLogger().warning("An expired Layer 2 marker could not be removed from LuckPerms; the removal will be retried.");
                }
            });
        }
    }

    /**
     * Records a Bedrock account for the account graph without applying Layer 2 logic.
     * Bedrock players remain immune to /offend; this only enables transparent network evidence.
     */
    void recordNetworkLogin(UUID uuid, String name, InetAddress address) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("alt-detection.enabled", false) || store.isWriteBlocked() || ignored(address)) {
            return;
        }
        User user = hook.userNow(uuid);
        if (hook.has(user, hook.exemptNode())) {
            return;
        }
        store.record(uuid, name, store.hash(address));
    }

    /** Läuft beim Async-Pre-Login für jeden Java-Spieler. */
    void handleLogin(UUID uuid, String name, InetAddress address) {
        User user = hook.userNow(uuid);

        if (hook.has(user, hook.exemptNode())) {
            return;
        }

        boolean offender = store.isOffender(uuid);
        boolean marked = hook.has(user, hook.markerNode());

        // Enforce existing LuckPerms state even when the local store is read-only.
        // A stored offender must also regain its Layer-2 nodes if they were removed
        // or a previous LuckPerms save failed.
        if ((offender || marked) && hook.applyLayer2(user)) {
            hook.save(user);
        }

        // Store writes and IP-network processing are unavailable in read-only mode,
        // but that must never disable enforcement of an existing Layer-2 marker.
        if (store.isWriteBlocked()) {
            return;
        }

        if (offender) {
            store.renewOffender(uuid);
        } else if (marked) {
            if (store.hasLayer2Tracking(uuid)) {
                store.renewLayer2(uuid);
            } else {
                store.trackLayer2(uuid);
            }
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
                store.trackLayer2(uuid);
                notifyStaff("alt-auto-applied-staff", name);
            } else {
                notifyStaff("alt-suggestion-staff", name);
            }
        }
    }

    private CompletableFuture<List<String>> flagAlts(UUID main, Set<UUID> others) {
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
                    return hook.applyLayer2Async(other).thenApply(v -> {
                        store.trackLayer2(other);
                        store.linkAutoOffender(main, other);
                        return (String) null;
                    });
                }
                return hook.applyLayer2Async(other).thenApply(v -> {
                    store.trackLayer2(other);
                    store.linkAutoOffender(main, other);
                    return name != null ? name : other.toString();
                });
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
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().stream()
                .filter(recipient -> recipient.hasPermission("thatsaban.notify"))
                .forEach(recipient -> recipient.sendMessage(message)));
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
