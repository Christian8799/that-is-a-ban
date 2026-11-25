package io.github.christian8799.thatsaban;

import com.ip2location.IP2Location;
import com.ip2location.IPResult;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Prueft die Region ausschliesslich mit einer lokalen IP2Location-BIN-Datenbank.
 * Es werden keine IP-Adressen an externe GeoIP-Dienste uebertragen oder protokolliert.
 */
final class CountryFilter {

    enum Result { ALLOWED, DENIED, UNAVAILABLE }

    private final ThatsABan plugin;
    private final IP2Location database = new IP2Location();
    private volatile boolean databaseReady;

    CountryFilter(ThatsABan plugin) {
        this.plugin = plugin;
        loadDatabase();
    }

    private void loadDatabase() {
        String configuredPath = plugin.getConfig().getString(
                "country-filter.database-file", "geoip/IP2LOCATION-LITE-DB3.BIN");
        File databaseFile = new File(plugin.getDataFolder(), configuredPath);

        if (!databaseFile.isFile() || !databaseFile.canRead()) {
            plugin.getLogger().severe("Lokale GeoIP-Datenbank fehlt oder ist nicht lesbar: "
                    + databaseFile.getPath()
                    + ". Die Regionssperre bleibt aus Sicherheitsgruenden geschlossen.");
            return;
        }

        try {
            database.Open(databaseFile.getAbsolutePath());
            databaseReady = true;
            plugin.getLogger().info("Lokale GeoIP-Datenbank geladen.");
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().severe("Lokale GeoIP-Datenbank konnte nicht geladen werden: "
                    + ex.getClass().getSimpleName()
                    + ". Die Regionssperre bleibt aus Sicherheitsgruenden geschlossen.");
        }
    }

    boolean isAvailable() {
        return !plugin.getConfig().getBoolean("country-filter.enabled", true) || databaseReady;
    }

    Result check(InetAddress address) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("country-filter.enabled", true)) {
            return Result.ALLOWED;
        }
        if (cfg.getBoolean("country-filter.allow-private-addresses", false)
                && (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress())) {
            return Result.ALLOWED;
        }
        if (!databaseReady) {
            return Result.UNAVAILABLE;
        }

        try {
            IPResult result = database.IPQuery(address.getHostAddress());
            if (result == null || !"OK".equalsIgnoreCase(result.getStatus())) {
                return Result.UNAVAILABLE;
            }

            String country = result.getCountryShort();
            String region = normalize(result.getRegion());
            Set<String> allowedRegions = cfg.getStringList(
                            "country-filter.allowed-region-names")
                    .stream()
                    .map(CountryFilter::normalize)
                    .collect(Collectors.toSet());

            boolean allowed = "DE".equalsIgnoreCase(country)
                    && region != null
                    && allowedRegions.contains(region);
            return allowed ? Result.ALLOWED : Result.DENIED;
        } catch (Exception ex) {
            // Keine IP, keinen Datenbankinhalt und keine Exception-Details protokollieren.
            plugin.getLogger().warning("Lokale GeoIP-Abfrage fehlgeschlagen "
                    + "(Details werden aus Datenschutzgruenden nicht protokolliert).");
            return Result.UNAVAILABLE;
        }
    }

    void close() {
        if (databaseReady) {
            database.Close();
            databaseReady = false;
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
