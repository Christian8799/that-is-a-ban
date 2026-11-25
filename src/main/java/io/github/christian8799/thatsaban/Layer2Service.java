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

    /** /offend: Spieler dauerhaft in Layer 2 versetzen (+ seine IP-Alts). */
    CompletableFuture<OffendResult> offend(UUID uuid) {
        return hook.users().loadUser(uuid).<OffendResult>thenCompose(user -> {
            if (hook.has(user, hook.exemptNode())) {
                return CompletableFuture.completedFuture(new OffendResult(Status.EXEMPT, List.of()));
            }
            boolean already = hook.has(user, hook.markerNode());
            return hook.applyLayer2Async(uuid)
                    .thenCompose(v -> flagAlts(uuid))
                    .thenApply(alts -> new OffendResult(already ? Status.ALREADY : Status.DONE, alts));
        });
    }

    /** Wird beim Login (Async) fuer jeden Java-Spieler aufgerufen. */
    void handleLogin(UUID uuid, String name, InetAddress address) {
        User user = hook.userNow(uuid);

        // Exempt muss vor jeder Marker-Reparatur und IP-Verarbeitung Vorrang haben.
        // Ausgenommene Accounts duerfen auch keine IP-Kopplung fuer andere Accounts ausloesen.
        if (hook.has(user, hook.exemptNode())) {
            return;
        }

        // Vorhandene Marker koennen manuell gesetzt worden sein; alle Layer-2-Nodes angleichen.
        boolean marked = hook.has(user, hook.markerNode());
        if (marked && hook.applyLayer2(user)) {
            hook.save(user);
        }

        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("alt-detection.enabled", true)) {
            return;
        }

        boolean offender = store.isOffender(uuid);
        if (offender) {
            store.renewOffender(uuid); // Auch bei ignorierten Proxy-/IP-Adressen Login-Aktivität erfassen
        }
        if (ignored(address)) {
            return;
        }

        String hash = store.hash(address);
        store.record(uuid, name, hash);

        if (offender) {
            store.flagIp(hash); // neue IP eines Ausloesers ebenfalls markieren
            return;
        }
        if (marked) {
            return;
        }
        if (store.isLayer2Ip(hash)) {
            hook.applyLayer2(user);
            hook.save(user);
            plugin.getLogger().info(name + " wurde per IP-Kopplung in Layer 2 versetzt.");
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcast(
                    plugin.messages().get("alt-flagged-staff", Placeholder.unparsed("player", name)),
                    "thatsaban.notify"));
        }
    }

    private CompletableFuture<List<String>> flagAlts(UUID uuid) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("alt-detection.enabled", true)) {
            return CompletableFuture.completedFuture(List.of());
        }
        Set<UUID> others = store.markOffender(uuid);
        if (!cfg.getBoolean("alt-detection.retroactive", true)) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<CompletableFuture<String>> jobs = new ArrayList<>();
        for (UUID other : others) {
            jobs.add(hook.users().loadUser(other).<String>thenCompose(user -> {
                if (hook.has(user, hook.exemptNode())) {
                    return CompletableFuture.completedFuture((String) null);
                }
                String name = store.nameOf(other);
                if (hook.has(user, hook.markerNode())) {
                    // Bereits markierte Accounts trotzdem reparieren: fehlende Negationen nachziehen.
                    return hook.applyLayer2Async(other).thenApply(v -> (String) null);
                }
                return hook.applyLayer2Async(other).thenApply(v ->
                        name != null ? name : other.toString());
            }));
        }
        return CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0]))
                .thenApply(v -> jobs.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList());
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
