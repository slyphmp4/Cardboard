package io.papermc.paper.registry;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RegistryBootstrapWiringTest {

    @Test
    void suppliesRegistryEventProviderAndRunsComposeBeforeTagLoading() throws IOException {
        final String provider = Files.readString(Path.of(
                "src/main/resources/META-INF/services/io.papermc.paper.registry.event.RegistryEventTypeProvider"));
        final String registryLoader = Files.readString(Path.of(
                "src/main/java/org/cardboardpowered/mixin/resources/RegistryLoadTaskMixin.java"));
        final String tags = Files.readString(Path.of(
                "src/main/java/org/cardboardpowered/mixin/tags/TagLoaderMixin.java"));
        final String mixins = Files.readString(Path.of("src/main/resources/bukkitfabric.mixins.json"));

        assertTrue(provider.contains("RegistryEventTypeProviderImpl"));
        assertTrue(registryLoader.indexOf("lockReferenceHolders") < registryLoader.indexOf("runFreezeListeners"));
        assertTrue(tags.indexOf("firePreFlattenEvent") < tags.indexOf("firePostFlattenEvent"));
        assertTrue(mixins.contains("resources.ResourceManagerRegistryLoadTaskMixin"));
        assertTrue(mixins.contains("tags.TagLoaderMixin"));
    }
}
