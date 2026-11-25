package io.github.christian8799.thatsaban;

import net.luckperms.api.LuckPerms;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class ThatsABan extends JavaPlugin {

    private Messages messages;
    private LinkStore store;
    private CountryFilter countryFilter;

    @Override
    public void onEnable() {
        saveDefaultConfig();

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
