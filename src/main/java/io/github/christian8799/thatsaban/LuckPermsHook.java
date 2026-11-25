package io.github.christian8799.thatsaban;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class LuckPermsHook {

    private final ThatsABan plugin;
    private final LuckPerms luckPerms;

    LuckPermsHook(ThatsABan plugin, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
    }

    UserManager users() {
        return luckPerms.getUserManager();
    }

    String markerNode() {
        return plugin.getConfig().getString("layer2.marker-permission", "thatsaban.layer2");
    }

    String exemptNode() {
        return plugin.getConfig().getString("layer2.exempt-permission", "thatsaban.exempt");
    }

    List<String> negatedNodes() {
        return plugin.getConfig().getStringList("layer2.negated-permissions");
    }

    boolean has(User user, String node) {
        return user.getCachedData().getPermissionData().checkPermission(node).asBoolean();
    }

    /** Nur aus Async-Threads aufrufen. */
    User userNow(UUID uuid) {
        User cached = users().getUser(uuid);
        return cached != null ? cached : users().loadUser(uuid).join();
    }

    boolean applyLayer2(User user) {
        boolean changed = setExact(user, markerNode(), true);
        for (String node : negatedNodes()) {
            changed |= setExact(user, node, false);
        }
        return changed;
    }

    boolean clearLayer2(User user) {
        boolean changed = clearExact(user, markerNode());
        for (String node : negatedNodes()) {
            changed |= clearExact(user, node);
        }
        return changed;
    }

    CompletableFuture<Void> applyLayer2Async(UUID uuid) {
        return users().loadUser(uuid).thenCompose(user -> {
            if (!applyLayer2(user)) {
                return CompletableFuture.completedFuture(null);
            }
            return save(user);
        });
    }

    CompletableFuture<Void> clearLayer2Async(UUID uuid) {
        return users().loadUser(uuid).thenCompose(user -> {
            if (!clearLayer2(user)) {
                return CompletableFuture.completedFuture(null);
            }
            return save(user);
        });
    }

    CompletableFuture<Void> save(User user) {
        return users().saveUser(user);
    }

    private boolean setExact(User user, String key, boolean value) {
        boolean present = user.getNodes().stream().anyMatch(
                n -> n.getKey().equals(key) && n.getValue() == value && n.getContexts().isEmpty());
        if (present) {
            return false;
        }
        user.data().clear(n -> n.getKey().equals(key) && n.getContexts().isEmpty());
        user.data().add(Node.builder(key).value(value).build());
        return true;
    }

    private boolean clearExact(User user, String key) {
        boolean present = user.getNodes().stream().anyMatch(
                n -> n.getKey().equals(key) && n.getContexts().isEmpty());
        if (present) {
            user.data().clear(n -> n.getKey().equals(key) && n.getContexts().isEmpty());
        }
        return present;
    }
}
