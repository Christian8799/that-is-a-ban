package io.github.christian8799.thatsaban;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.ChunkCoordIntPair;
import com.comphenix.protocol.wrappers.MovingObjectPositionBlock;
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
    private final SecureRandom random = new SecureRandom();
    private final VanishIntegration vanish;
    private boolean packetListenerRegistered;

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
        try {
            // Register the external packet hook first. If ProtocolLib is incompatible,
            // no Bukkit event listener or repeating task is left partially installed.
            registerPacketTranslation();
            Bukkit.getPluginManager().registerEvents(this, plugin);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!restorePending(player)) enforcePermanent(player);
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
        if (vanishMode && (vanish == null || !vanish.isAvailable())) {
            observer.sendMessage("Der Vanish-Modus benötigt das installierte und kompatible SuperVanish-Plugin.");
            return false;
        }

        UUID observerId = observer.getUniqueId();
        Session active = sessions.get(observerId);
        if (active != null) {
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
        boolean wasVanished = vanish != null && vanish.isAvailable() && vanish.isVanished(observer);
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
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Admin-Spectator konnte nicht gestartet werden: " + ex.getMessage());
            if (sessions.containsKey(observerId)) {
                try {
                    stop(observer, "Startfehler");
                } catch (RuntimeException restoreError) {
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
            } catch (RuntimeException ex) {
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
                if (!session.vanishMode || !session.wasVanished) showObserverToAll(observer);
                enforcePermanent(observer);
                clearPendingRestore(observerId);
                audit("STOP (" + reason + ")", observerId, session.target());
                return true;
            } catch (RuntimeException ex) {
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
        List<String> previousConfig = plugin.getConfig().getStringList("admin-spectator.permanent-players");
        permanent.add(uuid);
        if (!savePermanentList(previousConfig)) {
            permanent.remove(uuid);
            return false;
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) enforcePermanent(player);
        audit("PERMANENT_ADD", uuid, uuid);
        return true;
    }

    boolean removePermanent(UUID uuid) {
        if (!permanent.contains(uuid)) return true;
        List<String> previousConfig = plugin.getConfig().getStringList("admin-spectator.permanent-players");
        permanent.remove(uuid);
        if (!savePermanentList(previousConfig)) {
            permanent.add(uuid);
            return false;
        }
        audit("PERMANENT_REMOVE", uuid, uuid);
        return true;
    }

    private boolean savePermanentList(List<String> previousConfig) {
        plugin.getConfig().set("admin-spectator.permanent-players",
                permanent.stream().map(UUID::toString).sorted().toList());
        try {
            plugin.saveConfig();
            return true;
        } catch (RuntimeException ex) {
            plugin.getConfig().set("admin-spectator.permanent-players", previousConfig);
            plugin.getLogger().severe("Permanente Spectator-Liste konnte nicht gespeichert werden; Änderung wurde zurückgenommen: "
                    + ex.getMessage());
            return false;
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
            if (vanishMode && vanish != null && vanish.isAvailable()) vanish.setVanished(player, true);
            else hideObserverFromAll(player);
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
            if (!vanishMode || !wasVanished) showObserverToAll(player);
            enforcePermanent(player);
            clearPendingRestore(uuid);
            audit("RECOVER", uuid, uuid);
            return true;
        } catch (RuntimeException ex) {
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
        if (player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
        if (!player.getAllowFlight()) player.setAllowFlight(true);
        if (!player.isFlying()) player.setFlying(true);
        if (vanish != null && vanish.isAvailable()) {
            if (!vanish.isVanished(player)) vanish.setVanished(player, true);
        } else {
            hideObserverFromAll(player);
        }
    }

    private void restoreVanishState(Player player, boolean vanishMode, boolean wasVanished) {
        if (!vanishMode || vanish == null || !vanish.isAvailable()) return;
        vanish.setVanished(player, wasVanished);
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
        if (hasPendingRestore(joiningId)) {
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
                    && (vanish == null || !vanish.isAvailable())) {
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
                    && (vanish == null || !vanish.isAvailable())) {
                joining.hidePlayer(plugin, admin);
            }
        }

        // Recover an interrupted observation before revealing the joining account.
        if (!restorePending(joining)) enforcePermanent(joining);
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
        var hits = event.getPacket().getMovingBlockPositions();
        if (hits.size() == 0) {
            throw new IllegalStateException("Paket enthält keinen Blocktreffer: " + event.getPacketType());
        }
        MovingObjectPositionBlock hit = hits.read(0);
        var block = hit.getBlockPosition();
        hit.setBlockPosition(new com.comphenix.protocol.wrappers.BlockPosition(
                block.getX() + offsetX, block.getY(), block.getZ() + offsetZ));
        hit.setPosVector(translated(hit.getPosVector(), offsetX, offsetZ));
        hits.write(0, hit);
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
                    } catch (Exception ex) {
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
                    if (event.getPacketType() == PacketType.Play.Client.USE_ITEM_ON) {
                        translateUseItemOn(event, session.offsetX(), session.offsetZ());
                        return;
                    }
                    var modifier = event.getPacket().getBlockPositionModifier();
                    if (event.getPacketType() == PacketType.Play.Client.BLOCK_DIG && modifier.size() == 0) {
                        throw new IllegalStateException("Abbaupaket enthält keine Blockposition.");
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
                    } catch (Exception ex) {
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
