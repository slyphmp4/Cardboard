package io.papermc.paper.datacomponent;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class ModdedDataComponentAdapterTest {
    @Test
    void lateModdedComponentGetsStableUnimplementedAdapter() {
        ResourceKey<DataComponentType<?>> key = ResourceKey.create(
                Registries.DATA_COMPONENT_TYPE,
                Identifier.fromNamespaceAndPath("team_reborn_energy", "energy"));

        DataComponentAdapter<?, ?> adapter = DataComponentAdapters.adapterFor(key);
        assertTrue(adapter.isUnimplemented());
        assertSame(adapter, DataComponentAdapters.adapterFor(key));
    }
}
