package io.github.christian8799.thatsaban;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prueft die Herkunft oeffentlicher IP-Adressen ueber ipwho.is.
 * Ergebnisse werden lokal zwischengespeichert, damit nicht bei jedem Login eine Anfrage noetig ist.
 */
final class CountryFilter {

    enum Result { ALLOWED, DENIED, UNAVAILABLE }

    private static final Pattern SUCCESS = Pattern.compile("\\\"success\\\"\\s*:\\s*true");
    private static final Pattern COUNTRY = Pattern.compile("\\\"country_code\\\"\\s*:\\s*\\\"([A-Za-z]{2})\\\"");
    private static final Pattern REGION = Pattern.compile("\\\"region_code\\\"\\s*:\\s*\\\"([A-Za-z0-9-]+)\\\"");
    private static final long CACHE_TTL_MILLIS = Duration.ofHours(24).toMillis();

    private final ThatsABan plugin;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    CountryFilter(ThatsABan plugin) {
        this.plugin = plugin;
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

        String ip = address.getHostAddress();
        CacheEntry cached = cache.get(ip);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) {
            return cached.bavarian() ? Result.ALLOWED : Result.DENIED;
        }

        try {
            String encodedIp = ip.replace("%", "%25");
            URI uri = URI.create("https://ipwho.is/" + encodedIp);
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            int timeout = Math.max(500, cfg.getInt("country-filter.timeout-ms", 3000));
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "ThatsABan/1.0");

            try {
                int status = connection.getResponseCode();
                if (status != 200) {
                    throw new IOException("Geolocation service returned HTTP " + status);
                }
                String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                Matcher success = SUCCESS.matcher(body);
                Matcher country = COUNTRY.matcher(body);
                Matcher region = REGION.matcher(body);
                if (!success.find() || !country.find() || !region.find()) {
                    throw new IOException("Geolocation response did not contain valid country and region codes");
                }
                boolean bavarian = "DE".equalsIgnoreCase(country.group(1))
                        && "BY".equalsIgnoreCase(region.group(1));
                cache.put(ip, new CacheEntry(bavarian, now + CACHE_TTL_MILLIS));
                return bavarian ? Result.ALLOWED : Result.DENIED;
            } finally {
                connection.disconnect();
            }
        } catch (Exception ex) {
            // Keine IP-Adresse protokollieren: Sie ist ein personenbezogenes Datum.
            plugin.getLogger().warning("GeoIP-Regionspruefung fehlgeschlagen: " + ex.getMessage());
            // Fail closed: Bei aktivierter Regionssperre wird eine nicht pruefbare IP nicht zugelassen.
            return Result.UNAVAILABLE;
        }
    }

    private record CacheEntry(boolean bavarian, long expiresAt) {}
}
