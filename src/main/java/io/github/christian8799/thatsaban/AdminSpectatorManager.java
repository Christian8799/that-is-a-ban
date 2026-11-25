package io.github.christian8799.thatsaban;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class AdminSpectatorManager implements Listener {
    private final JavaPlugin plugin;
    private final ProtocolManager protocol;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> permanent = new HashSet<>();
    private final Random random = new Random();
    private boolean packetListenerRegistered;

    private record Snapshot(Location location, GameMode gameMode, boolean allowFlight,
                            boolean flying, boolean invulnerable, float flySpeed, float walkSpeed,
                            int viewDistance, int sendViewDistance) {}
    private record Session(UUID target, Snapshot snapshot, int offsetX, int offsetZ) {}

    AdminSpectatorManager(JavaPlugin plugin) {
        this.plugin = plugin;
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            throw new IllegalStateException("ProtocolLib fehlt; Admin-Spectator wird nicht aktiviert.");
        }
        this.protocol = ProtocolLibrary.getProtocolManager();
        for (String raw : plugin.getConfig().getStringList("admin-spectator.permanent-players")) {
            try { permanent.add(UUID.fromString(raw)); }
            catch (IllegalArgumentException ex) { plugin.getLogger().warning("Ungültige UUID in admin-spectator.permanent-players: " + raw); }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerPacketTranslation();
        for (Player player : Bukkit.getOnlinePlayers()) enforcePermanent(player);
        // Re-enforce against plugins that bypass the Bukkit game-mode event.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) enforcePermanent(player);
        }, 20L, 20L);
    }

    boolean isObserving(Player observer) {
        return sessions.containsKey(observer.getUniqueId());
    }

    boolean observe(Player observer, Player target) {
        if (observer.equals(target)) return false;
        if (sessions.containsKey(observer.getUniqueId())
                && !stop(observer, "Neue Beobachtung gestartet")) return false;
        Snapshot snapshot = new Snapshot(observer.getLocation().clone(), observer.getGameMode(),
                observer.getAllowFlight(), observer.isFlying(), observer.isInvulnerable(),
                observer.getFlySpeed(), observer.getWalkSpeed(),
                observer.getViewDistance(), observer.getSendViewDistance());
        // Chunk-aligned offsets preserve local block coordinates while changing absolute client coordinates.
        int offsetX = (2000 + random.nextInt(8001)) * 16;
        int offsetZ = (2000 + random.nextInt(8001)) * 16;
        if (random.nextBoolean()) offsetX = -offsetX;
        if (random.nextBoolean()) offsetZ = -offsetZ;
        sessions.put(observer.getUniqueId(), new Session(target.getUniqueId(), snapshot, offsetX, offsetZ));
        // Hide before changing mode or moving: otherwise the initial spectator
        // transition can briefly expose the moderator at their real location.
        hideObserverFromAll(observer);
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
        audit("START", observer.getUniqueId(), target.getUniqueId());
        return true;
    }

    boolean stop(Player observer, String reason) {
        UUID observerId = observer.getUniqueId();
        Session session = sessions.remove(observerId);
        if (session == null) return false;

        // Stop translating packets before sending the return teleport. Otherwise
        // the client would receive the saved server coordinates in the shifted
        // coordinate space and could be left at an invalid client-side position.
        observer.setSpectatorTarget(null);
        if (!observer.teleport(session.snapshot().location())) {
            // Fail closed: keep the session active and the moderator hidden.
            sessions.put(observerId, session);
            observer.setGameMode(GameMode.SPECTATOR);
            observer.setAllowFlight(true);
            observer.setFlying(true);
            plugin.getLogger().severe("Admin-Spectator-Rückteleport fehlgeschlagen; Spieler bleibt verborgen: " + observerId);
            audit("STOP_FAILED (" + reason + ")", observerId, session.target());
            return false;
        }

        Snapshot s = session.snapshot();
        observer.setGameMode(s.gameMode());
        observer.setAllowFlight(s.allowFlight());
        observer.setFlying(s.flying() && s.allowFlight());
        observer.setInvulnerable(s.invulnerable());
        observer.setFlySpeed(s.flySpeed());
        observer.setWalkSpeed(s.walkSpeed());
        observer.setViewDistance(s.viewDistance());
        observer.setSendViewDistance(s.sendViewDistance());

        // A permanent Spectator account must remain in Spectator after restoration.
        enforcePermanent(observer);
        // Reveal only after the unshifted return teleport succeeded.
        showObserverToAll(observer);
        audit("STOP (" + reason + ")", observerId, session.target());
        return true;
    }

    void addPermanent(UUID uuid) {
        permanent.add(uuid);
        plugin.getConfig().set("admin-spectator.permanent-players", permanent.stream().map(UUID::toString).sorted().toList());
        plugin.saveConfig();
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) enforcePermanent(player);
        audit("PERMANENT_ADD", uuid, uuid);
    }

    void removePermanent(UUID uuid) {
        permanent.remove(uuid);
        plugin.getConfig().set("admin-spectator.permanent-players", permanent.stream().map(UUID::toString).sorted().toList());
        plugin.saveConfig();
        audit("PERMANENT_REMOVE", uuid, uuid);
    }

    private void enforcePermanent(Player player) {
        if (!permanent.contains(player.getUniqueId())) return;
        if (player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
        if (!player.getAllowFlight()) player.setAllowFlight(true);
        if (!player.isFlying()) player.setFlying(true);
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

    @EventHandler
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

        // Enforce permanent Spectator immediately rather than one tick later.
        enforcePermanent(joining);
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (permanent.contains(event.getPlayer().getUniqueId()) && event.getNewGameMode() != GameMode.SPECTATOR) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Dein Account ist dauerhaft auf Spectator festgelegt.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player quitter = event.getPlayer();
        Session own = sessions.get(quitter.getUniqueId());
        if (own != null) stop(quitter, "Moderator hat den Server verlassen");
        for (Map.Entry<UUID, Session> entry : Set.copyOf(sessions.entrySet())) {
            if (entry.getValue().target().equals(quitter.getUniqueId())) {
                Player observer = Bukkit.getPlayer(entry.getKey());
                if (observer != null) stop(observer, "Zielspieler hat den Server verlassen");
            }
        }
    }

    private void translateDoubleCoordinates(PacketEvent event, int offsetX, int offsetZ, int xIndex, int zIndex) {
        var doubles = event.getPacket().getDoubles();
        if (doubles.size() <= Math.max(xIndex, zIndex)) {
            throw new IllegalStateException("Paket enthält nicht die erwarteten Koordinatenfelder: " + event.getPacketType());
        }
        doubles.write(xIndex, doubles.read(xIndex) - offsetX);
        doubles.write(zIndex, doubles.read(zIndex) - offsetZ);
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
                PacketType.Play.Server.SPAWN_ENTITY,
                PacketType.Play.Server.ENTITY_TELEPORT,
                PacketType.Play.Server.POSITION,
                PacketType.Play.Server.EXPLOSION,
                PacketType.Play.Server.WORLD_PARTICLES,
                PacketType.Play.Server.WORLD_EVENT,
                PacketType.Play.Server.NAMED_SOUND_EFFECT,
                PacketType.Play.Server.SET_BORDER_CENTER,
                PacketType.Play.Server.INITIALIZE_BORDER,
                PacketType.Play.Server.VEHICLE_MOVE,
                PacketType.Play.Client.BLOCK_DIG,
                PacketType.Play.Client.USE_ITEM_ON,
                PacketType.Play.Client.POSITION,
                PacketType.Play.Client.POSITION_LOOK) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Session session = sessions.get(event.getPlayer().getUniqueId());
                if (session == null) return;
                try {
                    PacketType type = event.getPacketType();
                    if (type == PacketType.Play.Server.MAP_CHUNK
                            || type == PacketType.Play.Server.UNLOAD_CHUNK
                            || type == PacketType.Play.Server.LIGHT_UPDATE
                            || type == PacketType.Play.Server.VIEW_CENTRE) {
                        event.getPacket().getIntegers().write(0, event.getPacket().getIntegers().read(0) - session.offsetX() / 16);
                        event.getPacket().getIntegers().write(1, event.getPacket().getIntegers().read(1) - session.offsetZ() / 16);
                    } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
                        var section = event.getPacket().getSectionPositions().read(0);
                        event.getPacket().getSectionPositions().write(0,
                                new com.comphenix.protocol.wrappers.BlockPosition(
                                        section.getX() - session.offsetX() / 16,
                                        section.getY(),
                                        section.getZ() - session.offsetZ() / 16));
                    } else if (type == PacketType.Play.Server.BLOCK_CHANGE
                            || type == PacketType.Play.Server.TILE_ENTITY_DATA
                            || type == PacketType.Play.Server.BLOCK_ACTION
                            || type == PacketType.Play.Server.BLOCK_BREAK_ANIMATION
                            || type == PacketType.Play.Server.SPAWN_POSITION) {
                        translateBlockPosition(event, -session.offsetX(), -session.offsetZ());
                    } else if (type == PacketType.Play.Server.SPAWN_ENTITY
                            || type == PacketType.Play.Server.ENTITY_TELEPORT
                            || type == PacketType.Play.Server.POSITION
                            || type == PacketType.Play.Server.EXPLOSION
                            || type == PacketType.Play.Server.WORLD_PARTICLES
                            || type == PacketType.Play.Server.VEHICLE_MOVE) {
                        translateDoubleCoordinates(event, session.offsetX(), session.offsetZ(), 0, 2);
                    } else if (type == PacketType.Play.Server.WORLD_EVENT) {
                        translateBlockPosition(event, -session.offsetX(), -session.offsetZ());
                    } else if (type == PacketType.Play.Server.NAMED_SOUND_EFFECT) {
                        // Sound packet coordinates use fixed-point block coordinates multiplied by eight.
                        var ints = event.getPacket().getIntegers();
                        if (ints.size() >= 3) {
                            ints.write(0, ints.read(0) - session.offsetX() * 8);
                            ints.write(2, ints.read(2) - session.offsetZ() * 8);
                        }
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

            @Override
            public void onPacketReceiving(PacketEvent event) {
                Session session = sessions.get(event.getPlayer().getUniqueId());
                if (session == null) return;
                try {
                    var modifier = event.getPacket().getBlockPositionModifier();
                    if (modifier.size() > 0) {
                        var pos = modifier.read(0);
                        modifier.write(0, new com.comphenix.protocol.wrappers.BlockPosition(
                                pos.getX() + session.offsetX(), pos.getY(), pos.getZ() + session.offsetZ()));
                    }
                    // The client sends its shifted player coordinates back to the server.
                    if ((event.getPacketType() == PacketType.Play.Client.POSITION
                            || event.getPacketType() == PacketType.Play.Client.POSITION_LOOK)
                            && event.getPacket().getDoubles().size() >= 3) {
                        event.getPacket().getDoubles().write(0, event.getPacket().getDoubles().read(0) + session.offsetX());
                        event.getPacket().getDoubles().write(2, event.getPacket().getDoubles().read(2) + session.offsetZ());
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("Konnte eingehendes Interaktionspaket nicht transformieren: " + ex.getMessage());
                    event.setCancelled(true);
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
        for (UUID uuid : Set.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) stop(player, "Plugin wird deaktiviert");
        }
        if (packetListenerRegistered) protocol.removePacketListeners(plugin);
    }
}
