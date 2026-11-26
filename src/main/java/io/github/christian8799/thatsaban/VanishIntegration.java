package io.github.christian8799.thatsaban;

import org.bukkit.entity.Player;

/** API-neutral boundary for the optional SuperVanish integration. */
interface VanishIntegration {
    boolean isAvailable();
    boolean isVanished(Player player);
    void setVanished(Player player, boolean vanished);
}
