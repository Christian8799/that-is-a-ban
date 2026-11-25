package io.github.christian8799.thatsaban;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.ChunkCoordIntPair;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedLevelChunkData;
import com.comphenix.protocol.wrappers.WrappedPositionMoveRotation;
import com.comphenix.protocol.wrappers.nbt.NbtBase;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class AdminSpectatorManager implements Listener {
    private final JavaPlugin plugin;
    private final ProtocolManager protocol;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> restoring = ConcurrentHashMap.newKeySet();
    private final Set<UUID> permanent = new HashSet<>();
    private final Map<UUID, PermanentSnapshot> permanentSnapshots = new java.util.HashMap<>();
    private final Map<UUID, PermanentSnapshot> pendingPermanentSnapshots = new java.util.HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final VanishIntegration vanish;
    private boolean vanishFailureLogged;
    private boolean vanishUnavailable;
    private boolean packetListenerRegistered;

    private record PermanentSnapshot(GameMode gameMode, boolean allowFlight, boolean flying,
                                     boolean vanishStateKnown, boolean wasVanished) {}
    private record Snapshot(Location location, GameMode gameMode, boolean allowFlight,
                            boolean flying, boolean invulnerable, float flySpeed, float walkSpeed,
                            int viewDistance, int sendViewDistance, UUID spectatorTarget) {}
    private static final class Session {
        private volatile UUID target;
        private final Snapshot snapshot;
        private final int offsetX;
        private final int offsetZ;
        private final boolean vanishMode;
        private final boolean wasVanished;

        private Session(UUID target, Snapshot snapshot, int offsetX, int offsetZ,
                        boolean vanishMode, boolean wasVanished) {
            this.target = target;
            this.snapshot = snapshot;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
            this.vanishMode = vanishMode;
            this.wasVanished = wasVanished;
        }

        UUID target() { return target; }
        void setTarget(UUID target) { this.target = target; }
        Snapshot snapshot() { return snapshot; }
        boolean vanishMode() { return vanishMode; }
        boolean wasVanished() { return wasVanished; }
        int offsetX() { return offsetX; }
        int offsetZ() { return offsetZ; }
    }

    AdminSpectatorManager(JavaPlugin plugin, VanishIntegration vanish) {
        this.plugin = plugin;
        this.vanish = vanish;
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            throw new IllegalStateException("ProtocolLib fehlt; Admin-Spectator wird nicht aktiviert.");
        }
        this.protocol = ProtocolLibrary.getProtocolManager();
        for (String raw : plugin.getConfig().getStringList("admin-spectator.permanent-players")) {
            try { permanent.add(UUID.fromString(raw)); }
            catch (IllegalArgumentException ex) { plugin.getLogger().warning("Ungültige UUID in admin-spectator.permanent-players: " + raw); }
        }
        loadPermanentSnapshots("admin-spectator.permanent-snapshots", permanentSnapshots);
        loadPermanentSnapshots("admin-spectator.pending-permanent-restores", pendingPermanentSnapshots);
        try {
            // Register the external packet hook first. If ProtocolLib is incompatible,
            // no Bukkit event listener or repeating task is left partially installed.
            registerPacketTranslation();
            Bukkit.getPluginManager().registerEvents(this, plugin);
            for (Player player : Bukkit.getOnlinePlayers()) {
                boolean restoredSession = restorePending(player);
                UUID playerId = player.getUniqueId();
                if (!hasPendingRestore(playerId)) {
                    if (!permanent.contains(playerId)) {
                        restorePendingPermanent(player);
                    } else if (!restoredSession) {
                        enforcePermanent(player);
                    }
                }
            }
            // Re-enforce against plugins that bypass the Bukkit game-mode event.
            Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) enforcePermanent(player);
            }, 20L, 20L);
        } catch (LinkageError | RuntimeException ex) {
            org.bukkit.event.HandlerList.unregisterAll(this);
            try {
                protocol.removePacketListeners(plugin);
            } catch (RuntimeException cleanupError) {
                plugin.getLogger().warning("ProtocolLib-Listener-Cleanup nach fehlgeschlagener Initialisierung nicht möglich: "
                        + cleanupError.getClass().getSimpleName());
            }
            throw ex;
        }
    }

    boolean isObserving(Player observer) {
        return sessions.containsKey(observer.getUniqueId())
                || hasPendingRestore(observer.getUniqueId());
    }

    boolean observe(Player observer, Player target, boolean vanishMode) {
        if (observer.equals(target)) return false;
        if (vanishMode && !vanishAvailable()) {
            observer.sendMessage("Der Vanish-Modus benötigt das installierte und kompatible SuperVanish-Plugin.");
            return false;
        }

        UUID observerId = observer.getUniqueId();
        Session active = sessions.get(observerId);
        if (active != null) {
            if (active.vanishMode() != vanishMode) {
                observer.sendMessage("Der Beobachtungsmodus kann während einer laufenden Sitzung nicht gewechselt werden. "
                        + "Beende sie zuerst mit /adminspec off.");
                return false;
            }
            return retarget(observer, target, active);
        }
        if (hasPendingRestore(observerId) && !restorePending(observer)) return false;

        Snapshot snapshot = new Snapshot(observer.getLocation().clone(), observer.getGameMode(),
                observer.getAllowFlight(), observer.isFlying(), observer.isInvulnerable(),
                observer.getFlySpeed(), observer.getWalkSpeed(),
                observer.getViewDistance(), observer.getSendViewDistance(),
                observer.getSpectatorTarget() == null ? null : observer.getSpectatorTarget().getUniqueId());
        // Chunk-aligned offsets preserve local block coordinates while changing absolute client coordinates.
        int offsetX = createOffset(target.getLocation().getX());
        int offsetZ = createOffset(target.getLocation().getZ());
        boolean wasVanished = false;
        if (vanishMode) {
            try {
                wasVanished = vanish.isVanished(observer);
            } catch (LinkageError | RuntimeException ex) {
                markVanishUnavailable(ex);
                observer.sendMessage("SuperVanish konnte den bisherigen Vanish-Status nicht sicher lesen. "
                        + "Die Beobachtung wurde nicht gestartet.");
                return false;
            }
        }
        // Persist the pre-observation state before changing the player's position or mode.
        // This also allows recovery after a process crash or plugin/server restart.
        try {
            savePendingRestore(observerId, snapshot, vanishMode, wasVanished);
            Session session = new Session(target.getUniqueId(), snapshot, offsetX, offsetZ,
                    vanishMode, wasVanished);
            sessions.put(observerId, session);
            // Apply SuperVanish before changing mode or moving so no initial
            // spectator transition can expose the moderator.
            if (vanishMode) vanish.setVanished(observer, true);
            else hideObserverFromAll(observer);
            observer.setGameMode(GameMode.SPECTATOR);
            observer.setAllowFlight(true);
            observer.setFlying(true);
            observer.setInvulnerable(true);
            observer.setViewDistance(4);
            observer.setSendViewDistance(4);
            if (!observer.teleport(target.getLocation())) {
                stop(observer, "Teleport zum Zielspieler fehlgeschlagen");
                return false;
            }
            observer.setSpectatorTarget(target);
            audit("START", observerId, target.getUniqueId());
            return true;
        } catch (LinkageError | RuntimeException ex) {
            plugin.getLogger().severe("Admin-Spectator konnte nicht gestartet werden: " + ex.getMessage());
            if (sessions.containsKey(observerId)) {
                try {
                    stop(observer, "Startfehler");
                } catch (LinkageError | RuntimeException restoreError) {
                    plugin.getLogger().severe("Wiederherstellung nach Startfehler fehlgeschlagen: " + restoreError.getMessage());
                }
            }
            return false;
        }
    }

    private boolean retarget(Player observer, Player target, Session session) {
        UUID observerId = observer.getUniqueId();
        synchronized (session) {
            if (sessions.get(observerId) != session) return false;

            // Keep one coordinate space for the entire observation. Changing offsets
            // mid-session races in-flight movement packets and can desynchronize client/server.
            double clientX = target.getLocation().getX() - session.offsetX();
            double clientZ = target.getLocation().getZ() - session.offsetZ();
            if (Math.abs(clientX) > 29_000_000.0 || Math.abs(clientZ) > 29_000_000.0) {
                audit("RETARGET_OUT_OF_RANGE", observerId, target.getUniqueId());
                return false;
            }

            boolean teleportAttempted = false;
            try {
                teleportAttempted = true;
                if (!observer.teleport(target.getLocation())) {
                    Player previousTarget = Bukkit.getPlayer(session.target());
                    if (previousTarget != null) {
                        observer.teleport(previousTarget.getLocation());
                        observer.setSpectatorTarget(previousTarget);
                    } else {
                        stop(observer, "Vorheriger Beobachtungs-Spieler ist nicht mehr online");
                    }
                    audit("RETARGET_FAILED", observerId, target.getUniqueId());
                    return false;
                }

                session.setTarget(target.getUniqueId());
                observer.setSpectatorTarget(target);
                audit("RETARGET", observerId, target.getUniqueId());
                return true;
            } catch (LinkageError | RuntimeException ex) {
                // A teleport may have emitted a packet before throwing. Keep the same
                // offset, and conservatively track the requested target for later cleanup.
                if (teleportAttempted) session.setTarget(target.getUniqueId());
                plugin.getLogger().severe("Admin-Spectator-Zielwechsel fehlgeschlagen für "
                        + observerId + ": " + ex.getMessage());
                keepObserverHidden(observer);
                audit("RETARGET_FAILED", observerId, target.getUniqueId());
                return false;
            }
        }
    }

    boolean stop(Player observer, String reason) {
        UUID observerId = observer.getUniqueId();
        Session session = sessions.get(observerId);
        if (session == null) return restorePending(observer);

        synchronized (session) {
            // Publish the transition before removing the session so an asynchronous
            // inbound packet cannot slip through unshifted in the tiny hand-off window.
            restoring.add(observerId);
            if (!sessions.remove(observerId, session)) {
                restoring.remove(observerId);
                return false;
            }
            boolean returnTeleportSucceeded = false;
            try {
                observer.setSpectatorTarget(null);
                if (!observer.teleport(session.snapshot().location())) {
                    sessions.put(observerId, session);
                    keepObserverHidden(observer);
                    plugin.getLogger().severe("Admin-Spectator-Rückteleport fehlgeschlagen; Spieler bleibt verborgen: " + observerId);
                    audit("STOP_FAILED (" + reason + ")", observerId, session.target());
                    return false;
                }
                returnTeleportSucceeded = true;

                restoreSnapshot(observer, session.snapshot());
                restoreVanishState(observer, session.vanishMode, session.wasVanished);
                finishObserverVisibility(observer, session.vanishMode, session.wasVanished);
                enforcePermanent(observer);
                clearPendingRestore(observerId);
                audit("STOP (" + reason + ")", observerId, session.target());
                return true;
            } catch (LinkageError | RuntimeException ex) {
                // Once the unshifted return teleport succeeded, never reactivate the
                // old coordinate offset. Leave the persisted snapshot as a pending
                // restore so /adminspec off or the next login can retry restoration.
                if (!returnTeleportSucceeded) {
                    sessions.putIfAbsent(observerId, session);
                }
                keepObserverHidden(observer);
                plugin.getLogger().severe("Admin-Spectator-Wiederherstellung fehlgeschlagen für "
                        + observerId + ": " + ex.getMessage());
                audit("STOP_FAILED (" + reason + ")", observerId, session.target());
                return false;
            } finally {
                restoring.remove(observerId);
            }
        }
    }

    boolean addPermanent(UUID uuid) {
        if (permanent.contains(uuid)) return true;
        Player player = Bukkit.getPlayer(uuid);
        PermanentSnapshot previousSnapshot = permanentSnapshots.get(uuid);
        PermanentSnapshot pendingSnapshot = pendingPermanentSnapshots.remove(uuid);
        permanent.add(uuid);
        if (previousSnapshot == null && pendingSnapshot != null) {
            permanentSnapshots.put(uuid, pendingSnapshot);
        } else if (player != null && previousSnapshot == null) {
            permanentSnapshots.put(uuid, capturePermanentSnapshot(player));
        }
        if (!savePermanentData()) {
            permanent.remove(uuid);
            if (previousSnapshot == null) permanentSnapshots.remove(uuid);
            else permanentSnapshots.put(uuid, previousSnapshot);
            if (pendingSnapshot != null) pendingPermanentSnapshots.put(uuid, pendingSnapshot);
            return false;
        }
        if (player != null) enforcePermanent(player);
        audit("PERMANENT_ADD", uuid, uuid);
        return true;
    }

    boolean removePermanent(UUID uuid) {
        if (!permanent.contains(uuid)) return true;
        PermanentSnapshot previousSnapshot = permanentSnapshots.remove(uuid);
        Player player = Bukkit.getPlayer(uuid);
        permanent.remove(uuid);
        if (player == null && previousSnapshot != null) {
            pendingPermanentSnapshots.put(uuid, previousSnapshot);
        }
        if (!savePermanentData()) {
            permanent.add(uuid);
            if (player == null) pendingPermanentSnapshots.remove(uuid);
            if (previousSnapshot != null) permanentSnapshots.put(uuid, previousSnapshot);
            return false;
        }
        if (player != null) {
            boolean sessionOwnsState = sessions.containsKey(uuid) || hasPendingRestore(uuid);
            restorePermanentSnapshot(player, previousSnapshot, sessionOwnsState);
        } else if (previousSnapshot == null) {
            plugin.getLogger().warning("Permanenter Spectator-Eintrag offline entfernt, aber kein Ursprungszustand ist gespeichert: "
                    + uuid + ". Ein bereits gespeicherter Spectator-Gamemode kann deshalb nicht automatisch zurückgesetzt werden.");
        }
        audit("PERMANENT_REMOVE", uuid, uuid);
        return true;
    }

    private boolean savePermanentData() {
        String playersPath = "admin-spectator.permanent-players";
        String snapshotsPath = "admin-spectator.permanent-snapshots";
        String pendingPath = "admin-spectator.pending-permanent-restores";
        Object oldPlayers = plugin.getConfig().get(playersPath);
        Object oldSnapshots = plugin.getConfig().get(snapshotsPath);
        Object oldPending = plugin.getConfig().get(pendingPath);

        plugin.getConfig().set(playersPath,
                permanent.stream().map(UUID::toString).sorted().toList());
        plugin.getConfig().set(snapshotsPath, serializePermanentSnapshots(permanentSnapshots));
        plugin.getConfig().set(pendingPath, serializePermanentSnapshots(pendingPermanentSnapshots));
        try {
            plugin.saveConfig();
            return true;
        } catch (RuntimeException ex) {
            plugin.getConfig().set(playersPath, oldPlayers);
            plugin.getConfig().set(snapshotsPath, oldSnapshots);
            plugin.getConfig().set(pendingPath, oldPending);
            plugin.getLogger().severe("Permanente Spectator-Daten konnten nicht gespeichert werden; Änderung wurde zurückgenommen: "
                    + ex.getMessage());
            return false;
        }
    }

    private Map<String, Object> serializePermanentSnapshots(Map<UUID, PermanentSnapshot> snapshots) {
        Map<String, Object> serialized = new java.util.TreeMap<>();
        for (Map.Entry<UUID, PermanentSnapshot> entry : snapshots.entrySet()) {
            PermanentSnapshot snapshot = entry.getValue();
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("captured", true);
            values.put("game-mode", snapshot.gameMode().name());
            values.put("allow-flight", snapshot.allowFlight());
            values.put("flying", snapshot.flying());
            values.put("vanish-state-known", snapshot.vanishStateKnown());
            values.put("was-vanished", snapshot.wasVanished());
            serialized.put(entry.getKey().toString(), values);
        }
        return serialized;
    }

    private void loadPermanentSnapshots(String path, Map<UUID, PermanentSnapshot> destination) {
        var section = plugin.getConfig().getConfigurationSection(path);
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                if (!section.getBoolean(key + ".captured", true)) continue;
                GameMode gameMode = GameMode.valueOf(
                        section.getString(key + ".game-mode", "SPECTATOR").toUpperCase(java.util.Locale.ROOT));
                destination.put(uuid, new PermanentSnapshot(
                        gameMode,
                        section.getBoolean(key + ".allow-flight"),
                        section.getBoolean(key + ".flying"),
                        section.getBoolean(key + ".vanish-state-known"),
                        section.getBoolean(key + ".was-vanished")));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Ungültiger gespeicherter Spectator-Zustand für UUID " + key
                        + " in " + path + "; automatische Wiederherstellung kann unvollständig sein.");
            }
        }
    }

    private PermanentSnapshot capturePermanentSnapshot(Player player) {
        UUID uuid = player.getUniqueId();
        Session active = sessions.get(uuid);
        if (active != null) {
            Snapshot base = active.snapshot();
            boolean known = vanishAvailable();
            boolean wasVanished = false;
            if (known) {
                try {
                    wasVanished = vanish.isVanished(player);
                } catch (LinkageError | RuntimeException ex) {
                    markVanishUnavailable(ex);
                    known = false;
                }
            }
            if (active.vanishMode()) {
                known = true;
                wasVanished = active.wasVanished();
            }
            return new PermanentSnapshot(base.gameMode(), base.allowFlight(), base.flying(), known, wasVanished);
        }

        String pending = pendingRestorePath(uuid);
        if (hasPendingRestore(uuid)) {
            GameMode gameMode;
            try {
                gameMode = GameMode.valueOf(plugin.getConfig().getString(pending + ".game-mode", "SURVIVAL"));
            } catch (IllegalArgumentException ex) {
                gameMode = GameMode.SURVIVAL;
            }
            boolean sessionVanishMode = plugin.getConfig().getBoolean(pending + ".vanish-mode", false);
            boolean known;
            boolean wasVanished;
            if (sessionVanishMode) {
                known = true;
                wasVanished = plugin.getConfig().getBoolean(pending + ".was-vanished", false);
            } else {
                known = vanishAvailable();
                wasVanished = known && readVanishState(player);
                known = known && !vanishUnavailable;
            }
            return new PermanentSnapshot(gameMode,
                    plugin.getConfig().getBoolean(pending + ".allow-flight"),
                    plugin.getConfig().getBoolean(pending + ".flying"),
                    known, wasVanished);
        }

        boolean known = vanishAvailable();
        boolean wasVanished = known && readVanishState(player);
        return new PermanentSnapshot(player.getGameMode(), player.getAllowFlight(),
                player.isFlying(), known && !vanishUnavailable, wasVanished);
    }

    private boolean readVanishState(Player player) {
        if (!vanishAvailable()) return false;
        try {
            return vanish.isVanished(player);
        } catch (LinkageError | RuntimeException ex) {
            markVanishUnavailable(ex);
            return false;
        }
    }

    private void ensurePermanentSnapshot(Player player) {
        UUID uuid = player.getUniqueId();
        if (permanentSnapshots.containsKey(uuid)) return;
        permanentSnapshots.put(uuid, capturePermanentSnapshot(player));
        if (!savePermanentData()) {
            plugin.getLogger().warning("Vorheriger Zustand des permanenten Spectator-Accounts bleibt bis zum nächsten erfolgreichen Speichern nur im Arbeitsspeicher: "
                    + uuid);
        }
    }

    private void restorePermanentSnapshot(Player player, PermanentSnapshot snapshot, boolean sessionOwnsState) {
        if (sessionOwnsState) return;
        if (snapshot == null) {
            showObserverToAll(player);
            return;
        }
        try {
            player.setGameMode(snapshot.gameMode());
            player.setAllowFlight(snapshot.allowFlight());
            player.setFlying(snapshot.flying() && snapshot.allowFlight());
            if (player.getGameMode() != snapshot.gameMode()) {
                plugin.getLogger().warning("Ursprünglicher Gamemode konnte nach Entfernen der permanenten Spectator-Festlegung "
                        + "nicht wiederhergestellt werden: " + player.getUniqueId());
            }
        } catch (LinkageError | RuntimeException ex) {
            plugin.getLogger().warning("Spielerzustand konnte nach Entfernen der permanenten Spectator-Festlegung "
                    + "nicht vollständig wiederhergestellt werden: " + ex.getClass().getSimpleName());
        }
        restorePermanentVisibility(player, snapshot);
    }

    private void restorePermanentVisibility(Player player, PermanentSnapshot snapshot) {
        boolean vanishRestored = false;
        if (snapshot.vanishStateKnown() && vanishAvailable()) {
            try {
                vanish.setVanished(player, snapshot.wasVanished());
                vanishRestored = true;
            } catch (LinkageError | RuntimeException ex) {
                markVanishUnavailable(ex);
            }
        }
        if (snapshot.vanishStateKnown() && snapshot.wasVanished() && !vanishRestored) {
            hideObserverFromAll(player);
        } else {
            showObserverToAll(player);
        }
    }

    private boolean restorePendingPermanent(Player player) {
        UUID uuid = player.getUniqueId();
        PermanentSnapshot snapshot = pendingPermanentSnapshots.get(uuid);
        if (snapshot == null || permanent.contains(uuid)) return false;
        try {
            player.setGameMode(snapshot.gameMode());
            player.setAllowFlight(snapshot.allowFlight());
            player.setFlying(snapshot.flying() && snapshot.allowFlight());
            restorePermanentVisibility(player, snapshot);
            pendingPermanentSnapshots.remove(uuid);
            if (!savePermanentData()) {
                pendingPermanentSnapshots.put(uuid, snapshot);
                return false;
            }
            audit("PERMANENT_RESTORE", uuid, uuid);
            return true;
        } catch (LinkageError | RuntimeException ex) {
            plugin.getLogger().severe("Ausstehende permanente Spectator-Wiederherstellung fehlgeschlagen für "
                    + uuid + ": " + ex.getClass().getSimpleName());
            return false;
        }
    }

    private boolean vanishAvailable() {
        if (vanish == null || vanishUnavailable) return false;
        try {
            return vanish.isAvailable();
        } catch (LinkageError | RuntimeException ex) {
            markVanishUnavailable(ex);
            return false;
        }
    }

    private void markVanishUnavailable(Throwable error) {
        vanishUnavailable = true;
        if (!vanishFailureLogged) {
            plugin.getLogger().warning("SuperVanish-API ist zur Laufzeit nicht verfügbar; Bukkit-Hiding wird verwendet: "
                    + error.getClass().getSimpleName());
            vanishFailureLogged = true;
        }
    }

    private int createOffset(double targetCoordinate) {
        int magnitude = (2000 + random.nextInt(8001)) * 16;
        // Keep the translated client coordinate away from Minecraft's hard
        // +/-30,000,000 coordinate limit when observing near the world border.
        if (targetCoordinate >= 28_500_000.0) return magnitude;
        if (targetCoordinate <= -28_500_000.0) return -magnitude;
        return random.nextBoolean() ? magnitude : -magnitude;
    }

    private String pendingRestorePath(UUID uuid) {
        return "admin-spectator.pending-restores." + uuid;
    }

    private boolean hasPendingRestore(UUID uuid) {
        return plugin.getConfig().contains(pendingRestorePath(uuid) + ".world-uuid");
    }

    private void savePendingRestore(UUID uuid, Snapshot snapshot, boolean vanishMode, boolean wasVanished) {
        String path = pendingRestorePath(uuid);
        Location location = snapshot.location();
        World world = location.getWorld();
        if (world == null) throw new IllegalStateException("Kann Admin-Spectator-Zustand ohne Welt nicht sichern.");
        plugin.getConfig().set(path + ".world-uuid", world.getUID().toString());
        plugin.getConfig().set(path + ".world-name", world.getName());
        plugin.getConfig().set(path + ".x", location.getX());
        plugin.getConfig().set(path + ".y", location.getY());
        plugin.getConfig().set(path + ".z", location.getZ());
        plugin.getConfig().set(path + ".yaw", location.getYaw());
        plugin.getConfig().set(path + ".pitch", location.getPitch());
        plugin.getConfig().set(path + ".game-mode", snapshot.gameMode().name());
        plugin.getConfig().set(path + ".allow-flight", snapshot.allowFlight());
        plugin.getConfig().set(path + ".flying", snapshot.flying());
        plugin.getConfig().set(path + ".invulnerable", snapshot.invulnerable());
        plugin.getConfig().set(path + ".fly-speed", snapshot.flySpeed());
        plugin.getConfig().set(path + ".walk-speed", snapshot.walkSpeed());
        plugin.getConfig().set(path + ".view-distance", snapshot.viewDistance());
        plugin.getConfig().set(path + ".send-view-distance", snapshot.sendViewDistance());
        plugin.getConfig().set(path + ".spectator-target",
                snapshot.spectatorTarget() == null ? null : snapshot.spectatorTarget().toString());
        plugin.getConfig().set(path + ".vanish-mode", vanishMode);
        plugin.getConfig().set(path + ".was-vanished", wasVanished);
        plugin.saveConfig();
    }

    private void clearPendingRestore(UUID uuid) {
        String path = pendingRestorePath(uuid);
        var section = plugin.getConfig().getConfigurationSection(path);
        Map<String, Object> backup = section == null
                ? null : new java.util.HashMap<>(section.getValues(false));

        plugin.getConfig().set(path, null);
        try {
            plugin.saveConfig();
        } catch (RuntimeException ex) {
            // Keep the recovery marker available in memory so /adminspec off can retry.
            if (backup != null) plugin.getConfig().createSection(path, backup);
            throw ex;
        }
    }

    private boolean restorePending(Player player) {
        UUID uuid = player.getUniqueId();
        String path = pendingRestorePath(uuid);
        if (!hasPendingRestore(uuid)) return false;

        try {
            World world = null;
            String worldId = plugin.getConfig().getString(path + ".world-uuid", "");
            try {
                if (!worldId.isBlank()) world = Bukkit.getWorld(UUID.fromString(worldId));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ungültige Welt-UUID bei gespeicherter Spectator-Wiederherstellung: " + uuid);
            }
            if (world == null) {
                String savedWorldName = plugin.getConfig().getString(path + ".world-name", "<unbekannt>");
                keepObserverHidden(player);
                plugin.getLogger().severe("Gespeicherte Wiederherstellungswelt ist nicht verfügbar ("
                        + savedWorldName + "); Spieler bleibt verborgen im Spectator-Modus: " + uuid);
                return false;
            }

            double x = plugin.getConfig().getDouble(path + ".x", Double.NaN);
            double y = plugin.getConfig().getDouble(path + ".y", Double.NaN);
            double z = plugin.getConfig().getDouble(path + ".z", Double.NaN);
            float yaw = (float) plugin.getConfig().getDouble(path + ".yaw");
            float pitch = (float) plugin.getConfig().getDouble(path + ".pitch");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalStateException("Gespeicherte Position enthält ungültige Zahlen.");
            }

            Location location = new Location(world, x, y, z, yaw, pitch);
            boolean vanishMode = plugin.getConfig().getBoolean(path + ".vanish-mode", false);
            boolean wasVanished = plugin.getConfig().getBoolean(path + ".was-vanished", false);
            if (vanishMode && vanishAvailable()) {
                try {
                    vanish.setVanished(player, true);
                } catch (LinkageError | RuntimeException ex) {
                    markVanishUnavailable(ex);
                    hideObserverFromAll(player);
                }
            } else {
                hideObserverFromAll(player);
            }
            player.setSpectatorTarget(null);
            if (!player.teleport(location)) {
                keepObserverHidden(player);
                plugin.getLogger().severe("Gespeicherte Spectator-Position konnte nicht wiederhergestellt werden; Spieler bleibt verborgen: " + uuid);
                return false;
            }

            GameMode gameMode;
            try {
                gameMode = GameMode.valueOf(plugin.getConfig().getString(path + ".game-mode", "SURVIVAL"));
            } catch (IllegalArgumentException ex) {
                gameMode = GameMode.SURVIVAL;
                plugin.getLogger().warning("Ungültiger gespeicherter Gamemode; verwende SURVIVAL für " + uuid);
            }
            Snapshot snapshot = new Snapshot(location, gameMode,
                    plugin.getConfig().getBoolean(path + ".allow-flight"),
                    plugin.getConfig().getBoolean(path + ".flying"),
                    plugin.getConfig().getBoolean(path + ".invulnerable"),
                    (float) plugin.getConfig().getDouble(path + ".fly-speed", 0.1),
                    (float) plugin.getConfig().getDouble(path + ".walk-speed", 0.2),
                    plugin.getConfig().getInt(path + ".view-distance", player.getViewDistance()),
                    plugin.getConfig().getInt(path + ".send-view-distance", player.getSendViewDistance()),
                    parseOptionalUuid(plugin.getConfig().getString(path + ".spectator-target")));
            restoreSnapshot(player, snapshot);
            restoreVanishState(player, vanishMode, wasVanished);
            finishObserverVisibility(player, vanishMode, wasVanished);
            enforcePermanent(player);
            clearPendingRestore(uuid);
            audit("RECOVER", uuid, uuid);
            return true;
        } catch (LinkageError | RuntimeException ex) {
            keepObserverHidden(player);
            plugin.getLogger().severe("Gespeicherte Admin-Spectator-Wiederherstellung fehlgeschlagen für "
                    + uuid + ": " + ex.getMessage());
            return false;
        }
    }

    private UUID parseOptionalUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Ungültige gespeicherte Spectator-Ziel-UUID: " + value);
            return null;
        }
    }

    private void keepObserverHidden(Player observer) {
        try {
            observer.setSpectatorTarget(null);
            observer.setGameMode(GameMode.SPECTATOR);
            observer.setAllowFlight(true);
            observer.setFlying(true);
            hideObserverFromAll(observer);
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Fail-closed Spectator-Zustand konnte nicht vollständig gesetzt werden: " + ex.getMessage());
        }
    }

    private void restoreSnapshot(Player player, Snapshot snapshot) {
        player.setGameMode(snapshot.gameMode());
        player.setAllowFlight(snapshot.allowFlight());
        player.setFlying(snapshot.flying() && snapshot.allowFlight());
        player.setInvulnerable(snapshot.invulnerable());
        player.setFlySpeed(snapshot.flySpeed());
        player.setWalkSpeed(snapshot.walkSpeed());
        player.setViewDistance(snapshot.viewDistance());
        player.setSendViewDistance(snapshot.sendViewDistance());
        // Restore an earlier spectator camera target when it still exists.
        UUID previousTargetId = snapshot.spectatorTarget();
        if (player.getGameMode() == GameMode.SPECTATOR && previousTargetId != null) {
            org.bukkit.entity.Entity previousTarget = Bukkit.getEntity(previousTargetId);
            player.setSpectatorTarget(previousTarget);
        } else {
            player.setSpectatorTarget(null);
        }
        // Permanent Spectator wins over the pre-observation game mode.
        enforcePermanent(player);
    }

    private void enforcePermanent(Player player) {
        if (!permanent.contains(player.getUniqueId())) return;
        ensurePermanentSnapshot(player);
        if (player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
        if (!player.getAllowFlight()) player.setAllowFlight(true);
        if (!player.isFlying()) player.setFlying(true);
        if (vanishAvailable()) {
            try {
                if (!vanish.isVanished(player)) vanish.setVanished(player, true);
                return;
            } catch (LinkageError | RuntimeException ex) {
                markVanishUnavailable(ex);
            }
        }
        hideObserverFromAll(player);
    }

    private void restoreVanishState(Player player, boolean vanishMode, boolean wasVanished) {
        if (!vanishMode || permanent.contains(player.getUniqueId())) return;
        if (vanishAvailable()) {
            try {
                vanish.setVanished(player, wasVanished);
                return;
            } catch (LinkageError | RuntimeException ex) {
                markVanishUnavailable(ex);
            }
        }
        if (wasVanished) hideObserverFromAll(player);
    }

    private void finishObserverVisibility(Player player, boolean vanishMode, boolean wasVanished) {
        if (permanent.contains(player.getUniqueId())) {
            enforcePermanent(player);
        } else if (pendingPermanentSnapshots.containsKey(player.getUniqueId())) {
            hideObserverFromAll(player);
        } else if (vanishMode && wasVanished && !vanishAvailable()) {
            hideObserverFromAll(player);
        } else {
            showObserverToAll(player);
        }
    }

    private void hideObserverFromAll(Player observer) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(observer)) viewer.hidePlayer(plugin, observer);
        }
    }

    private void showObserverToAll(Player observer) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(observer)) viewer.showPlayer(plugin, observer);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        Player joining = event.getPlayer();
        UUID joiningId = joining.getUniqueId();

        // Register hides before the joining entity is inserted into the world and
        // before existing clients can receive its first spawn packet.
        if (hasPendingRestore(joiningId) || pendingPermanentSnapshots.containsKey(joiningId)) {
            hideObserverFromAll(joining);
        }
        for (UUID observerId : Set.copyOf(sessions.keySet())) {
            Player observer = Bukkit.getPlayer(observerId);
            if (observer != null && !observer.equals(joining)) {
                joining.hidePlayer(plugin, observer);
            }
        }
        for (UUID permanentId : Set.copyOf(permanent)) {
            Player admin = Bukkit.getPlayer(permanentId);
            if (admin != null && !admin.equals(joining)
                    && !vanishAvailable()) {
                joining.hidePlayer(plugin, admin);
            }
        }
        enforcePermanent(joining);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();

        // Apply visibility synchronously in the join event, before the joining
        // player can receive a post-join world/entity update containing an observer.
        for (UUID observerId : Set.copyOf(sessions.keySet())) {
            Player observer = Bukkit.getPlayer(observerId);
            if (observer != null && !observer.equals(joining)) {
                joining.hidePlayer(plugin, observer);
            }
        }
        for (UUID permanentId : Set.copyOf(permanent)) {
            Player admin = Bukkit.getPlayer(permanentId);
            if (admin != null && !admin.equals(joining)
                    && !vanishAvailable()) {
                joining.hidePlayer(plugin, admin);
            }
        }

        // Recover the observer's saved state first. An offline permanent removal is
        // applied afterwards, so it takes precedence over the snapshot from that session.
        boolean restoredSession = restorePending(joining);
        UUID joiningId = joining.getUniqueId();
        if (!hasPendingRestore(joiningId)) {
            if (!permanent.contains(joiningId)) {
                restorePendingPermanent(joining);
            } else if (!restoredSession) {
                enforcePermanent(joining);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (permanent.contains(event.getPlayer().getUniqueId()) && event.getNewGameMode() != GameMode.SPECTATOR) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Dein Account ist dauerhaft auf Spectator festgelegt.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player quitter = event.getPlayer();

        // A quitting observer cannot be reliably teleported back: Bukkit may reject
        // teleports once the disconnect sequence has started. Do not leave a stale
        // session behind, or the next login would inherit the old hidden state.
        Session own = sessions.get(quitter.getUniqueId());
        if (own != null && sessions.remove(quitter.getUniqueId(), own)) {
            synchronized (own) {
                // Keep the persisted snapshot so the next login can restore the original
                // location and player settings before this account becomes visible.
                quitter.setSpectatorTarget(null);
                audit("ABORT_QUIT_PENDING_RECOVERY", quitter.getUniqueId(), own.target());
            }
        }

        // If the observed player leaves, the observer is still online and can be
        // safely returned to the saved location using the normal fail-closed path.
        for (Map.Entry<UUID, Session> entry : Set.copyOf(sessions.entrySet())) {
            if (entry.getValue().target().equals(quitter.getUniqueId())) {
                Player observer = Bukkit.getPlayer(entry.getKey());
                if (observer != null) stop(observer, "Zielspieler hat den Server verlassen");
            }
        }
    }

    private void translateEntityMetadata(PacketEvent event, int offsetX, int offsetZ) {
        var valuesModifier = event.getPacket().getDataValueCollectionModifier();
        if (valuesModifier.size() == 0) {
            throw new IllegalStateException("Entity-Metadaten enthalten keine lesbare DataValue-Liste.");
        }

        List<WrappedDataValue> values = valuesModifier.read(0);
        for (WrappedDataValue dataValue : values) {
            Object value = dataValue.getValue();
            if (value instanceof BlockPosition position) {
                dataValue.setValue(new BlockPosition(
                        position.getX() - offsetX, position.getY(), position.getZ() - offsetZ));
            } else if (value instanceof Optional<?> optional && optional.isPresent()) {
                Object nested = optional.get();
                if (nested instanceof BlockPosition position) {
                    dataValue.setValue(Optional.of(new BlockPosition(
                            position.getX() - offsetX, position.getY(), position.getZ() - offsetZ)));
                } else if (nested instanceof NbtCompound compound) {
                    translateBlockEntityNbt(compound, offsetX, offsetZ);
                    dataValue.setValue(Optional.of(compound));
                }
            } else if (value instanceof NbtCompound compound) {
                translateBlockEntityNbt(compound, offsetX, offsetZ);
                dataValue.setValue(compound);
            }
        }
        valuesModifier.write(0, values);
    }

    private void translateBlockEntityNbt(NbtCompound tag, int offsetX, int offsetZ) {
        if (tag == null || !tag.containsKey("x") || !tag.containsKey("z")) return;

        // Vanilla BlockEntity identifying data stores x/y/z as integer NBT tags.
        // Shift only the standard identifying coordinates; leave custom block-entity data intact.
        tag.put("x", tag.getInteger("x") - offsetX);
        tag.put("z", tag.getInteger("z") - offsetZ);
    }

    private void translateChunkBlockEntityNbt(PacketEvent event, int offsetX, int offsetZ) {
        var chunkDataModifier = event.getPacket().getLevelChunkData();
        if (chunkDataModifier.size() == 0) {
            throw new IllegalStateException("Chunk-Paket enthält keine lesbare ChunkData-Struktur.");
        }

        WrappedLevelChunkData.ChunkData chunkData = chunkDataModifier.read(0);
        for (WrappedLevelChunkData.BlockEntityInfo info : chunkData.getBlockEntityInfo()) {
            NbtCompound tag = info.getAdditionalData();
            if (tag != null) {
                translateBlockEntityNbt(tag, offsetX, offsetZ);
                info.setAdditionalData(tag);
            }
        }
        chunkDataModifier.write(0, chunkData);
    }

    private void translateBlockEntityPacketNbt(PacketEvent event, int offsetX, int offsetZ) {
        var tags = event.getPacket().getNbtModifier();
        if (tags.size() == 0) {
            throw new IllegalStateException("Block-Entity-Paket enthält keinen NBT-Tag.");
        }
        NbtBase<?> tag = tags.read(0);
        if (!(tag instanceof NbtCompound compound)) {
            throw new IllegalStateException("Block-Entity-Paket enthält keinen NBT-Compound.");
        }
        translateBlockEntityNbt(compound, offsetX, offsetZ);
        tags.write(0, compound);
    }

    private void sanitizeRespawn(PacketEvent event) {
        // Respawn's CommonPlayerSpawnInfo contains Optional<GlobalPos> lastDeathLocation.
        // getOptionalStructures() only matches Optional<InternalStructure>, so inspect
        // the nested structure's actual Optional-typed fields instead.
        var structures = event.getPacket().getStructures();
        for (int index = 0; index < structures.size(); index++) {
            var structure = structures.read(index);
            var optionalFields = structure.getModifier().withType(Optional.class);
            if (optionalFields.size() == 1) {
                optionalFields.write(0, Optional.empty());
                structures.write(index, structure);
                return;
            }
        }
        throw new IllegalStateException("Respawn enthält keine eindeutige Optional-Todesposition in CommonPlayerSpawnInfo.");
    }

    private void translateDoubleCoordinates(PacketEvent event, int offsetX, int offsetZ, int xIndex, int zIndex) {
        var doubles = event.getPacket().getDoubles();
        if (doubles.size() <= Math.max(xIndex, zIndex)) {
            throw new IllegalStateException("Paket enthält nicht die erwarteten Koordinatenfelder: " + event.getPacketType());
        }
        doubles.write(xIndex, doubles.read(xIndex) - offsetX);
        doubles.write(zIndex, doubles.read(zIndex) - offsetZ);
    }

    private void translateChunkCoordinates(PacketEvent event, int offsetX, int offsetZ) {
        int chunkOffsetX = offsetX / 16;
        int chunkOffsetZ = offsetZ / 16;
        var chunkPairs = event.getPacket().getChunkCoordIntPairs();
        if (chunkPairs.size() > 0) {
            ChunkCoordIntPair pair = chunkPairs.read(0);
            chunkPairs.write(0, new ChunkCoordIntPair(
                    pair.getChunkX() - chunkOffsetX,
                    pair.getChunkZ() - chunkOffsetZ));
            return;
        }

        // Older ProtocolLib mappings expose chunk coordinates as two integer fields.
        var integers = event.getPacket().getIntegers();
        if (integers.size() >= 2) {
            integers.write(0, integers.read(0) - chunkOffsetX);
            integers.write(1, integers.read(1) - chunkOffsetZ);
            return;
        }
        throw new IllegalStateException("Paket enthält keine unterstützte Chunk-Koordinate: " + event.getPacketType());
    }

    private void translateBlockPosition(PacketEvent event, int deltaX, int deltaZ) {
        var positions = event.getPacket().getBlockPositionModifier();
        if (positions.size() == 0) {
            throw new IllegalStateException("Paket enthält keine Blockposition: " + event.getPacketType());
        }
        var pos = positions.read(0);
        positions.write(0, new com.comphenix.protocol.wrappers.BlockPosition(
                pos.getX() + deltaX, pos.getY(), pos.getZ() + deltaZ));
    }

    private void translateVectorCoordinates(PacketEvent event, int offsetX, int offsetZ) {
        var vectors = event.getPacket().getVectors();
        if (vectors.size() == 0) {
            throw new IllegalStateException("Paket enthält keinen Positionsvektor: " + event.getPacketType());
        }
        Vector position = vectors.read(0);
        vectors.write(0, translated(position, -offsetX, -offsetZ));
    }

    private Vector translated(Vector position, int deltaX, int deltaZ) {
        return new Vector(position.getX() + deltaX, position.getY(), position.getZ() + deltaZ);
    }

    private void translatePositionMoveRotation(PacketEvent event, int offsetX, int offsetZ) {
        var positions = event.getPacket().getPositionMoveRotation();
        if (positions.size() == 0) {
            throw new IllegalStateException("Paket enthält keine Position/Rotation: " + event.getPacketType());
        }
        WrappedPositionMoveRotation position = positions.read(0);
        position.setPosition(translated(position.getPosition(), -offsetX, -offsetZ));
        positions.write(0, position);
    }

    private void translateRelativePosition(PacketEvent event, int offsetX, int offsetZ) {
        var positions = event.getPacket().getPositionMoveRotation();
        if (positions.size() == 0) {
            throw new IllegalStateException("Relatives Positionspaket enthält keine Position/Rotation.");
        }

        // ClientboundPlayerPositionPacket in 1.21.11 carries a Set<Relative>.
        // Relative X/Z values are deltas from the client's already-shifted position,
        // so only absolute components receive the coordinate offset.
        var setModifiers = event.getPacket().getModifier().withType(Set.class);
        if (setModifiers.size() == 0) {
            throw new IllegalStateException("Relatives Positionspaket enthält keine Relative-Flags.");
        }
        Set<?> flags = (Set<?>) setModifiers.read(0);
        boolean relativeX = containsRelativeFlag(flags, "X");
        boolean relativeZ = containsRelativeFlag(flags, "Z");

        WrappedPositionMoveRotation position = positions.read(0);
        Vector current = position.getPosition();
        position.setPosition(new Vector(
                relativeX ? current.getX() : current.getX() - offsetX,
                current.getY(),
                relativeZ ? current.getZ() : current.getZ() - offsetZ));
        positions.write(0, position);
    }

    private boolean containsRelativeFlag(Set<?> flags, String expectedName) {
        if (flags == null) throw new IllegalStateException("Relative-Flags sind null.");
        return flags.stream().anyMatch(flag ->
                flag instanceof Enum<?> enumFlag && enumFlag.name().equals(expectedName));
    }

    private void translateEntityPosition(PacketEvent event, int offsetX, int offsetZ) {
        var positions = event.getPacket().getPositionMoveRotation();
        if (positions.size() > 0) {
            WrappedPositionMoveRotation position = positions.read(0);
            position.setPosition(translated(position.getPosition(), -offsetX, -offsetZ));
            positions.write(0, position);
            return;
        }
        // Some newer protocol revisions expose position-sync payloads as vectors.
        var vectors = event.getPacket().getVectors();
        if (vectors.size() > 0) {
            translateVectorCoordinates(event, offsetX, offsetZ);
            return;
        }
        throw new IllegalStateException("Paket enthält kein unterstütztes Positionsformat: " + event.getPacketType());
    }

    private void translateVehicleMove(PacketEvent event, int offsetX, int offsetZ) {
        var positions = event.getPacket().getPositionMoveRotation();
        if (positions.size() > 0) {
            WrappedPositionMoveRotation position = positions.read(0);
            position.setPosition(translated(position.getPosition(), -offsetX, -offsetZ));
            positions.write(0, position);
            return;
        }
        var vectors = event.getPacket().getVectors();
        if (vectors.size() > 0) {
            translateVectorCoordinates(event, offsetX, offsetZ);
            return;
        }
        var doubles = event.getPacket().getDoubles();
        if (doubles.size() >= 3) {
            translateDoubleCoordinates(event, offsetX, offsetZ, 0, 2);
            return;
        }
        throw new IllegalStateException("Fahrzeugpaket enthält kein unterstütztes Positionsformat.");
    }

    private void translateUseItemOn(PacketEvent event, int offsetX, int offsetZ) {
        // 1.21.11 UseItemOn contains Hand, BlockPos, face and block-local cursor coordinates.
        // Translate only the absolute BlockPos, not the block-local cursor vector.
        translateBlockPosition(event, offsetX, offsetZ);
    }

    private void registerPacketTranslation() {
        protocol.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.MAP_CHUNK,
                PacketType.Play.Server.UNLOAD_CHUNK,
                PacketType.Play.Server.LIGHT_UPDATE,
                PacketType.Play.Server.VIEW_CENTRE,
                PacketType.Play.Server.MULTI_BLOCK_CHANGE,
                PacketType.Play.Server.BLOCK_CHANGE,
                PacketType.Play.Server.TILE_ENTITY_DATA,
                PacketType.Play.Server.BLOCK_ACTION,
                PacketType.Play.Server.BLOCK_BREAK_ANIMATION,
                PacketType.Play.Server.SPAWN_POSITION,
                PacketType.Play.Server.OPEN_SIGN_EDITOR,
                PacketType.Play.Server.SPAWN_ENTITY,
                PacketType.Play.Server.ENTITY_TELEPORT,
                PacketType.Play.Server.ENTITY_POSITION_SYNC,
                PacketType.Play.Server.ENTITY_METADATA,
                PacketType.Play.Server.POSITION,
                PacketType.Play.Server.EXPLOSION,
                PacketType.Play.Server.RESPAWN,
                PacketType.Play.Server.WORLD_PARTICLES,
                PacketType.Play.Server.CUSTOM_PAYLOAD,
                PacketType.Play.Server.CHUNKS_BIOMES,
                PacketType.Play.Server.DEBUG_BLOCK_VALUE,
                PacketType.Play.Server.DEBUG_CHUNK_VALUE,
                PacketType.Play.Server.DEBUG_ENTITY_VALUE,
                PacketType.Play.Server.DEBUG_EVENT,
                PacketType.Play.Server.DEBUG_SAMPLE,
                PacketType.Play.Server.GAME_TEST_HIGHLIGHT_POS,
                PacketType.Play.Server.MOVE_MINECART,
                PacketType.Play.Server.LOOK_AT,
                PacketType.Play.Server.TRACKED_WAYPOINT,
                PacketType.Play.Server.TEST_INSTANCE_BLOCK_STATUS,
                PacketType.Play.Server.WORLD_EVENT,
                PacketType.Play.Server.NAMED_SOUND_EFFECT,
                PacketType.Play.Server.SET_BORDER_CENTER,
                PacketType.Play.Server.INITIALIZE_BORDER,
                PacketType.Play.Server.VEHICLE_MOVE,
                PacketType.Play.Client.BLOCK_DIG,
                PacketType.Play.Client.TILE_NBT_QUERY,
                PacketType.Play.Client.ENTITY_NBT_QUERY,
                PacketType.Play.Client.USE_ITEM_ON,
                PacketType.Play.Client.PICK_ITEM_FROM_BLOCK,
                PacketType.Play.Client.VEHICLE_MOVE,
                PacketType.Play.Client.JIGSAW_GENERATE,
                PacketType.Play.Client.TEST_INSTANCE_BLOCK_ACTION,
                PacketType.Play.Client.POSITION,
                PacketType.Play.Client.POSITION_LOOK) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Session session = sessions.get(event.getPlayer().getUniqueId());
                if (session == null) return;
                synchronized (session) {
                    if (sessions.get(event.getPlayer().getUniqueId()) != session) return;
                    try {
                    PacketType type = event.getPacketType();
                    // These payloads may contain nested or packed world coordinates.
                    // Suppress them for the observer until their full wire layout is transformed.
                    if (type == PacketType.Play.Server.EXPLOSION
                            || type == PacketType.Play.Server.WORLD_PARTICLES
                            || type == PacketType.Play.Server.CUSTOM_PAYLOAD
                            || type == PacketType.Play.Server.CHUNKS_BIOMES
                            || type == PacketType.Play.Server.DEBUG_BLOCK_VALUE
                            || type == PacketType.Play.Server.DEBUG_CHUNK_VALUE
                            || type == PacketType.Play.Server.DEBUG_ENTITY_VALUE
                            || type == PacketType.Play.Server.DEBUG_EVENT
                            || type == PacketType.Play.Server.DEBUG_SAMPLE
                            || type == PacketType.Play.Server.GAME_TEST_HIGHLIGHT_POS
                            || type == PacketType.Play.Server.MOVE_MINECART
                            || type == PacketType.Play.Server.LOOK_AT
                            || type == PacketType.Play.Server.TRACKED_WAYPOINT
                            || type == PacketType.Play.Server.TEST_INSTANCE_BLOCK_STATUS) {
                        event.setCancelled(true);
                        return;
                    } else if (type == PacketType.Play.Server.MAP_CHUNK) {
                        translateChunkCoordinates(event, session.offsetX(), session.offsetZ());
                        translateChunkBlockEntityNbt(event, session.offsetX(), session.offsetZ());
                    } else if (type == PacketType.Play.Server.UNLOAD_CHUNK
                            || type == PacketType.Play.Server.LIGHT_UPDATE
                            || type == PacketType.Play.Server.VIEW_CENTRE) {
                        translateChunkCoordinates(event, session.offsetX(), session.offsetZ());
                    } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
                        var section = event.getPacket().getSectionPositions().read(0);
                        event.getPacket().getSectionPositions().write(0,
                                new com.comphenix.protocol.wrappers.BlockPosition(
                                        section.getX() - session.offsetX() / 16,
                                        section.getY(),
                                        section.getZ() - session.offsetZ() / 16));
                    } else if (type == PacketType.Play.Server.TILE_ENTITY_DATA) {
                        translateBlockPosition(event, -session.offsetX(), -session.offsetZ());
                        translateBlockEntityPacketNbt(event, session.offsetX(), session.offsetZ());
                    } else if (type == PacketType.Play.Server.BLOCK_CHANGE
                            || type == PacketType.Play.Server.BLOCK_ACTION
                            || type == PacketType.Play.Server.BLOCK_BREAK_ANIMATION
                            || type == PacketType.Play.Server.SPAWN_POSITION
                            || type == PacketType.Play.Server.OPEN_SIGN_EDITOR) {
                        translateBlockPosition(event, -session.offsetX(), -session.offsetZ());
                    } else if (type == PacketType.Play.Server.SPAWN_ENTITY) {
                        // In 1.21.11 X/Y/Z are three doubles; getVectors() is entity velocity.
                        translateDoubleCoordinates(event, session.offsetX(), session.offsetZ(), 0, 2);
                    } else if (type == PacketType.Play.Server.ENTITY_TELEPORT
                            || type == PacketType.Play.Server.POSITION) {
                        // Both packet types carry PositionMoveRotation plus relative flags.
                        translateRelativePosition(event, session.offsetX(), session.offsetZ());
                    } else if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
                        translateEntityPosition(event, session.offsetX(), session.offsetZ());
                    } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
                        translateEntityMetadata(event, session.offsetX(), session.offsetZ());
                    } else if (type == PacketType.Play.Server.RESPAWN) {
                        sanitizeRespawn(event);
                    } else if (type == PacketType.Play.Server.VEHICLE_MOVE) {
                        translateVehicleMove(event, session.offsetX(), session.offsetZ());
                    } else if (type == PacketType.Play.Server.WORLD_EVENT) {
                        translateBlockPosition(event, -session.offsetX(), -session.offsetZ());
                    } else if (type == PacketType.Play.Server.NAMED_SOUND_EFFECT) {
                        // Sound packet coordinates use fixed-point block coordinates multiplied by eight.
                        var ints = event.getPacket().getIntegers();
                        if (ints.size() < 3) {
                            throw new IllegalStateException("Soundpaket enthält nicht die erwarteten Festkomma-Koordinaten.");
                        }
                        ints.write(0, ints.read(0) - session.offsetX() * 8);
                        ints.write(2, ints.read(2) - session.offsetZ() * 8);
                    } else if (type == PacketType.Play.Server.SET_BORDER_CENTER) {
                        translateDoubleCoordinates(event, session.offsetX(), session.offsetZ(), 0, 1);
                    } else if (type == PacketType.Play.Server.INITIALIZE_BORDER) {
                        // The initial world-border packet begins with its X/Z center coordinates.
                        translateDoubleCoordinates(event, session.offsetX(), session.offsetZ(), 0, 1);
                    }
                    } catch (LinkageError | Exception ex) {
                        plugin.getLogger().warning("Konnte ausgehendes Paket für Admin-Spectator nicht transformieren: " + ex.getMessage());
                        event.setCancelled(true);
                    }
                }
            }

            @Override
            public void onPacketReceiving(PacketEvent event) {
                UUID playerId = event.getPlayer().getUniqueId();
                Session session = sessions.get(playerId);
                if (session == null) {
                    // Ignore stale shifted input while the server is sending the
                    // unshifted return teleport and restoring the saved state.
                    if (restoring.contains(playerId)) event.setCancelled(true);
                    return;
                }
                synchronized (session) {
                    if (sessions.get(playerId) != session) {
                        if (restoring.contains(playerId)) event.setCancelled(true);
                        return;
                    }
                    try {
                    if (event.getPacketType() == PacketType.Play.Client.TILE_NBT_QUERY
                            || event.getPacketType() == PacketType.Play.Client.ENTITY_NBT_QUERY) {
                        // Prevent a hidden observer from requesting raw block/entity NBT
                        // containing server-side coordinates.
                        event.setCancelled(true);
                        return;
                    }
                    PacketType type = event.getPacketType();
                    if (type == PacketType.Play.Client.USE_ITEM_ON) {
                        translateUseItemOn(event, session.offsetX(), session.offsetZ());
                        return;
                    }
                    if (type == PacketType.Play.Client.VEHICLE_MOVE) {
                        // translateVehicleMove subtracts its arguments; negate to restore real server coordinates.
                        translateVehicleMove(event, -session.offsetX(), -session.offsetZ());
                        return;
                    }
                    var modifier = event.getPacket().getBlockPositionModifier();
                    boolean requiresBlockPosition = type == PacketType.Play.Client.BLOCK_DIG
                            || type == PacketType.Play.Client.PICK_ITEM_FROM_BLOCK
                            || type == PacketType.Play.Client.JIGSAW_GENERATE
                            || type == PacketType.Play.Client.TEST_INSTANCE_BLOCK_ACTION;
                    if (requiresBlockPosition && modifier.size() == 0) {
                        throw new IllegalStateException("Positionspaket enthält keine lesbare Blockposition: " + type);
                    }
                    if (modifier.size() > 0) {
                        var pos = modifier.read(0);
                        modifier.write(0, new com.comphenix.protocol.wrappers.BlockPosition(
                                pos.getX() + session.offsetX(), pos.getY(), pos.getZ() + session.offsetZ()));
                    }
                    // The client sends its shifted player coordinates back to the server.
                    if (event.getPacketType() == PacketType.Play.Client.POSITION
                            || event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
                        var doubles = event.getPacket().getDoubles();
                        if (doubles.size() < 3) {
                            throw new IllegalStateException("Bewegungspaket enthält nicht alle Spielerkoordinaten.");
                        }
                        doubles.write(0, doubles.read(0) + session.offsetX());
                        doubles.write(2, doubles.read(2) + session.offsetZ());
                    }
                    } catch (LinkageError | Exception ex) {
                        plugin.getLogger().warning("Konnte eingehendes Interaktionspaket nicht transformieren: " + ex.getMessage());
                        event.setCancelled(true);
                    }
                }
            }
        });
        packetListenerRegistered = true;
    }

    private void audit(String action, UUID actor, UUID target) {
        plugin.getLogger().info("[AdminSpectatorAudit] " + action + " actor=" + actor + " target=" + target
                + " time=" + System.currentTimeMillis());
    }

    void shutdown() {
        try {
            for (UUID uuid : Set.copyOf(sessions.keySet())) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    try {
                        stop(player, "Plugin wird deaktiviert");
                    } catch (RuntimeException ex) {
                        plugin.getLogger().severe("Fehler beim Beenden einer Admin-Spectator-Sitzung für "
                                + uuid + ": " + ex.getMessage());
                    }
                }
            }
        } finally {
            if (packetListenerRegistered) {
                try {
                    protocol.removePacketListeners(plugin);
                } catch (RuntimeException ex) {
                    plugin.getLogger().severe("ProtocolLib-Listener konnten nicht vollständig entfernt werden: " + ex.getMessage());
                } finally {
                    packetListenerRegistered = false;
                }
            }
        }
    }
}
