package io.github.christian8799.thatsaban;

import org.bukkit.command.CommandExecutor;

interface SpectatorIntegration {
    CommandExecutor command();
    void shutdown();
}