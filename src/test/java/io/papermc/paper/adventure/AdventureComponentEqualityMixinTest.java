package io.papermc.paper.adventure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class AdventureComponentEqualityMixinTest {

    @Test
    void portsPaperAdventureComponentEqualityIntoMutableComponent() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/org/cardboardpowered/mixin/network/chat/MutableComponentMixin.java"
        ));
        String mixins = Files.readString(Path.of("src/main/resources/bukkitfabric.mixins.json"));

        assertTrue(source.contains("other instanceof AdventureComponent"));
        assertTrue(source.contains("adventureComponent.deepConverted()"));
        assertTrue(mixins.contains("network.chat.MutableComponentMixin"));
    }
}
