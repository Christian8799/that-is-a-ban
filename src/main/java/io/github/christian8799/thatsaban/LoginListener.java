package io.github.christian8799.thatsaban;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import java.net.InetAddress;
import java.util.UUID;

final class LoginListener implements Listener {
    private final ThatsABan plugin;
    private final LuckPermsIntegration luckPerms;
    private final BedrockDetector bedrock;
    private final CountryFilterIntegration countryFilter;

    LoginListener(ThatsABan plugin, LuckPermsIntegration luckPerms, BedrockDetector bedrock,
                  CountryFilterIntegration countryFilter) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
        this.bedrock = bedrock;
        this.countryFilter = countryFilter;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        InetAddress address = event.getAddress();

        if (countryFilter != null && countryFilter.isAvailable()) {
            CountryFilterIntegration.Result country = countryFilter.check(address);
            if (country == CountryFilterIntegration.Result.DENIED) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        plugin.messages().plain("country-denied"));
                return;
            }
            if (country == CountryFilterIntegration.Result.UNAVAILABLE) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        plugin.messages().plain("country-lookup-failed"));
                return;
            }
        }

        if (luckPerms == null) return;
        if (bedrock.isBedrock(uuid, name)) {
            try {
                luckPerms.recordNetworkLogin(uuid, name, address);
            } catch (Exception ignored) {
                plugin.getLogger().warning("Login-Netzwerkerfassung fehlgeschlagen; Details werden nicht protokolliert.");
            }
            return;
        }
        try {
            luckPerms.handleLogin(uuid, name, address);
        } catch (Exception ignored) {
            plugin.getLogger().warning("Login-Prüfung fehlgeschlagen; Details werden nicht protokolliert.");
        }
    }
}