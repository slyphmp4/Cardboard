package org.cardboardpowered.library;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class JdbcDriverInitializationTest {

    @Test
    void initializesBundledJdbcDriversAfterAddingLibrariesToKnot() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/org/cardboardpowered/library/Libraries.java"
        ));
        String build = Files.readString(Path.of("build.gradle"));

        int addLibraries = source.indexOf("man.run()");
        int sqliteDriver = source.indexOf("loadJdbcDriver(\"org.sqlite.JDBC\")");
        int mysqlDriver = source.indexOf("loadJdbcDriver(\"com.mysql.cj.jdbc.Driver\")");

        assertTrue(addLibraries >= 0, "Bundled libraries must be added to Knot");
        assertTrue(sqliteDriver > addLibraries, "SQLite must initialize after its jar is available");
        assertTrue(mysqlDriver > addLibraries, "MySQL must initialize after its jar is available");
        assertTrue(source.contains("getLauncher().getTargetClassLoader()"),
                "Drivers must be loaded through Fabric\'s target classloader");
        assertTrue(build.contains("runtimeOnly \"org.xerial:sqlite-jdbc:3.41.0.0\""));
        assertTrue(build.contains("runtimeOnly \"com.mysql:mysql-connector-j:8.0.32\""));
    }
}
