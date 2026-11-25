package io.github.christian8799.thatsaban;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final ThatsABan plugin;

    Messages(ThatsABan plugin) {
        this.plugin = plugin;
    }

    /** Nachricht mit Praefix (fuer Chat). */
    Component get(String key, TagResolver... resolvers) {
        return MM.deserialize(plugin.getConfig().getString("messages.prefix", "") + raw(key), resolvers);
    }

    /** Nachricht ohne Praefix (z.B. Kick-Grund). */
    Component plain(String key, TagResolver... resolvers) {
        return MM.deserialize(raw(key), resolvers);
    }

    private String raw(String key) {
        return plugin.getConfig().getString("messages." + key, "<red>Fehlende Nachricht: " + key);
    }
}
