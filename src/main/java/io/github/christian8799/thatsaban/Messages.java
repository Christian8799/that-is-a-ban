package io.github.christian8799.thatsaban;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Locale;

final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final ThatsABan plugin;

    Messages(ThatsABan plugin) {
        this.plugin = plugin;
    }

    /** Selected language, falling back to English for unsupported values. */
    String language() {
        String configured = plugin.getConfig().getString("language", "en");
        String language = configured == null ? "en" : configured.toLowerCase(Locale.ROOT);
        return language.equals("de") ? "de" : "en";
    }

    /** Message with prefix (for chat). */
    Component get(String key, TagResolver... resolvers) {
        String prefix = plugin.getConfig().getString("messages." + language() + ".prefix",
                plugin.getConfig().getString("messages.en.prefix", ""));
        return MM.deserialize(prefix + raw(key), resolvers);
    }

    /** Message without prefix (e.g. kick reason). */
    Component plain(String key, TagResolver... resolvers) {
        return MM.deserialize(raw(key), resolvers);
    }

    /** Plain localized text for commands and log output. */
    String text(String key) {
        return plugin.getConfig().getString("messages." + language() + "." + key,
                plugin.getConfig().getString("messages.en." + key, key));
    }

    private String raw(String key) {
        return text(key);
    }
}
