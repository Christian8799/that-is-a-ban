package io.github.christian8799.thatsaban;

import java.util.UUID;

/**
 * Erkennt Bedrock-Spieler (Geyser/Floodgate). Java-Spieler koennen weder eine UUID mit
 * most-significant-bits 0 noch einen Namen mit "." haben, daher sind die ersten Checks sicher.
 * Die APIs von Floodgate/Geyser werden per Reflection angesprochen (keine harte Abhaengigkeit).
 */
final class BedrockDetector {

    private final ThatsABan plugin;

    BedrockDetector(ThatsABan plugin) {
        this.plugin = plugin;
    }

    boolean isBedrock(UUID uuid, String name) {
        if (uuid.getMostSignificantBits() == 0L) {
            return true; // Floodgate-UUID
        }
        if (name != null) {
            for (String prefix : plugin.getConfig().getStringList("bedrock.name-prefixes")) {
                if (!prefix.isEmpty() && name.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return floodgate(uuid) || geyser(uuid);
    }

    private boolean floodgate(UUID uuid) {
        try {
            Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = type.getMethod("getInstance").invoke(null);
            return (boolean) type.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException ignored) {
            return false;
        }
    }

    private boolean geyser(UUID uuid) {
        try {
            Class<?> type = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Object api = type.getMethod("api").invoke(null);
            return (boolean) type.getMethod("isBedrockPlayer", UUID.class).invoke(api, uuid);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException ignored) {
            return false;
        }
    }
}
