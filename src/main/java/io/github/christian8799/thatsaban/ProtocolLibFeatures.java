package io.github.christian8799.thatsaban;

import com.comphenix.protocol.ProtocolLibrary;
import org.bukkit.command.CommandExecutor;

/** Loaded only when ProtocolLib is enabled and its API is present. */
final class ProtocolLibFeatures implements SpectatorIntegration {
    private final AdminSpectatorManager manager;
    private final AdminSpectatorMenu menu;

    ProtocolLibFeatures(ThatsABan plugin, VanishIntegration vanish) {
        try {
            manager = new AdminSpectatorManager(plugin, vanish);
            menu = new AdminSpectatorMenu(plugin, manager);
        } catch (LinkageError | RuntimeException ex) {
            try {
                ProtocolLibrary.getProtocolManager().removePacketListeners(plugin);
            } catch (LinkageError | RuntimeException cleanupError) {
                plugin.getLogger().warning("ProtocolLib cleanup failed: "
                        + cleanupError.getClass().getSimpleName());
            }
            throw ex;
        }
    }

    @Override
    public CommandExecutor command() {
        return new AdminSpectatorCommand(manager, menu);
    }

    @Override
    public void shutdown() {
        manager.shutdown();
    }
}