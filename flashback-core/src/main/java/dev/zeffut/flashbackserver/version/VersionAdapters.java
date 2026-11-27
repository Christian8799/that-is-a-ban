package dev.zeffut.flashbackserver.version;

import org.bukkit.Bukkit;

import java.util.logging.Logger;

/** Loads the only NMS adapter embedded in this build. */
public final class VersionAdapters {
    private static final Logger LOG = Logger.getLogger("ThatsABan-Flashback");
    private static volatile VersionAdapter cached;

    private VersionAdapters() {}

    public static VersionAdapter current() {
        VersionAdapter adapter = cached;
        if (adapter != null) return adapter;

        String running = Bukkit.getMinecraftVersion();
        if (!"1.21.11".equals(running)) {
            throw new VersionAdapterUnavailableException(
                    "Embedded report replay capture supports Minecraft 1.21.11 only; found " + running + ".",
                    new IllegalStateException("Unsupported Minecraft version: " + running));
        }

        synchronized (VersionAdapters.class) {
            if (cached != null) return cached;
            String name = "dev.zeffut.flashbackserver.version.v1_21_11.V1_21_11Adapter";
            try {
                cached = (VersionAdapter) Class.forName(name).getDeclaredConstructor().newInstance();
                LOG.info("Embedded Flashback adapter loaded for Minecraft 1.21.11.");
                return cached;
            } catch (ReflectiveOperationException | LinkageError ex) {
                throw new VersionAdapterUnavailableException(
                        "Could not load the embedded Minecraft 1.21.11 replay adapter.", ex);
            }
        }
    }
}
