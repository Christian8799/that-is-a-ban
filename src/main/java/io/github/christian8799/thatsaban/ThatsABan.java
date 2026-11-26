package io.github.christian8799.thatsaban;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;

public final class ThatsABan extends JavaPlugin {
    private Messages messages;
    private LinkStore store;
    private CountryFilterIntegration countryFilter;
    private SpectatorIntegration spectatorFeature;
    private ReportManager reportManager;

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
                luckPerms = loadLuckPermsIntegration();
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
            countryFilter = loadCountryFilter();
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
                ? new PrivacyStoreCommand(this, store)
                : luckPerms.privacyCommand());

        if (luckPerms != null) {
            LuckPermsIntegration active = luckPerms;
            active.cleanupExpired();
            getServer().getScheduler().runTaskTimer(this, active::cleanupExpired, 1200L, 1200L);
        }

        registerSpectatorFeature();

        FlashbackClipIntegration flashbackClips = new FlashbackClipIntegration(this);
        reportManager = new ReportManager(this, flashbackClips);
        getServer().getPluginManager().registerEvents(reportManager, this);
        registerCommand("report", new ReportCommand(reportManager));
        registerCommand("reports", new ReportsCommand(this, reportManager));
        reportManager.initialize();

        getServer().getScheduler().runTask(this, this::checkIntegrations);
        getLogger().info("ThatsABan aktiviert. Optionale Integrationen wurden unabhängig initialisiert.");
    }

    private CountryFilterIntegration loadCountryFilter() {
        try {
            Class<?> type = Class.forName(getClass().getPackageName() + ".CountryFilter",
                    true, getClass().getClassLoader());
            return (CountryFilterIntegration) type
                    .getDeclaredConstructor(ThatsABan.class)
                    .newInstance(this);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof LinkageError error) throw error;
            if (cause instanceof RuntimeException error) throw error;
            throw new IllegalStateException("Country-Filter konnte nicht erstellt werden.", cause);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Country-Filter konnte nicht geladen werden.", ex);
        }
    }

    private LuckPermsIntegration loadLuckPermsIntegration() {
        try {
            Class<?> type = Class.forName(getClass().getPackageName() + ".LuckPermsFeatures",
                    true, getClass().getClassLoader());
            return (LuckPermsIntegration) type
                    .getDeclaredConstructor(ThatsABan.class, LinkStore.class)
                    .newInstance(this, store);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof LinkageError error) throw error;
            if (cause instanceof RuntimeException error) throw error;
            throw new IllegalStateException("LuckPerms-Integration konnte nicht erstellt werden.", cause);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("LuckPerms-Integration konnte nicht geladen werden.", ex);
        }
    }

    private VanishIntegration loadVanishIntegration() {
        if (!getServer().getPluginManager().isPluginEnabled("SuperVanish")) {
            warnFeatureDisabled("SuperVanish-Integration", "SuperVanish fehlt oder ist nicht aktiviert.");
            return null;
        }
        try {
            Class<?> type = Class.forName(getClass().getPackageName() + ".SuperVanishFeatures",
                    true, getClass().getClassLoader());
            VanishIntegration integration = (VanishIntegration) type
                    .getDeclaredConstructor(ThatsABan.class)
                    .newInstance(this);
            getLogger().info("SuperVanish-Integration aktiv.");
            return integration;
        } catch (java.lang.reflect.InvocationTargetException ex) {
            warnFeatureDisabled("SuperVanish-Integration", ex.getCause() == null ? ex : ex.getCause());
            return null;
        } catch (ReflectiveOperationException | LinkageError ex) {
            warnFeatureDisabled("SuperVanish-Integration", ex);
            return null;
        }
    }

    private SpectatorIntegration loadSpectatorIntegration(VanishIntegration vanish) {
        try {
            Class<?> type = Class.forName(getClass().getPackageName() + ".ProtocolLibFeatures",
                    true, getClass().getClassLoader());
            return (SpectatorIntegration) type
                    .getDeclaredConstructor(ThatsABan.class, VanishIntegration.class)
                    .newInstance(this, vanish);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof LinkageError error) throw error;
            if (cause instanceof RuntimeException error) throw error;
            throw new IllegalStateException("ProtocolLib-Integration konnte nicht erstellt werden.", cause);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("ProtocolLib-Integration konnte nicht geladen werden.", ex);
        }
    }

    private void registerSpectatorFeature() {
        if (!getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            registerCommand("adminspec", new FeatureUnavailableCommand(
                    "Admin-Spectator deaktiviert: ProtocolLib fehlt oder ist nicht aktiviert."));
            warnFeatureDisabled("Admin-Spectator (ProtocolLib)", "ProtocolLib fehlt oder ist deaktiviert.");
            return;
        }
        try {
            VanishIntegration vanish = loadVanishIntegration();
            spectatorFeature = loadSpectatorIntegration(vanish);
            registerCommand("adminspec", spectatorFeature.command());
            getLogger().info("ProtocolLib-Integration aktiv: Admin-Spectator verfügbar.");
        } catch (LinkageError | RuntimeException ex) {
            spectatorFeature = null;
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
        if (version < 4) {
            if (!getConfig().contains("reports.enabled")) getConfig().set("reports.enabled", true);
            if (!getConfig().contains("reports.cooldown-seconds")) getConfig().set("reports.cooldown-seconds", 60);
            if (!getConfig().contains("reports.max-reason-length")) getConfig().set("reports.max-reason-length", 200);
            if (!getConfig().contains("reports.minimum-clip-window-seconds")) {
                getConfig().set("reports.minimum-clip-window-seconds", 60);
            }
            version = 4;
        }
        if (getConfig().getInt("config-version", 0) != version) {
            getConfig().set("config-version", version);
            saveConfig();
        }
    }

    @Override
    public void onDisable() {
        if (spectatorFeature != null) spectatorFeature.shutdown();
        if (reportManager != null) reportManager.shutdown();
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