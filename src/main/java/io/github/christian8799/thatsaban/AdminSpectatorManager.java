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

    boolean observe(Player observer, Player target) {
        if (observer.equals(target)) return false;
        if (sessions.containsKey(observer.getUniqueId())) stop(observer, "Neue Beobachtung gestartet");
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
        observer.setGameMode(GameMode.SPECTATOR);
        observer.setAllowFlight(true);
        observer.setFlying(true);
        observer.setInvulnerable(true);
        observer.setViewDistance(Math.min(4, observer.getViewDistance()));
        observer.setSendViewDistance(Math.min(4, observer.getSendViewDistance()));
        target.hidePlayer(plugin, observer);
        observer.teleport(target.getLocation());
        observer.setSpectatorTarget(target);
        audit("START", observer.getUniqueId(), target.getUniqueId());
        return true;
    }

    boolean stop(Player observer, String reason) {
        Session session = sessions.remove(observer.getUniqueId());
        if (session == null) return false;
        observer.setSpectatorTarget(null);
        Player target = Bukkit.getPlayer(session.target());
        if (target != null) target.showPlayer(plugin, observer);
        Snapshot s = session.snapshot();
        observer.teleport(s.location());
        observer.setGameMode(s.gameMode());
        observer.setAllowFlight(s.allowFlight());
        observer.setFlying(s.flying() && s.allowFlight());
        observer.setInvulnerable(s.invulnerable());
        observer.setFlySpeed(s.flySpeed());
        observer.setWalkSpeed(s.walkSpeed());
        observer.setViewDistance(s.viewDistance());
        observer.setSendViewDistance(s.sendViewDistance());
        audit("STOP (" + reason + ")", observer.getUniqueId(), session.target());
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

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> enforcePermanent(event.getPlayer()));
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

    private void registerPacketTranslation() {
        protocol.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.MAP_CHUNK,
                PacketType.Play.Server.BLOCK_CHANGE,
                PacketType.Play.Server.SPAWN_ENTITY,
                PacketType.Play.Server.ENTITY_TELEPORT,
                PacketType.Play.Server.POSITION,
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
                    if (type == PacketType.Play.Server.MAP_CHUNK) {
                        event.getPacket().getIntegers().write(0, event.getPacket().getIntegers().read(0) - session.offsetX() / 16);
                        event.getPacket().getIntegers().write(1, event.getPacket().getIntegers().read(1) - session.offsetZ() / 16);
                    } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
                        var pos = event.getPacket().getBlockPositionModifier().read(0);
                        event.getPacket().getBlockPositionModifier().write(0,
                                new com.comphenix.protocol.wrappers.BlockPosition(pos.getX() - session.offsetX(),
                                        pos.getY(), pos.getZ() - session.offsetZ()));
                    } else if (type == PacketType.Play.Server.SPAWN_ENTITY
                            || type == PacketType.Play.Server.ENTITY_TELEPORT
                            || type == PacketType.Play.Server.POSITION) {
                        if (event.getPacket().getDoubles().size() >= 3) {
                            event.getPacket().getDoubles().write(0, event.getPacket().getDoubles().read(0) - session.offsetX());
                            event.getPacket().getDoubles().write(2, event.getPacket().getDoubles().read(2) - session.offsetZ());
                        }
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
