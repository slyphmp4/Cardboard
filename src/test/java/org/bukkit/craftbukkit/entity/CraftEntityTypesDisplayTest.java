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

        assertTrue(entityTypes.contains("EntityType.TEXT_DISPLAY, TextDisplay.class, CraftTextDisplay::new, combine(createAndSetPos(net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY), ROT)"));
        assertTrue(entityTypes.contains("EntityType.ITEM_DISPLAY, ItemDisplay.class, CraftItemDisplay::new, combine(createAndSetPos(net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY), ROT)"));
        assertTrue(entityTypes.contains("EntityType.BLOCK_DISPLAY, BlockDisplay.class, CraftBlockDisplay::new, combine(createAndSetPos(net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY), ROT)"));
        assertTrue(craftWorld.contains("Display.class.isAssignableFrom(clazz)"));
        assertTrue(craftWorld.contains("? super.createEntity(location, clazz, true)"));
    }
}
