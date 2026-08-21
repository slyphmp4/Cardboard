package org.cardboardpowered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

final class GitVersionTest {

    @Test
    void exposesStableSourceRevisionMetadata() {
        assertEquals("org.cardboardpowered", GitVersion.MAVEN_GROUP);
        assertEquals("cardboard", GitVersion.MAVEN_NAME);
        assertEquals("26.2.1", GitVersion.VERSION);
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
