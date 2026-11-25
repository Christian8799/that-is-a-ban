package io.github.christian8799.thatsaban;

import org.bukkit.command.CommandExecutor;
import java.net.InetAddress;
import java.util.UUID;

interface LuckPermsIntegration {
    CommandExecutor offendCommand();
    CommandExecutor privacyCommand();
    void cleanupExpired();
    void recordNetworkLogin(UUID uuid, String name, InetAddress address);
    void handleLogin(UUID uuid, String name, InetAddress address);
}