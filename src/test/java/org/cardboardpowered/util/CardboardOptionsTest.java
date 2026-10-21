package org.cardboardpowered.util;

import java.io.File;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardboardOptionsTest {
    @Test
    void integratedServerHasAllPluginDiscoveryDefaults() {
        var options = CardboardOptions.parse(new String[0]);
        assertEquals(new File("plugins"), options.valueOf("plugins"));
        assertEquals(new File("bukkit.yml"), options.valueOf("bukkit-settings"));
        assertEquals(new File("spigot.yml"), options.valueOf("spigot-settings"));
        assertTrue(options.valuesOf("add-plugin").isEmpty());
        assertTrue(options.valuesOf("add-plugin-dir").isEmpty());
    }

    @Test
    void dedicatedServerRetainsCustomDiscoveryOptionsAndAliases() {
        var options = CardboardOptions.parse(new String[]{"-P", "custom-plugins", "--add-plugin", "one.jar", "--add-plugin", "two.jar", "--add-plugin-dir", "extra", "--bukkit-settings", "custom.yml"});
        assertEquals(new File("custom-plugins"), options.valueOf("plugins"));
        assertEquals(new File("custom.yml"), options.valueOf("bukkit-settings"));
        assertEquals(java.util.List.of(new File("one.jar"), new File("two.jar")), options.valuesOf("add-plugin"));
        assertEquals(java.util.List.of(new File("extra")), options.valuesOf("add-plugin-dir"));
    }
}
