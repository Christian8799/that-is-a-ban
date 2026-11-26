package io.github.christian8799.thatsaban;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** Owns player report metadata and the rolling replay buffers used for evidence clips. */
final class ReportManager implements Listener {
    enum Status { CAPTURING, OPEN, FAILED, RESOLVED, DISMISSED }

    static final class ReportEntry {
        private final String id;
        private final UUID reporterId;
        private final String reporterName;
        private final UUID targetId;
        private final String targetName;
        private final String reason;
        private final long createdAt;
        private Status status;
        private String replayPath;

        ReportEntry(String id, UUID reporterId, String reporterName, UUID targetId,
                    String targetName, String reason, long createdAt, Status status, String replayPath) {
            this.id = id;
            this.reporterId = reporterId;
            this.reporterName = reporterName;
            this.targetId = targetId;
            this.targetName = targetName;
            this.reason = reason;
            this.createdAt = createdAt;
            this.status = status;
            this.replayPath = replayPath;
        }

        String id() { return id; }
        UUID reporterId() { return reporterId; }
        String reporterName() { return reporterName; }
        UUID targetId() { return targetId; }
        String targetName() { return targetName; }
        String reason() { return reason; }
        long createdAt() { return createdAt; }
        Status status() { return status; }
        String replayPath() { return replayPath; }
    }

    private final ThatsABan plugin;
    private final FlashbackClipIntegration clips;
    private final File storageFile;
    private final Map<String, ReportEntry> reports = new LinkedHashMap<>();
    private boolean storageWriteBlocked;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final java.util.Set<UUID> armedByThisPlugin = new java.util.HashSet<>();

    ReportManager(ThatsABan plugin, FlashbackClipIntegration clips) {
        this.plugin = plugin;
        this.clips = clips;
        this.storageFile = new File(plugin.getDataFolder(), "reports.yml");
        load();
    }

    void initialize() {
        if (!plugin.getConfig().getBoolean("reports.enabled", true)) {
            plugin.getLogger().info("Spieler-Reports sind in config.yml deaktiviert.");
            return;
        }
        if (!clips.isAvailable()) {
            plugin.getLogger().warning("Spieler-Reports deaktiviert: FlashbackServer fehlt oder seine Clip-API ist nicht verfügbar.");
            return;
        }

        int configuredWindow = clips.configuredWindowSeconds();
        int requiredWindow = Math.max(1,
                plugin.getConfig().getInt("reports.minimum-clip-window-seconds", 60));
        if (configuredWindow < requiredWindow) {
            plugin.getLogger().warning("FlashbackServer clips.window-seconds ist " + configuredWindow
                    + "; für mindestens " + requiredWindow + " Sekunden Report-Replay bitte "
                    + "plugins/FlashbackServer/config.yml entsprechend einstellen.");
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> armIfNeeded(player), 1L);
        }
        plugin.getLogger().info("Report-System initialisiert; FlashbackServer-Rolling-Clips werden beim Spielerbeitritt aktiviert.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("reports.enabled", true)) return;
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> armIfNeeded(event.getPlayer()), 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        armedByThisPlugin.remove(event.getPlayer().getUniqueId());
        // FlashbackServer also removes its own rolling buffer on quit.
    }

    private void armIfNeeded(Player player) {
        if (!player.isOnline() || !clips.isAvailable()) return;
        try {
            if (clips.isArmed(player)) return;
            if (clips.arm(player)) {
                armedByThisPlugin.add(player.getUniqueId());
            } else if (!clips.isArmed(player)) {
                plugin.getLogger().warning("Report-Clip-Puffer konnte für " + player.getName() + " nicht aktiviert werden.");
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Report-Clip-Puffer für " + player.getName()
                    + " fehlgeschlagen: " + ex.getClass().getSimpleName());
        }
    }

    boolean submit(Player reporter, Player target, String reason) {
        if (storageWriteBlocked) {
            reporter.sendMessage(Component.text("Reports sind wegen eines Speicherfehlers vorübergehend deaktiviert. Bitte informiere das Team.",
                    NamedTextColor.RED));
            return false;
        }
        if (!plugin.getConfig().getBoolean("reports.enabled", true)) {
            reporter.sendMessage(Component.text("Das Report-System ist deaktiviert.", NamedTextColor.RED));
            return false;
        }
        if (!clips.isAvailable()) {
            reporter.sendMessage(Component.text("Reports sind momentan nicht verfügbar.", NamedTextColor.RED));
            return false;
        }
        if (reporter.getUniqueId().equals(target.getUniqueId())) {
            reporter.sendMessage(Component.text("Du kannst dich nicht selbst melden.", NamedTextColor.RED));
            return false;
        }

        int maxLength = Math.max(20, plugin.getConfig().getInt("reports.max-reason-length", 200));
        if (reason.isBlank() || reason.length() > maxLength) {
            reporter.sendMessage(Component.text("Der Grund muss zwischen 1 und " + maxLength
                    + " Zeichen lang sein.", NamedTextColor.RED));
            return false;
        }

        int minimumWindow = Math.max(1,
                plugin.getConfig().getInt("reports.minimum-clip-window-seconds", 60));
        if (clips.configuredWindowSeconds() < minimumWindow) {
            reporter.sendMessage(Component.text("Das Replay-System ist noch nicht korrekt konfiguriert. Bitte informiere das Team.",
                    NamedTextColor.RED));
            return false;
        }
        try {
            if (!clips.isArmed(target)) {
                reporter.sendMessage(Component.text("Für diesen Spieler ist noch kein Replay-Puffer bereit. Bitte versuche es später erneut.",
                        NamedTextColor.RED));
                return false;
            }
        } catch (RuntimeException ex) {
            reporter.sendMessage(Component.text("Das Replay-System ist momentan nicht verfügbar.", NamedTextColor.RED));
            return false;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = Math.max(0,
                plugin.getConfig().getLong("reports.cooldown-seconds", 60L)) * 1000L;
        Long lastReport = cooldowns.get(reporter.getUniqueId());
        if (lastReport != null && now - lastReport < cooldownMillis) {
            long remaining = Math.max(1, (cooldownMillis - (now - lastReport) + 999) / 1000);
            reporter.sendMessage(Component.text("Bitte warte noch " + remaining
                    + " Sekunden, bevor du einen weiteren Spieler meldest.", NamedTextColor.RED));
            return false;
        }

        String id;
        do {
            id = UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
        } while (reports.containsKey(id));

        final String reportId = id;
        ReportEntry entry = new ReportEntry(reportId, reporter.getUniqueId(), reporter.getName(),
                target.getUniqueId(), target.getName(), reason, now, Status.CAPTURING, null);
        reports.put(reportId, entry);
        if (!save()) {
            reports.remove(reportId);
            reporter.sendMessage(Component.text("Der Report konnte nicht dauerhaft gespeichert werden. Bitte informiere das Team.",
                    NamedTextColor.RED));
            return false;
        }
        cooldowns.put(reporter.getUniqueId(), now);

        Path output = plugin.getDataFolder().toPath().resolve("reports")
                .resolve(reportId + "-" + safeFileName(target.getName()) + ".flashback");
        try {
            Files.createDirectories(output.getParent());
            clips.saveClip(target, output).whenComplete((path, error) -> {
                if (!plugin.isEnabled()) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    ReportEntry current = reports.get(reportId);
                    if (current == null) return;
                    if (error != null || path == null) {
                        current.status = Status.FAILED;
                        plugin.getLogger().warning("Replay für Report " + reportId + " konnte nicht gespeichert werden: "
                                + (error == null ? "kein Replay-Pfad" : rootMessage(error)));
                        reporter.sendMessage(Component.text("Report #" + reportId
                                + " wurde angelegt, aber das Replay konnte nicht gespeichert werden. Bitte informiere das Team.",
                                NamedTextColor.RED));
                        notifyStaff(current);
                    } else {
                        current.status = Status.OPEN;
                        current.replayPath = path.toString();
                        reporter.sendMessage(Component.text("Report #" + reportId
                                + " wurde erstellt. Das Team wurde benachrichtigt.", NamedTextColor.GREEN));
                        notifyStaff(current);
                    }
                    save();
                });
            });
        } catch (RuntimeException | IOException ex) {
            entry.status = Status.FAILED;
            save();
            notifyStaff(entry);
            reporter.sendMessage(Component.text("Der Report wurde angelegt, aber das Replay konnte nicht gestartet werden.",
                    NamedTextColor.RED));
            plugin.getLogger().warning("Replay-Aufzeichnung für Report " + reportId + " fehlgeschlagen: "
                    + ex.getClass().getSimpleName());
            return false;
        }

        reporter.sendMessage(Component.text("Report wird erstellt …", NamedTextColor.YELLOW));
        return true;
    }

    private void notifyStaff(ReportEntry entry) {
        Component message = Component.text("[Report #" + entry.id + "] ", NamedTextColor.GOLD)
                .append(Component.text(entry.targetName + " wurde von " + entry.reporterName + " gemeldet. ",
                        NamedTextColor.YELLOW))
                .append(Component.text("/reports view " + entry.id, NamedTextColor.AQUA));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("thatsaban.reports.notify")
                    || staff.hasPermission("thatsaban.reports")) {
                staff.sendMessage(message);
            }
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }

    List<ReportEntry> reports() {
        return reports.values().stream()
                .sorted(Comparator.comparingLong(ReportEntry::createdAt).reversed())
                .toList();
    }

    ReportEntry find(String id) {
        return id == null ? null : reports.get(id.toLowerCase(Locale.ROOT));
    }

    boolean setStatus(String id, Status status) {
        if (storageWriteBlocked) return false;
        ReportEntry entry = find(id);
        if (entry == null) return false;
        Status previous = entry.status;
        entry.status = status;
        if (!save()) {
            entry.status = previous;
            return false;
        }
        return true;
    }

    void shutdown() {
        for (UUID id : List.copyOf(armedByThisPlugin)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                try {
                    clips.disarm(player);
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Report-Puffer konnte für " + player.getName()
                            + " beim Deaktivieren nicht beendet werden.");
                }
            }
        }
        armedByThisPlugin.clear();
        save();
    }

    private void load() {
        if (!storageFile.isFile()) return;
        if (!restrictOwnerOnly(storageFile.toPath())) {
            storageWriteBlocked = true;
            plugin.getLogger().severe("Dateirechte von reports.yml konnten nicht auf owner-only gesetzt werden. "
                    + "Report-Schreibzugriffe sind blockiert.");
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(storageFile);
        } catch (IOException | InvalidConfigurationException ex) {
            storageWriteBlocked = true;
            plugin.getLogger().severe("reports.yml ist fehlerhaft oder nicht lesbar. "
                    + "Die Datei bleibt unverändert; Report-Schreibzugriffe sind bis zur manuellen Prüfung blockiert.");
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("reports");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            ConfigurationSection item = section.getConfigurationSection(id);
            if (item == null) continue;
            try {
                Status storedStatus = Status.valueOf(
                        item.getString("status", "FAILED").toUpperCase(Locale.ROOT));
                if (storedStatus == Status.CAPTURING) storedStatus = Status.FAILED;
                ReportEntry entry = new ReportEntry(
                        id,
                        UUID.fromString(item.getString("reporter-uuid", "")),
                        item.getString("reporter-name", "unknown"),
                        UUID.fromString(item.getString("target-uuid", "")),
                        item.getString("target-name", "unknown"),
                        item.getString("reason", ""),
                        item.getLong("created-at", 0L),
                        storedStatus,
                        item.getString("replay-path"));
                reports.put(id.toLowerCase(Locale.ROOT), entry);
                // Rebuild cooldowns from persisted report timestamps so a restart
                // cannot immediately reset every reporter's cooldown.
                cooldowns.merge(entry.reporterId(), entry.createdAt(), Math::max);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Ungültiger Report-Eintrag in reports.yml: " + id);
            }
        }
    }

    private boolean save() {
        if (storageWriteBlocked) {
            return false;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (ReportEntry entry : reports.values()) {
            String base = "reports." + entry.id();
            yaml.set(base + ".reporter-uuid", entry.reporterId().toString());
            yaml.set(base + ".reporter-name", entry.reporterName());
            yaml.set(base + ".target-uuid", entry.targetId().toString());
            yaml.set(base + ".target-name", entry.targetName());
            yaml.set(base + ".reason", entry.reason());
            yaml.set(base + ".created-at", entry.createdAt());
            yaml.set(base + ".status", entry.status().name());
            yaml.set(base + ".replay-path", entry.replayPath());
        }
        Path temporaryFile = null;
        try {
            Path parent = storageFile.toPath().toAbsolutePath().getParent();
            if (parent == null) {
                throw new IOException("Report-Verzeichnis ist nicht verfügbar.");
            }
            Files.createDirectories(parent);
            temporaryFile = Files.createTempFile(parent, "reports.yml.tmp-", ".yml");
            yaml.save(temporaryFile.toFile());
            if (!restrictOwnerOnly(temporaryFile)) {
                storageWriteBlocked = true;
                plugin.getLogger().severe("Dateirechte der temporären reports.yml konnten nicht gesichert werden. "
                        + "Report-Schreibzugriffe sind blockiert.");
                return false;
            }
            try {
                Files.move(temporaryFile, storageFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporaryFile, storageFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException ex) {
            storageWriteBlocked = true;
            plugin.getLogger().severe("reports.yml konnte nicht sicher gespeichert werden: "
                    + ex.getClass().getSimpleName() + ". Weitere Report-Schreibzugriffe sind blockiert.");
            return false;
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // A leftover temporary file is harmless and is never treated as the active report store.
                }
            }
        }
    }

    private static boolean restrictOwnerOnly(Path path) {
        Set<PosixFilePermission> permissions = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        try {
            Files.setPosixFilePermissions(path, permissions);
            return true;
        } catch (UnsupportedOperationException ex) {
            File file = path.toFile();
            return file.setReadable(false, false)
                    && file.setWritable(false, false)
                    && file.setExecutable(false, false)
                    && file.setReadable(true, true)
                    && file.setWritable(true, true);
        } catch (IOException | SecurityException ex) {
            return false;
        }
    }

    private static String safeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        return current.getClass().getSimpleName();
    }
}
