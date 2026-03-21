package gg.pivot.util;

import gg.pivot.PivotPlugin;

/**
 * Extracts the hostname from a virtual host string.
 */
public class HostnameDetector {

    /**
     * Determines the correct hostname to track, falling back to a configured default
     * if the virtual host is empty or null.
     *
     * <p>Bukkit's {@code PlayerLoginEvent#getHostname()} may return the host with a port
     * suffix (e.g. {@code "example.com:25565"} or {@code "[::1]:25565"}). This method
     * strips the port so only the bare hostname is returned.
     */
    public static String detectHostname(String virtualHost, PivotPlugin plugin) {
        if (virtualHost != null && !virtualHost.isEmpty()) {
            return stripPort(virtualHost);
        }

        // Fallback to configured default
        String defaultHostname = plugin.getConfig().getString("api.default-hostname", "localhost");
        return (defaultHostname != null && !defaultHostname.isEmpty()) ? defaultHostname : "localhost";
    }

    /**
     * Strips the port from a host string.
     *
     * <ul>
     *   <li>IPv6 bracketed form {@code "[::1]:25565"} → {@code "[::1]"}</li>
     *   <li>IPv6 bracketed form {@code "[::1]"} → {@code "[::1]"}</li>
     *   <li>Standard form {@code "example.com:25565"} → {@code "example.com"}</li>
     *   <li>Standard form {@code "example.com"} → {@code "example.com"}</li>
     *   <li>Unbracketed IPv6 {@code "2001:db8::1"} → {@code "2001:db8::1"} (returned unchanged)</li>
     * </ul>
     */
    public static String stripPort(String host) {
        if (host.startsWith("[")) {
            // IPv6 bracketed: "[addr]:port" or "[addr]"
            int closingBracket = host.indexOf(']');
            if (closingBracket != -1) {
                return host.substring(0, closingBracket + 1);
            }
            return host;
        }
        // Only strip if there is exactly one colon (standard host:port).
        // Multiple colons means an unbracketed IPv6 literal – leave it unchanged.
        int firstColon = host.indexOf(':');
        if (firstColon != -1 && firstColon == host.lastIndexOf(':')) {
            return host.substring(0, firstColon);
        }
        return host;
    }
}
