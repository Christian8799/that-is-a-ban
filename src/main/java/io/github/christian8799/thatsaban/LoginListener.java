package io.github.christian8799.thatsaban;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.UUID;
import java.util.logging.Level;

final class LoginListener implements Listener {

    private final ThatsABan plugin;
    private final Layer2Service layer2;
    private final BedrockDetector bedrock;

    LoginListener(ThatsABan plugin, Layer2Service layer2, BedrockDetector bedrock) {
        this.plugin = plugin;
        this.layer2 = layer2;
        this.bedrock = bedrock;
    }

    /**
     * LOW: LuckPerms laedt seine Userdaten auf LOWEST, FoxGate/HandShaker pruefen spaeter.
     * So sind Layer-2-Nodes gesetzt, bevor die Layer-2-Plugins entscheiden.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        if (bedrock.isBedrock(uuid, name)) {
            return; // Bedrock-Spieler sind immun
        }
        try {
            layer2.handleLogin(uuid, name, event.getAddress());
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Fehler bei der Login-Pruefung von " + name, ex);
        }
    }
}
