package org.bukkit.craftbukkit.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CraftEntityTypesDisplayTest {

    @Test
    void displayFamilyRegistrationAndWorldRoutingStayWired() throws Exception {
        String entityTypes = Files.readString(Path.of("src/main/java/org/bukkit/craftbukkit/entity/CraftEntityTypes.java"));
        String craftWorld = Files.readString(Path.of("src/main/java/org/cardboardpowered/impl/world/CraftWorld.java"));

        assertTrue(entityTypes.contains("EntityType.TEXT_DISPLAY, TextDisplay.class, CraftTextDisplay::new"));
        assertTrue(entityTypes.contains("EntityTypes.TEXT_DISPLAY"));
        assertTrue(entityTypes.contains("EntityType.ITEM_DISPLAY, ItemDisplay.class, CraftItemDisplay::new"));
        assertTrue(entityTypes.contains("EntityTypes.ITEM_DISPLAY"));
        assertTrue(entityTypes.contains("EntityType.BLOCK_DISPLAY, BlockDisplay.class, CraftBlockDisplay::new"));
        assertTrue(entityTypes.contains("EntityTypes.BLOCK_DISPLAY"));
        assertTrue(craftWorld.contains("Display.class.isAssignableFrom(clazz)"));
        assertTrue(craftWorld.contains("? super.createEntity(location, clazz, true)"));
    }
}
