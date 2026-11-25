package io.github.christian8799.thatsaban;

import com.comphenix.protocol.ProtocolLibrary;
import org.bukkit.command.CommandExecutor;

/** Loaded only when ProtocolLib is enabled and its API is present. */
final class ProtocolLibFeatures implements SpectatorIntegration {
    private final AdminSpectatorManager manager;

    ProtocolLibFeatures(ThatsABan plugin) {
        try {
            manager = new AdminSpectatorManager(plugin);
        } catch (LinkageError | RuntimeException ex) {
            try {
                ProtocolLibrary.getProtocolManager().removePacketListeners(plugin);
            } catch (LinkageError | RuntimeException cleanupError) {
                plugin.getLogger().warning("ProtocolLib-Cleanup fehlgeschlagen: "
                        + cleanupError.getClass().getSimpleName());
            }
            throw ex;
        }
    }

    @Override
    public CommandExecutor command() {
        return new AdminSpectatorCommand(manager);
    }

    @Override
    public void shutdown() {
        manager.shutdown();
    }
}