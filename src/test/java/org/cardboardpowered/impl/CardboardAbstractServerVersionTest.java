package org.cardboardpowered.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class CardboardAbstractServerVersionTest {

    @Test
    void prefixesPaperFacingVersionWithMinecraftVersion() {
        String version = CardboardAbstractServer.formatVersion("git-abcdef0", "26.2");

        assertEquals("26.2", version.split("-", 2)[0]);
        assertTrue(version.startsWith("26.2-git-abcdef0"));
        assertTrue(version.contains("(MC: 26.2)"));
        assertTrue(version.contains("(Legacy MC: 1.21)"));
        assertTrue(Pattern.compile("(?i)\\(MC:? ([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)\\)")
                .matcher(version).find());
    }

    @Test
    void fallsBackToApiVersionWhenMinecraftVersionIsUnavailable() {
        String version = CardboardAbstractServer.formatVersion("git-abcdef0", null);

        assertTrue(version.startsWith(CardboardAbstractServer.API_VERSION + "-git-abcdef0"));
    }
}
