package io.github.christian8799.thatsaban;

import dev.zeffut.flashbackserver.api.FlashbackAPI;
import dev.zeffut.flashbackserver.api.ReplayCheckResult;
import dev.zeffut.flashbackserver.clip.ClipManager;
import dev.zeffut.flashbackserver.telemetry.Telemetry;
import dev.zeffut.flashbackserver.version.VersionAdapters;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Owns That'sABan's embedded Flashback recording engine.
 * The MIT-licensed recording core and Minecraft 26.3 adapter are bundled
 * into this plugin. No separate FlashbackServer plugin is required.
 */
final class FlashbackClipIntegration {
    private final ThatsABan plugin;
    private final ClipManager clipManager;
    private final int windowSeconds;
    private boolean warnedUnavailable;

    FlashbackClipIntegration(ThatsABan plugin) {
        this.plugin = plugin;
        this.windowSeconds = Math.max(1,
                plugin.getConfig().getInt("reports.clip-window-seconds", 60));
        Path outputDirectory = plugin.getDataFolder().toPath().resolve("clips");
        Telemetry telemetry = new Telemetry(
                false, "", "", "", plugin.getDescription().getVersion(), plugin.getLogger());
        this.clipManager = new ClipManager(plugin, outputDirectory, windowSeconds, telemetry);
        plugin.getServer().getPluginManager().registerEvents(clipManager, plugin);
    }

    boolean isAvailable() {
        try {
            VersionAdapters.current();
            return true;
        } catch (RuntimeException | LinkageError ex) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                plugin.getLogger().warning("Embedded replay recording is unavailable on this Minecraft version: "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            return false;
        }
    }

    int configuredWindowSeconds() { return windowSeconds; }
    boolean isArmed(Player player) { return clipManager.isArmed(player); }

    boolean arm(Player player) {
        return isAvailable() && clipManager.arm(player);
    }

    boolean disarm(Player player) { return clipManager.disarm(player); }

    CompletableFuture<Path> saveClip(Player player, Path output) {
        if (!isAvailable()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Embedded replay recording is unavailable."));
        }
        return clipManager.saveClip(player, output).thenApply(path -> {
            if (path == null) return null;
            ReplayCheckResult check = FlashbackAPI.verify(path);
            if (!check.valid()) {
                String details = String.join("; ", check.problems());
                throw new CompletionException(new IOException(
                        "The generated replay did not pass Flashback validation: " + details));
            }
            return path;
        });
    }

    void shutdown() { clipManager.disarmAll(); }
}
