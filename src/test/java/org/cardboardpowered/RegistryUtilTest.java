package org.cardboardpowered;

import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class RegistryUtilTest {

    @Test
    void resolvesModdedEntityByStableKeyWhenRuntimeIdentityDiffers() {
        Identifier id = Identifier.fromNamespaceAndPath("cardboard_test", "modded_entity_identity_fallback");

        RegistryUtil.cacheModdedEntityType(
                net.minecraft.world.entity.EntityType.PIG,
                id,
                org.bukkit.entity.EntityType.PIG
        );

        assertSame(
                org.bukkit.entity.EntityType.PIG,
                RegistryUtil.getCraftTypeFromMinecraft(net.minecraft.world.entity.EntityType.COW, id)
        );
    }
}
