package org.bukkit.craftbukkit.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CraftLivingEntityParityTest {

    @Test
    void absentPotionEffectIsGuardedBeforeStatusEffectAccess() throws Exception {
        String source = Files.readString(Path.of("src/main/java/org/bukkit/craftbukkit/entity/CraftLivingEntity.java"));
        int method = source.indexOf("public PotionEffect getPotionEffect(PotionEffectType arg0)");
        int nullGuard = source.indexOf("if (handle == null)", method);
        int idLookup = source.indexOf("IC$get_status_effect_id(handle)", method);

        assertTrue(method >= 0);
        assertTrue(nullGuard > method);
        assertTrue(idLookup > nullGuard);
    }

    @Test
    void pickupAndGlidingUseBackedEntityState() throws Exception {
        String living = Files.readString(Path.of("src/main/java/org/bukkit/craftbukkit/entity/CraftLivingEntity.java"));
        String pickup = Files.readString(Path.of("src/main/java/org/cardboardpowered/mixin/world/entity/item/ItemEntityMixin.java"));

        assertTrue(living.contains("cardboard$getBukkitPickUpLoot()"));
        assertTrue(living.contains("cardboard$setBukkitPickUpLoot(arg0)"));
        assertTrue(living.contains("mob.setCanPickUpLoot(arg0)"));
        assertTrue(living.contains("return this.getHandle().isFallFlying();"));
        assertTrue(pickup.contains("playerEvent.setCancelled(!playerEvent.getPlayer().getCanPickupItems())"));
        assertTrue(pickup.contains("entityEvent.setCancelled(!entityEvent.getEntity().getCanPickupItems())"));
    }
}
