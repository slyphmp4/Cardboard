package org.cardboardpowered;

import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class RegistryUtilTest {

    @Test
    void resolvesModdedEntityByStableKeyWhenRuntimeIdentityDiffers() {
        Identifier id = Identifier.fromNamespaceAndPath("cardboard_test", "modded_entity_identity_fallback");
        net.minecraft.world.entity.EntityType<?> registeredIdentity = minecraftEntityType("pig");
        net.minecraft.world.entity.EntityType<?> runtimeIdentity = minecraftEntityType("cow");

        RegistryUtil.cacheModdedEntityType(
                registeredIdentity,
                id,
                org.bukkit.entity.EntityType.PIG
        );

        assertSame(
                org.bukkit.entity.EntityType.PIG,
                RegistryUtil.getCraftTypeFromMinecraft(runtimeIdentity, id)
        );
    }

    private static net.minecraft.world.entity.EntityType<?> minecraftEntityType(String path) {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", path);
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElseThrow();
    }
}
