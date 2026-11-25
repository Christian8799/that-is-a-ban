package io.github.christian8799.thatsaban;

import net.luckperms.api.LuckPerms;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class ThatsABan extends JavaPlugin {
    private Messages messages;
    private LinkStore store;
    private CountryFilter countryFilter;
    private AdminSpectatorManager spectatorManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();

        RegisteredServiceProvider<LuckPerms> provider =
                getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            getLogger().severe("LuckPerms nicht gefunden - that-s-a-ban wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        messages = new Messages(this);
        store = new LinkStore(this);
        store.load();
        store.startAutosave();

        LuckPermsHook hook = new LuckPermsHook(this, provider.getProvider());
        BedrockDetector bedrock = new BedrockDetector(this);
        Layer2Service layer2 = new Layer2Service(this, store, hook);
        countryFilter = new CountryFilter(this);

        layer2.cleanupExpired();
        getServer().getScheduler().runTaskTimer(this, layer2::cleanupExpired, 1200L, 1200L);
        getServer().getPluginManager().registerEvents(
                new LoginListener(this, layer2, bedrock, countryFilter), this);

        PluginCommand offendCommand = getCommand("offend");
        if (offendCommand != null) {
            OffendCommand offend = new OffendCommand(this, layer2, bedrock, store);
            offendCommand.setExecutor(offend);
            offendCommand.setTabCompleter(offend);
        }

        PluginCommand privacyCommand = getCommand("thatsaban");
        if (privacyCommand != null) {
            PrivacyAdminCommand admin = new PrivacyAdminCommand(this, store, layer2, hook);
            privacyCommand.setExecutor(admin);
            privacyCommand.setTabCompleter(admin);
        }

        spectatorManager = new AdminSpectatorManager(this);
        PluginCommand spectatorCommand = getCommand("adminspec");
        if (spectatorCommand != null) {
            AdminSpectatorCommand executor = new AdminSpectatorCommand(this, spectatorManager);
            spectatorCommand.setExecutor(executor);
            spectatorCommand.setTabCompleter(executor);
        }

        getServer().getScheduler().runTask(this, this::checkIntegrations);
    }

    private void migrateConfig() {
        int version = getConfig().getInt("config-version", 0);
        if (version < 2) {
            getConfig().set("country-filter.database-file", "geoip/IP2LOCATION-LITE-DB3.IPV6.BIN");
            getConfig().set("country-filter.allowed-region-names", List.of("Bayern", "Bavaria"));
            getConfig().set("alt-detection.enabled", false);
            getConfig().set("alt-detection.retention-days", 30);
            version = 2;
            getLogger().warning("Datenschutz-Migration: IP-Kopplung wurde deaktiviert. "
                    + "Die lokale GeoIP-Datenbank muss unter plugins/ThatsABan/geoip abgelegt werden.");
        }
        if (version < 3) {
            if (getConfig().getLong("alt-detection.offender-retention-days", 0L) <= 0L) {
                getConfig().set("alt-detection.offender-retention-days", 365);
            }
            getConfig().set("alt-detection.auto-apply", false);
            getConfig().set("alt-detection.retroactive", false);
            getConfig().set("privacy.audit-logging", false);
            version = 3;
        }
        if (getConfig().getInt("config-version", 0) != version) {
            getConfig().set("config-version", version);
            saveConfig();
        }
    }

    @Override
    public void onDisable() {
        if (spectatorManager != null) spectatorManager.shutdown();
        if (store != null) store.save();
        if (countryFilter != null) countryFilter.close();
    }

    Messages messages() { return messages; }

    private void checkIntegrations() {
        warnMissing("layer1-plugins", "Layer 1");
        warnMissing("layer2-plugins", "Layer 2");
    }

    private void warnMissing(String key, String layer) {
        for (String name : getConfig().getStringList("integrations." + key)) {
            if (getServer().getPluginManager().getPlugin(name) == null) {
                getLogger().warning(layer + ": Ein konfiguriertes Integrations-Plugin fehlt. "
                        + "(Details: config.yml -> integrations." + key + ")");
            }
        }
    }
}
