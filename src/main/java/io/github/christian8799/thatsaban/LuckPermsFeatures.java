package io.github.christian8799.thatsaban;

import net.luckperms.api.LuckPerms;
import org.bukkit.command.CommandExecutor;
import org.bukkit.plugin.RegisteredServiceProvider;
import java.net.InetAddress;
import java.util.UUID;

final class LuckPermsFeatures implements LuckPermsIntegration {
    private final Layer2Service layer2;
    private final OffendCommand offend;
    private final PrivacyAdminCommand privacy;

    LuckPermsFeatures(ThatsABan plugin, LinkStore store) {
        RegisteredServiceProvider<LuckPerms> provider =
                plugin.getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null || provider.getProvider() == null) {
            throw new IllegalStateException("LuckPerms API-Service nicht registriert.");
        }
        LuckPermsHook hook = new LuckPermsHook(plugin, provider.getProvider());
        layer2 = new Layer2Service(plugin, store, hook);
        BedrockDetector bedrock = new BedrockDetector(plugin);
        offend = new OffendCommand(plugin, layer2, bedrock, store);
        privacy = new PrivacyAdminCommand(plugin, store, layer2, hook);
    }

    @Override public CommandExecutor offendCommand() { return offend; }
    @Override public CommandExecutor privacyCommand() { return privacy; }
    @Override public void cleanupExpired() { layer2.cleanupExpired(); }
    @Override public void recordNetworkLogin(UUID uuid, String name, InetAddress address) {
        layer2.recordNetworkLogin(uuid, name, address);
    }
    @Override public void handleLogin(UUID uuid, String name, InetAddress address) {
        layer2.handleLogin(uuid, name, address);
    }
}