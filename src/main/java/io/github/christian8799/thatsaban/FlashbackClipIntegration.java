package io.github.christian8799.thatsaban;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API-neutral bridge to the optional FlashbackServer plugin.
 *
 * FlashbackServer 1.2.2 (the release for Minecraft 1.21.11) predates its
 * public Bukkit API. For that release we use its documented /replay clip
 * commands and copy the generated clip into That'sABan's report directory.
 * Newer releases with the public ClipService API use the API directly.
 */
final class FlashbackClipIntegration {
    private static final String API_CLASS = "dev.zeffut.flashbackserver.api.ClipService";
    private static final long COMMAND_SAVE_TIMEOUT_MILLIS = 30_000L;
    private static final Set<UUID> commandArmedPlayers = ConcurrentHashMap.newKeySet();

    private final ThatsABan plugin;
    private boolean warnedUnavailable;
    private boolean loggedCommandFallback;

    FlashbackClipIntegration(ThatsABan plugin) {
        this.plugin = plugin;
    }

    boolean isAvailable() {
        if (dependency() == null) return false;
        if (apiService() != null) return true;
        if (replayCommand() != null) {
            if (!loggedCommandFallback) {
                loggedCommandFallback = true;
                plugin.getLogger().info("FlashbackServer public ClipService API is not present; using the compatible /replay clip command bridge.");
            }
            return true;
        }
        warnUnavailable("FlashbackServer is enabled, but neither its ClipService API nor its /replay command is available.");
        return false;
    }

    int configuredWindowSeconds() {
        Plugin dependency = dependency();
        return dependency == null ? -1 : dependency.getConfig().getInt("clips.window-seconds", 30);
    }

    boolean isArmed(Player player) {
        Object service = apiService();
        if (service != null) {
            return Boolean.TRUE.equals(invoke(service, "isArmed",
                    new Class<?>[]{Player.class}, player));
        }
        return commandArmedPlayers.contains(player.getUniqueId());
    }

    boolean arm(Player player) {
        Object service = apiService();
        if (service != null) {
            return Boolean.TRUE.equals(invoke(service, "arm",
                    new Class<?>[]{Player.class}, player));
        }
        if (!dispatchClipCommand("arm", player)) return false;
        commandArmedPlayers.add(player.getUniqueId());
        return true;
    }

    boolean disarm(Player player) {
        Object service = apiService();
        if (service != null) {
            return Boolean.TRUE.equals(invoke(service, "disarm",
                    new Class<?>[]{Player.class}, player));
        }
        boolean dispatched = dispatchClipCommand("disarm", player);
        commandArmedPlayers.remove(player.getUniqueId());
        return dispatched;
    }

    CompletableFuture<Path> saveClip(Player player, Path output) {
        Object service = apiService();
        if (service != null) {
            Object result = invoke(service, "saveClip",
                    new Class<?>[]{Player.class, Path.class}, player, output);
            if (result instanceof CompletableFuture<?> future) {
                return future.thenApply(value -> (Path) value);
            }
            return CompletableFuture.failedFuture(
                    new IllegalStateException("FlashbackServer returned an invalid ClipService result."));
        }

        if (!commandArmedPlayers.contains(player.getUniqueId())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No report clip buffer is armed for this player."));
        }

        Plugin dependency = dependency();
        if (dependency == null || replayCommand() == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("FlashbackServer command bridge is unavailable."));
        }

        Path clipsDirectory = dependency.getDataFolder().toPath().resolve("clips");
        Map<Path, FileStamp> before;
        try {
            before = scanClips(clipsDirectory);
            Path parent = output.toAbsolutePath().getParent();
            if (parent == null) throw new IOException("Report replay output directory is unavailable.");
            Files.createDirectories(parent);
        } catch (IOException ex) {
            return CompletableFuture.failedFuture(ex);
        }

        if (!dispatchClipCommand("save", player)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("FlashbackServer rejected /replay clip save."));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                Path generated = waitForGeneratedClip(clipsDirectory, before);
                Files.copy(generated, output, StandardCopyOption.REPLACE_EXISTING);
                return output;
            } catch (IOException ex) {
                throw new CompletionException(ex);
            }
        });
    }

    private Plugin dependency() {
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin("FlashbackServer");
        return dependency != null && dependency.isEnabled() ? dependency : null;
    }

    private PluginCommand replayCommand() {
        Plugin dependency = dependency();
        if (dependency == null) return null;
        PluginCommand command = plugin.getServer().getPluginCommand("replay");
        return command != null && command.getPlugin().equals(dependency) ? command : null;
    }

    private boolean dispatchClipCommand(String action, Player player) {
        if (replayCommand() == null) return false;
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "replay clip " + action + " " + player.getName());
    }

    private Object apiService() {
        Plugin dependency = dependency();
        if (dependency == null) return null;

        try {
            Class<?> serviceType = Class.forName(API_CLASS, false, dependency.getClass().getClassLoader());
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object service = plugin.getServer().getServicesManager().load((Class) serviceType);
            return service;
        } catch (ClassNotFoundException | LinkageError | IllegalArgumentException ex) {
            // FlashbackServer 1.2.2 for Minecraft 1.21.11 has no public API;
            // the command bridge below is the supported compatibility path.
            return null;
        }
    }

    private Object invoke(Object service, String methodName,
                          Class<?>[] parameterTypes, Object... arguments) {
        try {
            Class<?> apiType = Class.forName(API_CLASS, false, dependency().getClass().getClassLoader());
            Method method = apiType.getMethod(methodName, parameterTypes);
            return method.invoke(service, arguments);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException ex) {
            throw new IllegalStateException("Incompatible FlashbackServer ClipService API.", ex);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("FlashbackServer API call failed.", cause);
        }
    }

    private static Map<Path, FileStamp> scanClips(Path directory) throws IOException {
        Map<Path, FileStamp> files = new HashMap<>();
        if (!Files.isDirectory(directory)) return files;
        try (var stream = Files.newDirectoryStream(directory, "*.flashback")) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    files.put(path, new FileStamp(Files.size(path),
                            Files.getLastModifiedTime(path).toMillis()));
                }
            }
        }
        return files;
    }

    private static Path waitForGeneratedClip(Path directory, Map<Path, FileStamp> before)
            throws IOException {
        long deadline = System.currentTimeMillis() + COMMAND_SAVE_TIMEOUT_MILLIS;
        Path stableCandidate = null;
        FileStamp previousStamp = null;
        int stableSamples = 0;

        while (System.currentTimeMillis() < deadline) {
            Map<Path, FileStamp> current = scanClips(directory);
            Path newestChanged = null;
            FileStamp newestStamp = null;
            for (Map.Entry<Path, FileStamp> entry : current.entrySet()) {
                FileStamp oldStamp = before.get(entry.getKey());
                if (oldStamp == null || !oldStamp.equals(entry.getValue())) {
                    if (newestStamp == null
                            || entry.getValue().modifiedMillis() > newestStamp.modifiedMillis()) {
                        newestChanged = entry.getKey();
                        newestStamp = entry.getValue();
                    }
                }
            }

            if (newestChanged != null && newestStamp.size() > 0) {
                if (newestChanged.equals(stableCandidate) && newestStamp.equals(previousStamp)) {
                    stableSamples++;
                } else {
                    stableCandidate = newestChanged;
                    previousStamp = newestStamp;
                    stableSamples = 1;
                }
                if (stableSamples >= 3) return stableCandidate;
            } else {
                stableCandidate = null;
                previousStamp = null;
                stableSamples = 0;
            }

            try {
                Thread.sleep(100L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for FlashbackServer to save the clip.", ex);
            }
        }
        throw new IOException("Timed out waiting for FlashbackServer to finish writing the replay clip.");
    }

    private void warnUnavailable(String reason) {
        if (warnedUnavailable) return;
        warnedUnavailable = true;
        plugin.getLogger().warning("Report replays are unavailable. " + reason);
    }

    private record FileStamp(long size, long modifiedMillis) { }
}
