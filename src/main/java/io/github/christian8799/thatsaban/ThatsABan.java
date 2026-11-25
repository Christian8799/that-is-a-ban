package io.github.christian8799.thatsaban;

import org.bukkit.command.PluginCommand;
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
        messages = new Messages(this);
        store = new LinkStore(this);
        store.load();
        store.startAutosave();

        LuckPermsIntegration luckPerms = null;
        if (getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                luckPerms = new LuckPermsFeatures(this, store);
                getLogger().info("LuckPerms-Integration aktiv.");
            } catch (LinkageError | RuntimeException ex) {
                warnFeatureDisabled("LuckPerms-Integration (Layer 2, /offend, /thatsaban)", ex);
            }
        } else {
            warnFeatureDisabled("LuckPerms-Integration (Layer 2, /offend, /thatsaban)",
                    "LuckPerms fehlt oder ist nicht aktiviert.");
        }

        BedrockDetector bedrock = new BedrockDetector(this);
        try {
            countryFilter = new CountryFilter(this);
            if (!countryFilter.isAvailable()) {
                warnFeatureDisabled("Country-/Region-Filter",
                        "Die lokale IP2Location-Datenbank fehlt oder ist nicht lesbar.");
            }
        } catch (LinkageError | RuntimeException ex) {
            countryFilter = null;
            warnFeatureDisabled("Country-/Region-Filter (IP2Location)", ex);
        }

        getServer().getPluginManager().registerEvents(
                new LoginListener(this, luckPerms, bedrock, countryFilter), this);

        registerCommand("offend", luckPerms == null
                ? new FeatureUnavailableCommand("Deaktiviert: Dieser Befehl benötigt LuckPerms.")
                : luckPerms.offendCommand());
        registerCommand("thatsaban", luckPerms == null
                ? new FeatureUnavailableCommand("Deaktiviert: Diese Datenschutzaktionen benötigen LuckPerms.")
                : luckPerms.privacyCommand());

        if (luckPerms != null) {
            LuckPermsIntegration active = luckPerms;
            active.cleanupExpired();
            getServer().getScheduler().runTaskTimer(this, active::cleanupExpired, 1200L, 1200L);
        }

        registerSpectatorFeature();
        getServer().getScheduler().runTask(this, this::checkIntegrations);
        getLogger().info("ThatsABan aktiviert. Optionale Integrationen wurden unabhängig initialisiert.");
    }

    private void registerSpectatorFeature() {
        if (!getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            registerCommand("adminspec", new FeatureUnavailableCommand(
                    "Admin-Spectator deaktiviert: ProtocolLib fehlt oder ist nicht aktiviert."));
            warnFeatureDisabled("Admin-Spectator (ProtocolLib)", "ProtocolLib fehlt oder ist deaktiviert.");
            return;
        }
        try {
            spectatorManager = new AdminSpectatorManager(this);
            registerCommand("adminspec", new AdminSpectatorCommand(spectatorManager));
            getLogger().info("ProtocolLib-Integration aktiv: Admin-Spectator verfügbar.");
        } catch (LinkageError | RuntimeException ex) {
            spectatorManager = null;
            try {
                com.comphenix.protocol.ProtocolLibrary.getProtocolManager().removePacketListeners(this);
            } catch (LinkageError | RuntimeException cleanupError) {
                getLogger().warning("ProtocolLib-Cleanup fehlgeschlagen: " + cleanupError.getClass().getSimpleName());
            }
            registerCommand("adminspec", new FeatureUnavailableCommand(
                    "Admin-Spectator deaktiviert: ProtocolLib ist inkompatibel oder konnte nicht initialisiert werden."));
            warnFeatureDisabled("Admin-Spectator (ProtocolLib)", ex);
        }
    }

    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Befehl '" + name + "' fehlt in plugin.yml.");
            return;
        }
        command.setExecutor(executor);
        if (executor instanceof org.bukkit.command.TabCompleter completer) command.setTabCompleter(completer);
    }

    private void warnFeatureDisabled(String feature, Throwable error) {
        getLogger().warning(feature + " wurde deaktiviert; andere Plugin-Funktionen bleiben aktiv. Ursache: "
                + error.getClass().getSimpleName());
    }

    private void warnFeatureDisabled(String feature, String reason) {
        getLogger().warning(feature + " wurde deaktiviert; andere Plugin-Funktionen bleiben aktiv. Ursache: " + reason);
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
        if (countryFilter != null) {
            try {
                countryFilter.close();
            } catch (LinkageError | RuntimeException ex) {
                getLogger().warning("Country-Filter konnte nicht geschlossen werden: " + ex.getClass().getSimpleName());
            }
        }
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