package org.cardboardpowered.impl.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RestartPlanTest {
    @TempDir Path directory;
    @Test void quotedArgumentsRemainLiteral() {
        assertEquals(List.of("start script.sh", "two words", "$(touch marker)", "literal;value", ""),
                RestartPlan.arguments("\"start script.sh\" 'two words' '$(touch marker)' 'literal;value' ''"));
        assertEquals(List.of("C:\\server\\start.cmd", "C:\\data\\world"), RestartPlan.arguments("C:\\server\\start.cmd C:\\data\\world"));
        assertThrows(IllegalArgumentException.class, () -> RestartPlan.arguments("'unclosed"));
        assertThrows(IllegalArgumentException.class, () -> RestartPlan.arguments("bad\0argument"));
    }
    @Test void onlyExistingRegularScriptsAreArmed() throws Exception {
        assertTrue(RestartPlan.create(this.directory, "missing.sh", false).isEmpty());
        assertTrue(RestartPlan.create(this.directory, "", false).isEmpty());
        Files.createDirectory(this.directory.resolve("folder"));
        assertTrue(RestartPlan.create(this.directory, "folder", false).isEmpty());
        Files.writeString(this.directory.resolve("start script.sh"), "exit 0\n");
        assertEquals(this.directory.resolve("start script.sh").toAbsolutePath(), RestartPlan.create(this.directory, "start script.sh", false).orElseThrow().script());
        var plan = RestartPlan.create(this.directory, "\"start script.sh\" \"two words\"", false).orElseThrow();
        assertEquals("two words", plan.command().getLast());
        assertEquals(this.directory.toAbsolutePath(), plan.directory());
    }
}
