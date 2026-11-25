package io.github.christian8799.thatsaban;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
 * Speichert IP-Verknuepfungen ausschliesslich als gesalzene SHA-256-Hashes.
 * Hashes und zugehoerige Spielerreferenzen werden nach der konfigurierten Frist geloescht.
 */
final class LinkStore {

    private final ThatsABan plugin;
    private final File file;

    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Set<String>> ipsByPlayer = new HashMap<>();
    private final Map<String, Set<UUID>> playersByIp = new HashMap<>();
    private final Set<String> layer2Ips = new HashSet<>();
    private final Map<String, Long> ipLastSeen = new HashMap<>();
    private final Map<UUID, Long> offenders = new HashMap<>();

    private byte[] salt;
    private boolean dirty;
    private boolean writeBlocked;
    private long lastPrune;

    LinkStore(ThatsABan plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    synchronized void load() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            if (file.exists()) {
                yaml.load(file);
            }
        } catch (IOException | InvalidConfigurationException ex) {
            File backup = createBackup("corrupt");
            if (backup == null) {
                writeBlocked = true;
                salt = new byte[16];
                new SecureRandom().nextBytes(salt);
                plugin.getLogger().severe("data.yml ist fehlerhaft und konnte nicht gesichert werden. "
                        + "Schreibzugriffe sind blockiert, damit die Originaldatei nicht überschrieben wird.");
            } else {
                salt = new byte[16];
                new SecureRandom().nextBytes(salt);
                dirty = true;
                plugin.getLogger().warning("data.yml enthaelt ungueltiges YAML und wurde gesichert unter "
                        + backup.getAbsolutePath() + ". Der LinkStore startet leer; "
                        + "Offender-Markierungen konnten aus der beschaedigten Datei nicht geladen werden. "
                        + "Ursache: " + ex.getMessage());
            }
            return;
        }

        String savedSalt = yaml.getString("salt");
        boolean validSalt = false;
        if (savedSalt != null) {
            try {
                byte[] parsed = HexFormat.of().parseHex(savedSalt);
                if (parsed.length == 16) {
                    salt = parsed;
                    validSalt = true;
                }
            } catch (IllegalArgumentException ignored) {
                // Wird unten kontrolliert mit einem neuen Salt wiederhergestellt.
            }
        }

        if (!validSalt) {
            if (file.exists() && hasDiscardableIpData(yaml)) {
                File backup = createBackup("bak");
                if (backup == null) {
                    writeBlocked = true;
                    plugin.getLogger().severe("Salt in data.yml fehlt oder ist ungueltig und IP-Daten "
                            + "konnten nicht gesichert werden. Schreibzugriffe sind blockiert, "
                            + "damit die Originaldatei nicht ueberschrieben wird.");
                } else {
                    plugin.getLogger().warning("Salt in data.yml fehlt oder ist ungueltig. "
                        + "Die zugehoerigen IP-Links und Layer-2-IP-Markierungen werden verworfen. "
                            + "Backup: " + backup.getAbsolutePath());
                }
            }

            salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            dirty = true;
            if (savedSalt != null) {
                plugin.getLogger().warning("data.yml enthaelt einen ungueltigen Salt. "
                        + "Gespeicherte IP-Verknuepfungen werden verworfen.");
            } else if (file.exists()) {
                plugin.getLogger().warning("Salt fehlt in data.yml. Gespeicherte IP-Verknuepfungen "
                        + "werden verworfen.");
            }
        }

        if (validSalt) {
            ConfigurationSection seen = yaml.getConfigurationSection("ip-last-seen");
            if (seen != null) {
                for (String hash : seen.getKeys(false)) {
                    long timestamp = seen.getLong(hash, 0L);
                    if (timestamp > 0L) {
                        ipLastSeen.put(hash, timestamp);
                    }
                }
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

            // Alte Datenstände hatten nicht für jeden Hash einen Zeitstempel.
            long now = System.currentTimeMillis();
            for (Set<String> hashes : ipsByPlayer.values()) {
                for (String hash : hashes) {
                    if (!ipLastSeen.containsKey(hash)) {
                        ipLastSeen.put(hash, now);
                        dirty = true;
                    }
                }
            }
            for (String hash : layer2Ips) {
                if (!ipLastSeen.containsKey(hash)) {
                    ipLastSeen.put(hash, now);
                    dirty = true;
                }
            }
        }

        ConfigurationSection offenderTimes = yaml.getConfigurationSection("offender-last-seen");
        if (offenderTimes != null) {
            for (String id : offenderTimes.getKeys(false)) {
                try {
                    long timestamp = offenderTimes.getLong(id, 0L);
                    if (timestamp > 0L) {
                        offenders.put(UUID.fromString(id), timestamp);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Kaputten Eintrag ueberspringen.
                }
            }
        }

        long now = System.currentTimeMillis();
        for (String id : yaml.getStringList("offenders")) {
            try {
                UUID uuid = UUID.fromString(id);
                if (!offenders.containsKey(uuid)) {
                    offenders.put(uuid, now);
                    dirty = true;
                }
            } catch (IllegalArgumentException ignored) {
                // Ungültige UUID überspringen.
            }
        }

        pruneExpired(now, true);
    }

    private boolean hasDiscardableIpData(YamlConfiguration yaml) {
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                if (!players.getStringList(key + ".ips").isEmpty()) {
                    return true;
                }
            }
        }
        return !yaml.getStringList("layer2-ips").isEmpty()
                || (yaml.getConfigurationSection("ip-last-seen") != null
                && !yaml.getConfigurationSection("ip-last-seen").getKeys(false).isEmpty());
    }

    private File createBackup(String suffix) {
        if (!file.exists()) {
            return null;
        }
        long timestamp = System.currentTimeMillis();
        File backup;
        do {
            backup = new File(file.getParentFile(), file.getName() + "." + suffix + "-" + timestamp++);
        } while (backup.exists());

        try {
            Files.copy(file.toPath(), backup.toPath());
            plugin.getLogger().warning("Sicherung von data.yml erstellt: " + backup.getAbsolutePath());
            return backup;
        } catch (IOException ex) {
            plugin.getLogger().severe("data.yml konnte nicht nach " + backup.getAbsolutePath()
                    + " gesichert werden: " + ex.getMessage());
            return null;
        }
    }

    synchronized void save() {
        if (writeBlocked) {
            return;
        }
        pruneExpired(System.currentTimeMillis(), true);
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
        yaml.set("ip-last-seen", new HashMap<>(ipLastSeen));
        yaml.set("offenders", offenders.keySet().stream().map(UUID::toString).toList());
        for (Map.Entry<UUID, Long> entry : offenders.entrySet()) {
            yaml.set("offender-last-seen." + entry.getKey(), entry.getValue());
        }
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
        long now = System.currentTimeMillis();
        pruneExpired(now, false);
        names.put(uuid, name);
        link(uuid, ipHash);
        ipLastSeen.put(ipHash, now);
        dirty = true;
    }

    synchronized boolean isOffender(UUID uuid) {
        return offenders.containsKey(uuid);
    }

    synchronized void renewOffender(UUID uuid) {
        if (offenders.containsKey(uuid)) {
            offenders.put(uuid, System.currentTimeMillis());
            dirty = true;
        }
    }

    synchronized boolean isLayer2Ip(String ipHash) {
        return layer2Ips.contains(ipHash);
    }

    synchronized void flagIp(String ipHash) {
        long now = System.currentTimeMillis();
        layer2Ips.add(ipHash);
        ipLastSeen.put(ipHash, now);
        dirty = true;
    }

    synchronized Set<UUID> markOffender(UUID uuid) {
        long now = System.currentTimeMillis();
        pruneExpired(now, false);
        offenders.put(uuid, now);
        Set<UUID> others = new HashSet<>();
        for (String hash : ipsByPlayer.getOrDefault(uuid, Set.of())) {
            layer2Ips.add(hash);
            ipLastSeen.put(hash, now);
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

    private void pruneExpired(long now, boolean force) {
        if (!force && now - lastPrune < 60_000L) {
            return;
        }
        lastPrune = now;

        long retentionDays = Math.min(3650L,
                Math.max(1L, plugin.getConfig().getLong("alt-detection.retention-days", 30L)));
        long retentionMillis = retentionDays * 24L * 60L * 60L * 1000L;
        long cutoff = now - retentionMillis;

        Set<String> allHashes = new HashSet<>(ipLastSeen.keySet());
        allHashes.addAll(layer2Ips);
        for (Set<String> hashes : ipsByPlayer.values()) {
            allHashes.addAll(hashes);
        }

        Set<String> expired = new HashSet<>();
        for (String hash : allHashes) {
            Long lastSeen = ipLastSeen.get(hash);
            if (lastSeen == null || lastSeen <= cutoff) {
                expired.add(hash);
            }
        }
        if (!expired.isEmpty()) {
            layer2Ips.removeAll(expired);
            ipLastSeen.keySet().removeAll(expired);
            for (Set<String> hashes : ipsByPlayer.values()) {
                hashes.removeAll(expired);
            }
            for (String hash : expired) {
                playersByIp.remove(hash);
            }
            dirty = true;
        }

        ipsByPlayer.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        names.keySet().removeIf(uuid -> !ipsByPlayer.containsKey(uuid));
        long offenderRetentionDays = plugin.getConfig().getLong("alt-detection.offender-retention-days", 0L);
        if (offenderRetentionDays > 0L) {
            offenderRetentionDays = Math.min(3650L, offenderRetentionDays);
            long offenderCutoff = now - offenderRetentionDays * 24L * 60L * 60L * 1000L;
            if (offenders.entrySet().removeIf(entry -> entry.getValue() <= offenderCutoff)) {
                dirty = true;
            }
        }
    }

    private void link(UUID uuid, String ipHash) {
        ipsByPlayer.computeIfAbsent(uuid, k -> new HashSet<>()).add(ipHash);
        playersByIp.computeIfAbsent(ipHash, k -> new HashSet<>()).add(uuid);
    }
}
