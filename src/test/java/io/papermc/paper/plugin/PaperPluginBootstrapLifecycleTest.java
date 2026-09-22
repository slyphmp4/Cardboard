package io.papermc.paper.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PaperPluginBootstrapLifecycleTest {

    @Test
    void entersPaperBootstrappersAfterProviderDiscovery() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/org/cardboardpowered/CardboardMod.java"
        ));

        int discoverProviders = source.indexOf("PluginInitializerManager.load(options)");
        int enterBootstrappers = source.indexOf("LaunchEntryPointHandler.enterBootstrappers()");

        assertTrue(discoverProviders >= 0, "Paper plugin providers must be discovered");
        assertTrue(enterBootstrappers > discoverProviders,
                "Paper bootstrappers must run after their providers are discovered");
    }
}
