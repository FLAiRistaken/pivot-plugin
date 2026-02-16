package gg.pivot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ConfigValidationTest {

    @Test
    public void testApiKeyValidation() {
        // Valid keys
        assertTrue(PivotPlugin.isValidApiKeyFormat("pvt_12345678901234567890"), "Standard key should be valid");
        assertTrue(PivotPlugin.isValidApiKeyFormat("pvt_abc_def_123_456_789"), "Key with underscores should be valid");
        assertTrue(PivotPlugin.isValidApiKeyFormat("pvt_abc-def-123-456-789"),
                "Key with hyphens should be valid (The Fix)");

        // Invalid keys
        assertFalse(PivotPlugin.isValidApiKeyFormat(null), "Null key should be invalid");
        assertFalse(PivotPlugin.isValidApiKeyFormat(""), "Empty key should be invalid");
        assertFalse(PivotPlugin.isValidApiKeyFormat("paste_your_key_here"), "Default placeholder should be invalid");
        assertFalse(PivotPlugin.isValidApiKeyFormat("short_key"), "Short key should be invalid");
        assertFalse(PivotPlugin.isValidApiKeyFormat("no_prefix_123456789012"), "Key without prefix should be invalid");
        assertFalse(PivotPlugin.isValidApiKeyFormat("pvt_invalid@char"), "Key with special chars should be invalid");
    }
}
