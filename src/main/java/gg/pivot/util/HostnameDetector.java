package gg.pivot.util;

import gg.pivot.PivotPlugin;

/**
 * Extracts the hostname from a virtual host string.
 */
public class HostnameDetector {

    /**
     * Determines the correct hostname to track, falling back to a configured default
     * if the virtual host is empty or null.
     */
    public static String detectHostname(String virtualHost, PivotPlugin plugin) {
        if (virtualHost != null && !virtualHost.isEmpty()) {
            return virtualHost;
        }

        // Fallback to configured default
        String defaultHostname = plugin.getConfig().getString("api.default-hostname", "localhost");
        return (defaultHostname != null && !defaultHostname.isEmpty()) ? defaultHostname : "localhost";
    }
}
