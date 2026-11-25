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

        getServer().getPluginManager().registerEvents(
                new LoginListener(this, layer2, bedrock, countryFilter), this);

        PluginCommand command = getCommand("offend");
        if (command != null) {
            OffendCommand offend = new OffendCommand(this, layer2, bedrock, store);
            command.setExecutor(offend);
            command.setTabCompleter(offend);
        }

        getServer().getScheduler().runTask(this, this::checkIntegrations);
    }

    private void migrateConfig() {
        int version = getConfig().getInt("config-version", 0);
        if (version >= 2) {
            return;
        }

        // Alte Installationen koennen noch die externe ipwho.is-Abfrage und
        // standardmaessig aktive, unbegrenzt gespeicherte IP-Kopplungsdaten nutzen.
        getConfig().set("country-filter.database-file", "geoip/IP2LOCATION-LITE-DB3.IPV6.BIN");
        getConfig().set("country-filter.allowed-region-names", List.of("Bayern", "Bavaria"));
        getConfig().set("alt-detection.enabled", false);
        getConfig().set("alt-detection.retention-days", 30);
        getConfig().set("config-version", 2);
        saveConfig();
        getLogger().warning("Datenschutz-Migration: IP-Kopplung wurde deaktiviert. "
                + "Die lokale GeoIP-Datenbank muss unter plugins/ThatsABan/geoip abgelegt werden.");
    }

    @Override
    public void onDisable() {
        if (store != null) {
            store.save();
        }
        if (countryFilter != null) {
            countryFilter.close();
        }
    }

    Messages messages() {
        return messages;
    }

    private void checkIntegrations() {
        warnMissing("layer1-plugins", "Layer 1");
        warnMissing("layer2-plugins", "Layer 2");
    }

    private void warnMissing(String key, String layer) {
        for (String name : getConfig().getStringList("integrations." + key)) {
            if (getServer().getPluginManager().getPlugin(name) == null) {
                getLogger().warning(layer + ": Plugin '" + name + "' ist nicht installiert. "
                        + "(Falls der Plugin-Name abweicht: config.yml -> integrations." + key + ")");
            }
        }
    }
}
