package org.cardboardpowered.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CardboardModdedItemTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        // Since 26.3, registry bootstrap and component binding are separate phases.
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(net.minecraft.data.registries.VanillaRegistries.createWorldLookup())
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
    }

    @Test
    void itemsWithoutDamageComponentHaveZeroDurability() {
        assertEquals(0, new CardboardModdedItem("minecraft:dirt").getDamage());
    }

    @Test
    void derivesRegistryIdAndToolDurability() {
        var material = new CardboardModdedItem(Items.DIAMOND_PICKAXE);
        assertEquals("minecraft:diamond_pickaxe", material.getId());
        assertEquals(1561, material.getDamage());
    }

    @Test
    void derivesBlockRegistryId() {
        var material = new CardboardModdedBlock(net.minecraft.world.level.block.Blocks.DIRT);
        assertEquals("minecraft:dirt", material.getId());
    }
}
