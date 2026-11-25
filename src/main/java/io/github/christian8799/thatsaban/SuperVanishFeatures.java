package io.github.christian8799.thatsaban;

import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Loaded reflectively only while SuperVanish is enabled. */
final class SuperVanishFeatures implements VanishIntegration {
    private final Method isInvisible;
    private final Method hidePlayer;
    private final Method showPlayer;

    SuperVanishFeatures(ThatsABan plugin) {
        try {
            Class<?> api = Class.forName("de.myzelyam.api.vanish.VanishAPI",
                    true, plugin.getClass().getClassLoader());
            isInvisible = api.getMethod("isInvisible", Player.class);
            hidePlayer = api.getMethod("hidePlayer", Player.class);
            showPlayer = api.getMethod("showPlayer", Player.class);
            if (api.getMethod("getPlugin").invoke(null) == null) {
                throw new IllegalStateException("SuperVanish-API ist noch nicht initialisiert.");
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("SuperVanish-API ist inkompatibel.", ex);
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean isVanished(Player player) {
        return (Boolean) invoke(isInvisible, player);
    }

    @Override
    public void setVanished(Player player, boolean vanished) {
        if (isVanished(player) == vanished) return;
        invoke(vanished ? hidePlayer : showPlayer, player);
        if (isVanished(player) != vanished) {
            throw new IllegalStateException("SuperVanish hat den angeforderten Status nicht übernommen.");
        }
    }

    private Object invoke(Method method, Player player) {
        try {
            return method.invoke(null, player);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("SuperVanish-API ist nicht zugänglich.", ex);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("SuperVanish-API-Aufruf fehlgeschlagen.", cause);
        }
    }
}
