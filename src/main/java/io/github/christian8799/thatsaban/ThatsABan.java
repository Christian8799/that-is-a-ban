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
        messages = new Messages(this);
        migrateConfig();
        store = new LinkStore(this);
        store.load();
        store.startAutosave();

        LuckPermsIntegration luckPerms = null;
        if (getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                luckPerms = loadLuckPermsIntegration();
                getLogger().info(messages.text("log-luckperms-enabled"));
            } catch (LinkageError | RuntimeException ex) {
                warnFeatureDisabled("LuckPerms-Integration (Layer 2, /offend, /thatsaban)", ex);
            }
        } else {
            warnFeatureDisabled("LuckPerms-Integration (Layer 2, /offend, /thatsaban)",
                    messages.text("log-luckperms-missing"));
        }

        BedrockDetector bedrock = new BedrockDetector(this);
        try {
            countryFilter = loadCountryFilter();
            if (!countryFilter.isAvailable()) {
                warnFeatureDisabled("Country-/Region-Filter",
                        messages.text("log-geoip-missing"));
            }
        } catch (LinkageError | RuntimeException ex) {
            countryFilter = null;
            warnFeatureDisabled("Country-/Region-Filter (IP2Location)", ex);
        }

        getServer().getPluginManager().registerEvents(
                new LoginListener(this, luckPerms, bedrock, countryFilter), this);

        registerCommand("offend", luckPerms == null
                ? new FeatureUnavailableCommand(messages.text("feature-luckperms-required"))
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
        ReportsCommand reportsCommand = new ReportsCommand(this, reportManager);
        registerCommand("reports", reportsCommand);
        getServer().getPluginManager().registerEvents(reportsCommand, this);
        reportManager.initialize();

        getServer().getScheduler().runTask(this, this::checkIntegrations);
        getLogger().info(messages.text("log-enabled"));
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
            warnFeatureDisabled("SuperVanish", messages.text("log-supervanish-missing"));
            return null;
        }
        try {
            Class<?> type = Class.forName(getClass().getPackageName() + ".SuperVanishFeatures",
                    true, getClass().getClassLoader());
            VanishIntegration integration = (VanishIntegration) type
                    .getDeclaredConstructor(ThatsABan.class)
                    .newInstance(this);
            getLogger().info(messages.text("log-supervanish-enabled"));
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
                    messages.text("feature-protocollib-missing")));
            warnFeatureDisabled("Admin-Spectator (ProtocolLib)", messages.text("log-protocollib-missing"));
            return;
        }
        try {
            VanishIntegration vanish = loadVanishIntegration();
            spectatorFeature = loadSpectatorIntegration(vanish);
            registerCommand("adminspec", spectatorFeature.command());
            getLogger().info(messages.text("log-protocollib-enabled"));
        } catch (LinkageError | RuntimeException ex) {
            spectatorFeature = null;
            registerCommand("adminspec", new FeatureUnavailableCommand(
                    messages.text("feature-protocollib-incompatible")));
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
        String detail = error.getMessage();
        getLogger().warning(feature + " wurde deaktiviert; andere Plugin-Funktionen bleiben aktiv. Ursache: "
                + error.getClass().getSimpleName()
                + (detail == null || detail.isBlank() ? "" : ": " + detail));
    }

    private void warnFeatureDisabled(String feature, String reason) {
        getLogger().warning(feature + " wurde deaktiviert; andere Plugin-Funktionen bleiben aktiv. Ursache: " + reason);
    }

    private void migrateConfig() {
        int version = getConfig().getInt("config-version", 0);
        if (version < 2) {
            if (!getConfig().contains("country-filter.database-file")) {
                getConfig().set("country-filter.database-file", "geoip/IP2LOCATION-LITE-DB3.IPV6.BIN");
            }
            if (!getConfig().contains("country-filter.allowed-region-names")) {
                getConfig().set("country-filter.allowed-region-names", List.of("Bayern", "Bavaria"));
            }
            getConfig().set("alt-detection.enabled", false);
            if (!getConfig().contains("alt-detection.retention-days")) {
                getConfig().set("alt-detection.retention-days", 30);
            }
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
        if (version < 5) {
            if (!getConfig().contains("language")) getConfig().set("language", "en");
            if (!getConfig().contains("messages.de.prefix")) {
                var oldMessages = getConfig().getConfigurationSection("messages");
                if (oldMessages != null) {
                    for (String key : oldMessages.getKeys(false)) {
                        if (!oldMessages.isConfigurationSection(key) && !getConfig().contains("messages.de." + key)) {
                            getConfig().set("messages.de." + key, oldMessages.get(key));
                        }
                    }
                }
            }
            setEnglishMessageDefaults();
            version = 5;
        }
        if (getConfig().getInt("config-version", 0) != version) {
            getConfig().set("config-version", version);
            saveConfig();
        }
    }


    private void setEnglishMessageDefaults() {
        java.util.Map<String, String> defaults = java.util.Map.ofEntries(
                java.util.Map.entry("prefix", "<gray>[<red>that-s-a-ban</red>]</gray> "),
                java.util.Map.entry("usage", "<red>Usage: /offend <player>"),
                java.util.Map.entry("player-not-found", "<red>Player <white><player></white> is not known to this server."),
                java.util.Map.entry("bedrock-immune", "<yellow><player></yellow> is a Bedrock player and is immune to /offend."),
                java.util.Map.entry("exempt", "<yellow><player></yellow> is exempt (thatsaban.exempt)."),
                java.util.Map.entry("already", "<yellow><player></yellow> is already in Layer 2."),
                java.util.Map.entry("done", "<green><player></green> was moved to <red>Layer 2</red>."),
                java.util.Map.entry("done-alts", "<gray>Also automatically moved to Layer 2: <white><alts></white>"),
                java.util.Map.entry("error", "<red>Could not apply Layer 2. Check the console."),
                java.util.Map.entry("kick-relogin", "<red>Your security level has changed. Please reconnect."),
                java.util.Map.entry("alt-suggestion-staff", "<yellow>Review required:</yellow> <white><player></white> shares a flagged IP. Review the context before taking action; if confirmed, use /offend <player>."),
                java.util.Map.entry("alt-auto-applied-staff", "<red>Automatic action:</red> <white><player></white> was moved to Layer 2 due to an IP match."),
                java.util.Map.entry("country-denied", "<red>Connections are only allowed from an IP address in Bavaria."),
                java.util.Map.entry("country-lookup-failed", "<red>Your IP address could not be geolocated locally. Please try again later.")
        );
        for (var entry : defaults.entrySet()) {
            if (!getConfig().contains("messages.en." + entry.getKey())) {
                getConfig().set("messages.en." + entry.getKey(), entry.getValue());
            }
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