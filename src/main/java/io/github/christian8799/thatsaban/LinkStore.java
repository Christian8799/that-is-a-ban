package io.github.christian8799.thatsaban;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Paths;
import java.nio.file.InvalidPathException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class LinkStore {

    record PlayerSnapshot(String name, int linkedIpCount, boolean offender, boolean layer2Tracked, boolean hasStoredData) {}

    record NetworkEntry(UUID uuid, String name, UUID connectedVia, int depth, int sharedIpCount, long lastSeen) {}
    record NetworkSnapshot(java.util.List<NetworkEntry> accounts, boolean truncated) {}

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final ThatsABan plugin;
    private final File file;
    private final File keyFile;
    private final boolean invalidKeyPath;

    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Set<String>> ipsByPlayer = new HashMap<>();
    private final Map<String, Set<UUID>> playersByIp = new HashMap<>();
    private final Set<String> layer2Ips = new HashSet<>();
    private final Map<String, Long> ipLastSeen = new HashMap<>();
    private final Map<UUID, Long> offenders = new HashMap<>();
    private final Map<UUID, Long> layer2LastSeen = new HashMap<>();
    private final Set<UUID> expiredOffenders = new HashSet<>();

    private byte[] hmacKey;
    private boolean dirty;
    private boolean writeBlocked;
    private long lastPrune;

    LinkStore(ThatsABan plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");

        String configuredKeyPath = System.getenv("THATSABAN_HMAC_KEY_PATH");
        if (configuredKeyPath == null || configuredKeyPath.isBlank()) {
            configuredKeyPath = plugin.getConfig().getString("privacy.hmac-key-path", "");
        }
        File resolvedKeyFile = new File(plugin.getDataFolder(), "ip-hmac.key");
        boolean rejectedKeyPath = false;
        if (configuredKeyPath != null && !configuredKeyPath.isBlank()) {
            try {
                Path supplied = Paths.get(configuredKeyPath);
                Path requested = supplied.toAbsolutePath().normalize();
                Path pluginDirectory = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
                if (!supplied.isAbsolute() || requested.startsWith(pluginDirectory)) {
                    rejectedKeyPath = true;
                } else {
                    resolvedKeyFile = requested.toFile();
                }
            } catch (InvalidPathException ex) {
                rejectedKeyPath = true;
            }
        }
        this.keyFile = resolvedKeyFile;
        this.invalidKeyPath = rejectedKeyPath;
    }

    synchronized void load() {
        plugin.getDataFolder().mkdirs();
        if (file.exists() && !restrictOwnerOnly(file)) {
            writeBlocked = true;
        }
        boolean keyReady = loadHmacKey();

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            if (file.exists()) {
                yaml.load(file);
            }
        } catch (IOException | InvalidConfigurationException ex) {
            writeBlocked = true;
            plugin.getLogger().severe("data.yml is invalid. The file remains unchanged; "
                    + "writes to LinkStore are blocked until manual inspection.");
            return;
        }

        if (keyReady && file.exists() && yaml.getInt("hash-version", 0) == 1) {
            loadIpData(yaml);
        } else if (file.exists() && hasDiscardableIpData(yaml)) {
            dirty = true;
            plugin.getLogger().warning("Old IP links were discarded during the HMAC migration.");
        }

        ConfigurationSection offenderTimes = yaml.getConfigurationSection("offender-last-seen");
        if (offenderTimes != null) {
            for (String id : offenderTimes.getKeys(false)) {
                try {
                    long timestamp = offenderTimes.getLong(id, 0L);
                    if (timestamp > 0L) {
                        UUID uuid = UUID.fromString(id);
                        offenders.put(uuid, timestamp);
                        layer2LastSeen.put(uuid, timestamp);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Skip invalid UUID entry.
                }
            }
        }

        for (String id : yaml.getStringList("expired-layer2-markers")) {
            try {
                expiredOffenders.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // Skip invalid UUID entry.
            }
        }

        ConfigurationSection layer2Times = yaml.getConfigurationSection("layer2-last-seen");
        if (layer2Times != null) {
            for (String id : layer2Times.getKeys(false)) {
                try {
                    long timestamp = layer2Times.getLong(id, 0L);
                    if (timestamp > 0L) {
                        layer2LastSeen.put(UUID.fromString(id), timestamp);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Skip invalid UUID entry.
                }
            }
        }

        long now = System.currentTimeMillis();
        for (String id : yaml.getStringList("offenders")) {
            try {
                UUID uuid = UUID.fromString(id);
                if (!offenders.containsKey(uuid)) {
                    offenders.put(uuid, now);
                    layer2LastSeen.putIfAbsent(uuid, now);
                    dirty = true;
                }
            } catch (IllegalArgumentException ignored) {
                // Skip invalid UUID.
            }
        }
        for (UUID uuid : offenders.keySet()) {
            layer2LastSeen.putIfAbsent(uuid, offenders.get(uuid));
        }
        pruneExpired(now, true);
    }

    private boolean loadHmacKey() {
        if (invalidKeyPath) {
            writeBlocked = true;
            plugin.getLogger().severe("privacy.hmac-key-path bzw. THATSABAN_HMAC_KEY_PATH muss ein absoluter Pfad "
                    + "must be outside the plugin directory. IP processing and LinkStore writes are blocked.");
            return false;
        }
        try {
            File parent = keyFile.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create key directory");
            }
            if (keyFile.isFile()) {
                byte[] existing = Files.readAllBytes(keyFile.toPath());
                if (existing.length == 32) {
                    hmacKey = existing;
                    if (!restrictOwnerOnly(keyFile)) {
                        writeBlocked = true;
                        return false;
                    }
                    return true;
                }
                plugin.getLogger().warning("The HMAC key is invalid; old IP links will be discarded.");
            }

            hmacKey = new byte[32];
            new SecureRandom().nextBytes(hmacKey);
            Files.write(keyFile.toPath(), hmacKey, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (!restrictOwnerOnly(keyFile)) {
                writeBlocked = true;
                return false;
            }
            dirty = true;
            return false;
        } catch (IOException | SecurityException ex) {
            writeBlocked = true;
            plugin.getLogger().severe("The separate HMAC key could not be loaded or created securely. "
                    + "IP-Verarbeitung und Schreibzugriffe sind blockiert.");
            return false;
        }
    }

    private void loadIpData(YamlConfiguration yaml) {
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

    private boolean restrictOwnerOnly(File target) {
        try {
            Files.setPosixFilePermissions(target.toPath(), OWNER_ONLY);
            return true;
        } catch (UnsupportedOperationException ex) {
            boolean ok = target.setReadable(false, false)
                    && target.setWritable(false, false)
                    && target.setExecutable(false, false)
                    && target.setReadable(true, true)
                    && target.setWritable(true, true);
            if (!ok) {
                plugin.getLogger().severe("Could not set file permissions to owner-only: "
                        + target.getName() + ". Schreibzugriffe werden blockiert.");
            }
            return ok;
        } catch (IOException | SecurityException ex) {
            plugin.getLogger().severe("Could not set file permissions to owner-only: "
                    + target.getName() + ". Schreibzugriffe werden blockiert.");
            return false;
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
        yaml.set("hash-version", 1);
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            String base = "players." + entry.getKey();
            yaml.set(base + ".name", entry.getValue());
            yaml.set(base + ".ips", new ArrayList<>(ipsByPlayer.getOrDefault(entry.getKey(), Set.of())));
        }
        yaml.set("layer2-ips", new ArrayList<>(layer2Ips));
        yaml.set("ip-last-seen", new HashMap<>(ipLastSeen));
        yaml.set("offenders", offenders.keySet().stream().map(UUID::toString).toList());
        yaml.set("layer2-last-seen", new HashMap<>(layer2LastSeen));
        yaml.set("expired-layer2-markers",
                expiredOffenders.stream().map(UUID::toString).toList());
        for (Map.Entry<UUID, Long> entry : offenders.entrySet()) {
            yaml.set("offender-last-seen." + entry.getKey(), entry.getValue());
        }
        Path temporaryFile = null;
        try {
            Path parent = file.toPath().toAbsolutePath().getParent();
            if (parent == null) {
                throw new IOException("LinkStore directory is unavailable.");
            }
            Files.createDirectories(parent);
            temporaryFile = Files.createTempFile(parent, "data.yml.tmp-", ".yml");
            yaml.save(temporaryFile.toFile());

            // Protect the temporary file before it replaces the existing store.
            if (!restrictOwnerOnly(temporaryFile.toFile())) {
                writeBlocked = true;
                return;
            }
            try {
                Files.move(temporaryFile, file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporaryFile, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (!restrictOwnerOnly(file)) {
                writeBlocked = true;
                return;
            }
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save data.yml: "
                    + ex.getClass().getSimpleName());
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // A leftover temporary file is harmless and is never treated as the active store.
                }
            }
        }
    }

    void startAutosave() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::save, 600L, 600L);
    }

    synchronized String hash(InetAddress address) {
        if (writeBlocked || hmacKey == null) {
            throw new IllegalStateException("HMAC key unavailable");
        }
        try {
            byte[] raw = address.getAddress();
            if (raw.length == 16) {
                raw = java.util.Arrays.copyOf(raw, 8);
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(raw));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA-256 unavailable", ex);
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

    synchronized boolean isWriteBlocked() {
        return writeBlocked;
    }

    synchronized boolean isOffender(UUID uuid) {
        return offenders.containsKey(uuid);
    }

    synchronized void renewOffender(UUID uuid) {
        if (offenders.containsKey(uuid)) {
            long now = System.currentTimeMillis();
            offenders.put(uuid, now);
            layer2LastSeen.put(uuid, now);
            dirty = true;
        }
    }

    synchronized Set<UUID> markOffender(UUID uuid) {
        long now = System.currentTimeMillis();
        pruneExpired(now, false);
        offenders.put(uuid, now);
        layer2LastSeen.put(uuid, now);
        expiredOffenders.remove(uuid);
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

    synchronized void trackLayer2(UUID uuid) {
        layer2LastSeen.put(uuid, System.currentTimeMillis());
        expiredOffenders.remove(uuid);
        dirty = true;
    }

    synchronized void renewLayer2(UUID uuid) {
        if (layer2LastSeen.containsKey(uuid)) {
            layer2LastSeen.put(uuid, System.currentTimeMillis());
            dirty = true;
        }
    }

    synchronized boolean hasLayer2Tracking(UUID uuid) {
        return layer2LastSeen.containsKey(uuid);
    }

    synchronized void unmarkOffender(UUID uuid) {
        boolean changed = offenders.remove(uuid) != null;
        changed |= layer2LastSeen.remove(uuid) != null;
        changed |= removeOrphanedLayer2Flags(uuid);
        changed |= expiredOffenders.remove(uuid);
        if (changed) {
            dirty = true;
        }
    }

    synchronized int forgetPlayer(UUID uuid) {
        Set<String> hashes = new HashSet<>(ipsByPlayer.getOrDefault(uuid, Set.of()));
        ipsByPlayer.remove(uuid);
        names.remove(uuid);
        offenders.remove(uuid);
        layer2LastSeen.remove(uuid);
        expiredOffenders.remove(uuid);
        for (String hash : hashes) {
            Set<UUID> linked = playersByIp.get(hash);
            if (linked != null) {
                linked.remove(uuid);
                if (linked.isEmpty()) {
                    playersByIp.remove(hash);
                    layer2Ips.remove(hash);
                    ipLastSeen.remove(hash);
                } else if (linked.stream().noneMatch(offenders::containsKey)) {
                    layer2Ips.remove(hash);
                }
            } else {
                layer2Ips.remove(hash);
                ipLastSeen.remove(hash);
            }
        }
        dirty = true;
        return hashes.size();
    }

    /**
     * Builds a bounded account graph from currently retained shared-IP HMACs.
     * The edge is evidence of a shared network address, not proof of shared identity.
     */
    synchronized NetworkSnapshot network(UUID start, int maxDepth, int maxAccounts) {
        pruneExpired(System.currentTimeMillis(), false);
        record Visit(UUID uuid, int depth) {}
        java.util.ArrayDeque<Visit> queue = new java.util.ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        java.util.List<NetworkEntry> result = new ArrayList<>();
        queue.add(new Visit(start, 0));
        visited.add(start);
        boolean truncated = false;

        while (!queue.isEmpty()) {
            Visit current = queue.removeFirst();
            if (current.depth() >= maxDepth) {
                continue;
            }
            Map<UUID, Long> neighbors = new HashMap<>();
            Map<UUID, Integer> sharedCounts = new HashMap<>();
            for (String hash : ipsByPlayer.getOrDefault(current.uuid(), Set.of())) {
                long seen = ipLastSeen.getOrDefault(hash, 0L);
                for (UUID other : playersByIp.getOrDefault(hash, Set.of())) {
                    if (other.equals(current.uuid()) || visited.contains(other)) {
                        continue;
                    }
                    sharedCounts.merge(other, 1, Integer::sum);
                    neighbors.merge(other, seen, Math::max);
                }
            }
            java.util.List<UUID> ordered = neighbors.keySet().stream()
                    .sorted((a, b) -> {
                        int byCount = Integer.compare(sharedCounts.getOrDefault(b, 0),
                                sharedCounts.getOrDefault(a, 0));
                        return byCount != 0 ? byCount : a.toString().compareTo(b.toString());
                    }).toList();
            for (UUID other : ordered) {
                if (result.size() >= maxAccounts) {
                    truncated = true;
                    break;
                }
                visited.add(other);
                int depth = current.depth() + 1;
                result.add(new NetworkEntry(other, names.get(other), current.uuid(), depth,
                        sharedCounts.getOrDefault(other, 0), neighbors.getOrDefault(other, 0L)));
                queue.addLast(new Visit(other, depth));
            }
            if (truncated) {
                break;
            }
        }
        return new NetworkSnapshot(java.util.List.copyOf(result), truncated);
    }

    synchronized PlayerSnapshot snapshot(UUID uuid) {
        Set<String> hashes = ipsByPlayer.getOrDefault(uuid, Set.of());
        String name = names.get(uuid);
        boolean offender = offenders.containsKey(uuid);
        boolean layer2Tracked = layer2LastSeen.containsKey(uuid);
        return new PlayerSnapshot(name, hashes.size(), offender, layer2Tracked,
                name != null || !hashes.isEmpty() || offender || layer2Tracked);
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

    synchronized UUID uuidByName(String name) {
        if (name == null) {
            return null;
        }
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

    synchronized List<String> knownPlayerNames(String prefix) {
        String normalizedPrefix = prefix == null ? "" : prefix.toLowerCase(java.util.Locale.ROOT);
        return names.values().stream()
                .filter(name -> name != null && name.toLowerCase(java.util.Locale.ROOT).startsWith(normalizedPrefix))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    synchronized List<String> offenderNames(String prefix) {
        String normalizedPrefix = prefix == null ? "" : prefix.toLowerCase(java.util.Locale.ROOT);
        return offenders.keySet().stream()
                .map(uuid -> names.getOrDefault(uuid, uuid.toString()))
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(normalizedPrefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    synchronized Set<UUID> pendingExpiredOffenders() {
        return new HashSet<>(expiredOffenders);
    }

    synchronized void completeExpiredOffender(UUID uuid) {
        if (expiredOffenders.remove(uuid)) {
            dirty = true;
        }
    }

    private boolean removeOrphanedLayer2Flags(UUID uuid) {
        boolean changed = false;
        for (String hash : ipsByPlayer.getOrDefault(uuid, Set.of())) {
            boolean stillOffender = playersByIp.getOrDefault(hash, Set.of()).stream()
                    .filter(other -> !other.equals(uuid))
                    .anyMatch(offenders::containsKey);
            if (!stillOffender) {
                changed |= layer2Ips.remove(hash);
            }
        }
        return changed;
    }

    private void pruneExpired(long now, boolean force) {
        if (!force && now - lastPrune < 60_000L) {
            return;
        }
        lastPrune = now;

        long retentionDays = Math.min(3650L,
                Math.max(1L, plugin.getConfig().getLong("alt-detection.retention-days", 30L)));
        long cutoff = now - retentionDays * 24L * 60L * 60L * 1000L;

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

        long offenderRetentionDays = Math.max(1L, Math.min(3650L,
                plugin.getConfig().getLong("alt-detection.offender-retention-days", 365L)));
        long offenderCutoff = now - offenderRetentionDays * 24L * 60L * 60L * 1000L;
        for (Map.Entry<UUID, Long> entry : new HashMap<>(layer2LastSeen).entrySet()) {
            if (entry.getValue() <= offenderCutoff) {
                UUID expiredUuid = entry.getKey();
                layer2LastSeen.remove(expiredUuid);
                boolean wasOffender = offenders.remove(expiredUuid) != null;
                expiredOffenders.add(expiredUuid);
                if (wasOffender) {
                    removeOrphanedLayer2Flags(expiredUuid);
                }
                dirty = true;
            }
        }
    }

    private void link(UUID uuid, String ipHash) {
        ipsByPlayer.computeIfAbsent(uuid, k -> new HashSet<>()).add(ipHash);
        playersByIp.computeIfAbsent(ipHash, k -> new HashSet<>()).add(uuid);
    }
}
