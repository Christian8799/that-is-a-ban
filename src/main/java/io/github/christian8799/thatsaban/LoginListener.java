package io.github.christian8799.thatsaban;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.net.InetAddress;
import java.util.UUID;
import java.util.logging.Level;

final class LoginListener implements Listener {

    private final ThatsABan plugin;
    private final Layer2Service layer2;
    private final BedrockDetector bedrock;
    private final CountryFilter countryFilter;

    LoginListener(ThatsABan plugin, Layer2Service layer2, BedrockDetector bedrock,
                  CountryFilter countryFilter) {
        this.plugin = plugin;
        this.layer2 = layer2;
        this.bedrock = bedrock;
        this.countryFilter = countryFilter;
    }

    /**
     * Laeuft auf dem Async-Pre-Login-Thread. LuckPerms laedt seine Userdaten auf LOWEST;
     * Layer-2-Nodes werden deshalb weiterhin auf LOW gesetzt, bevor andere Plugins pruefen.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        InetAddress address = event.getAddress();

        CountryFilter.Result country = countryFilter.check(address);
        if (country == CountryFilter.Result.DENIED) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.messages().plain("country-denied"));
            return;
        }
        if (country == CountryFilter.Result.UNAVAILABLE) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.messages().plain("country-lookup-failed"));
            return;
        }

        if (bedrock.isBedrock(uuid, name)) {
            return; // Bedrock-Spieler sind weiterhin von der Layer-2-Kopplung ausgenommen.
        }
        try {
            layer2.handleLogin(uuid, name, address);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Fehler bei der Login-Pruefung von " + name, ex);
        }
    }
}
