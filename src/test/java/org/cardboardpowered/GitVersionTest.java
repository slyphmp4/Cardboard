package org.cardboardpowered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;

final class GitVersionTest {

    @Test
    void exposesStableSourceRevisionMetadata() throws IOException {
        Properties gradleProperties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("gradle.properties"))) {
            gradleProperties.load(reader);
        }

        assertEquals("org.cardboardpowered", GitVersion.MAVEN_GROUP);
        assertEquals("cardboard", GitVersion.MAVEN_NAME);
        assertEquals(gradleProperties.getProperty("mod_version"), GitVersion.VERSION);
        assertEquals("ver/26.2", GitVersion.GIT_BRANCH);

        assertTrue(GitVersion.GIT_REVISION > 0);
        assertTrue(GitVersion.GIT_SHA.matches("[0-9a-f]{40}"));
        assertTrue(GitVersion.DIRTY == 0 || GitVersion.DIRTY == 1);
    }

    @Test
    void derivesBuildFieldsFromTheCommitTimestamp() {
        assertEquals(GitVersion.GIT_DATE, GitVersion.BUILD_DATE);
        assertEquals(Instant.parse(GitVersion.GIT_DATE).toEpochMilli(), GitVersion.BUILD_UNIX_TIME);
    }
}
