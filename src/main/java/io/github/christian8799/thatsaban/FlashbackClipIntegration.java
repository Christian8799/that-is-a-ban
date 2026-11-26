package io.github.christian8799.thatsaban;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * API-neutral bridge to the optional MIT-licensed FlashbackServer plugin.
 * The external API is resolved reflectively so ThatsABan still loads without it.
 */
final class FlashbackClipIntegration {
    private static final String API_CLASS = "dev.zeffut.flashbackserver.api.ClipService";

    private final ThatsABan plugin;
    private boolean warnedUnavailable;

    FlashbackClipIntegration(ThatsABan plugin) {
        this.plugin = plugin;
    }

    boolean isAvailable() {
        return service() != null;
    }

    int configuredWindowSeconds() {
        Plugin dependency = dependency();
        return dependency == null ? -1 : dependency.getConfig().getInt("clips.window-seconds", 30);
    }

    boolean isArmed(Player player) {
        return Boolean.TRUE.equals(invoke("isArmed", new Class<?>[]{Player.class}, player));
    }

    boolean arm(Player player) {
        return Boolean.TRUE.equals(invoke("arm", new Class<?>[]{Player.class}, player));
    }

    boolean disarm(Player player) {
        return Boolean.TRUE.equals(invoke("disarm", new Class<?>[]{Player.class}, player));
    }

    @SuppressWarnings("unchecked")
    CompletableFuture<Path> saveClip(Player player, Path output) {
        Object result = invoke("saveClip",
                new Class<?>[]{Player.class, Path.class}, player, output);
        if (result instanceof CompletableFuture<?> future) {
            return future.thenApply(value -> (Path) value);
        }
        return CompletableFuture.failedFuture(
                new IllegalStateException("FlashbackServer returned an invalid ClipService result."));
    }

    private Plugin dependency() {
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin("FlashbackServer");
        return dependency != null && dependency.isEnabled() ? dependency : null;
    }

    private Object service() {
        Plugin dependency = dependency();
        if (dependency == null) return null;

        try {
            Class<?> serviceType = Class.forName(API_CLASS, false, dependency.getClass().getClassLoader());
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object service = plugin.getServer().getServicesManager().load((Class) serviceType);
            if (service == null) warnUnavailable("FlashbackServer is enabled but has not registered a ClipService.");
            return service;
        } catch (ClassNotFoundException | LinkageError | IllegalArgumentException ex) {
            warnUnavailable("Could not load the FlashbackServer API: "
                    + ex.getClass().getSimpleName());
            return null;
        }
    }

    private Object invoke(String methodName, Class<?>[] parameterTypes, Object... arguments) {
        Object service = service();
        if (service == null) {
            throw new IllegalStateException("FlashbackServer or its ClipService is unavailable.");
        }

        try {
            // Invoke through the public API interface, not the implementation class:
            // FlashbackServer is free to expose a package-private service implementation.
            Class<?> apiType = Class.forName(API_CLASS, false, dependency().getClass().getClassLoader());
            Method method = apiType.getMethod(methodName, parameterTypes);
            return method.invoke(service, arguments);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException ex) {
            throw new IllegalStateException("Inkompatible FlashbackServer-ClipService-API.", ex);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("FlashbackServer API call failed.", cause);
        }
    }

    private void warnUnavailable(String reason) {
        if (warnedUnavailable) return;
        warnedUnavailable = true;
        plugin.getLogger().warning("Report replays are unavailable. " + reason);
    }
}
