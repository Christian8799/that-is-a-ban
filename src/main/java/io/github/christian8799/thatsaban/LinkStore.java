package io.github.christian8799.thatsaban;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Speichert, welcher Account von welcher IP kam. IPs werden nur als gesalzener SHA-256-Hash abgelegt.
 * IPv6 wird auf das /64-Praefix gekuerzt. Alle Methoden sind thread-safe.
 */
final class LinkStore {

    private final ThatsABan plugin;
    private final File file;

    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Set<String>> ipsByPlayer = new HashMap<>();
    private final Map<String, Set<UUID>> playersByIp = new HashMap<>();
    private final Set<String> layer2Ips = new HashSet<>();
    private final Set<UUID> offenders = new HashSet<>();

    private byte[] salt;
    private boolean dirty;

    LinkStore(ThatsABan plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    synchronized void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        String savedSalt = yaml.getString("salt");
        if (savedSalt == null) {
            salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            dirty = true;
        } else {
            salt = HexFormat.of().parseHex(savedSalt);
        }

        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                String name = players.getString(key + ".name");
                if (name != null) {
                    names.put(uuid, name);
                }
                for (String hash : players.getStringList(key + ".ips")) {
                    link(uuid, hash);
                }
            }
        }
        layer2Ips.addAll(yaml.getStringList("layer2-ips"));
        for (String id : yaml.getStringList("offenders")) {
            try {
                offenders.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // kaputten Eintrag ueberspringen
            }
        }
    }

    synchronized void save() {
        if (!dirty && file.exists()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("salt", HexFormat.of().formatHex(salt));
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            String base = "players." + entry.getKey();
            yaml.set(base + ".name", entry.getValue());
            yaml.set(base + ".ips", new ArrayList<>(ipsByPlayer.getOrDefault(entry.getKey(), Set.of())));
        }
        yaml.set("layer2-ips", new ArrayList<>(layer2Ips));
        yaml.set("offenders", offenders.stream().map(UUID::toString).toList());
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("data.yml konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }

    void startAutosave() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::save, 600L, 600L);
    }

    synchronized String hash(InetAddress address) {
        byte[] raw = address.getAddress();
        if (raw.length == 16) {
            raw = Arrays.copyOf(raw, 8); // IPv6: /64
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return HexFormat.of().formatHex(digest.digest(raw));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    synchronized void record(UUID uuid, String name, String ipHash) {
        names.put(uuid, name);
        link(uuid, ipHash);
        dirty = true;
    }

    synchronized boolean isOffender(UUID uuid) {
        return offenders.contains(uuid);
    }

    synchronized boolean isLayer2Ip(String ipHash) {
        return layer2Ips.contains(ipHash);
    }

    synchronized void flagIp(String ipHash) {
        if (layer2Ips.add(ipHash)) {
            dirty = true;
        }
    }

    /**
     * Merkt den Spieler als "Ausloeser", markiert alle seine bekannten IPs
     * und gibt alle anderen bekannten Accounts dieser IPs zurueck.
     */
    synchronized Set<UUID> markOffender(UUID uuid) {
        offenders.add(uuid);
        Set<UUID> others = new HashSet<>();
        for (String hash : ipsByPlayer.getOrDefault(uuid, Set.of())) {
            layer2Ips.add(hash);
            others.addAll(playersByIp.getOrDefault(hash, Set.of()));
        }
        others.remove(uuid);
        dirty = true;
        return others;
    }

    synchronized UUID uuidByName(String name) {
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    synchronized String nameOf(UUID uuid) {
        return names.get(uuid);
    }

    private void link(UUID uuid, String ipHash) {
        ipsByPlayer.computeIfAbsent(uuid, k -> new HashSet<>()).add(ipHash);
        playersByIp.computeIfAbsent(ipHash, k -> new HashSet<>()).add(uuid);
    }
}
