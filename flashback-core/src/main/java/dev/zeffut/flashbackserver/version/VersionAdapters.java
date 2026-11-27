package dev.zeffut.flashbackserver.version;

import org.bukkit.Bukkit;

import java.util.logging.Logger;

/**
 * Loads the NMS adapter matching the running Minecraft version.
 * Each adapter is compiled in its own Paperweight module and shaded into the plugin JAR.
 */
public final class VersionAdapters {
    private static final Logger LOG = Logger.getLogger("ThatsABan-Flashback");
    private static volatile VersionAdapter cached;
    private static volatile String cachedVersion;

    private VersionAdapters() {}

    public static VersionAdapter current() {
        String running = Bukkit.getMinecraftVersion();
        VersionAdapter adapter = cached;
        if (adapter != null && running.equals(cachedVersion)) return adapter;

        final String adapterName;
        if ("1.21.11".equals(running)) {
            adapterName = "dev.zeffut.flashbackserver.version.v1_21_11.V1_21_11Adapter";
        } else if (running.startsWith("26.3")) {
            adapterName = "dev.zeffut.flashbackserver.version.v26_3.V26_3Adapter";
        } else {
            throw new VersionAdapterUnavailableException(
                    "Embedded report replay capture supports Minecraft 1.21.11 and 26.3; found "
                            + running + ".",
                    new IllegalStateException("Unsupported Minecraft version: " + running));
        }

        synchronized (VersionAdapters.class) {
            if (cached != null && running.equals(cachedVersion)) return cached;
            try {
                cached = (VersionAdapter) Class.forName(adapterName).getDeclaredConstructor().newInstance();
                cachedVersion = running;
                LOG.info("Embedded Flashback adapter loaded for Minecraft " + running + ".");
                return cached;
            } catch (ReflectiveOperationException | LinkageError ex) {
                throw new VersionAdapterUnavailableException(
                        "Could not load the embedded Minecraft " + running + " replay adapter.", ex);
            }
        }
    }
}
