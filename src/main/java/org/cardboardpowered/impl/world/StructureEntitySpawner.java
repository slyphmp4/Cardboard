package org.cardboardpowered.impl.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerLevelAccessor;
import org.bukkit.craftbukkit.util.CraftStructureTransformer;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.cardboardpowered.bridge.world.entity.EntityBridge;
import org.cardboardpowered.bridge.world.level.LevelBridge;

/** Shared addition path for captured entities and entities queued by structure callbacks. */
public final class StructureEntitySpawner {
    private StructureEntitySpawner() { }

    public static void add(ServerLevelAccessor level, Entity entity, CraftStructureTransformer transformer) {
        entity.getSelfAndPassengers().forEach(passenger -> {
            if (transformer != null && transformer.canTransformEntities() && !transformer.transformEntity(passenger)) return;
            if (level instanceof ServerLevel serverLevel) {
                var bridge = (EntityBridge) passenger;
                bridge.cardboard$setGeneration(false);
                // ProjectileLaunchEvent already has a native ServerLevel hook.
                if (!(bridge.getBukkitEntity() instanceof Projectile)
                        && !((LevelBridge) serverLevel).cardboard$getWorld().cardboard$callSpawnEvent(passenger, SpawnReason.DEFAULT)) {
                    passenger.discard();
                    return;
                }
            }
            level.addFreshEntity(passenger);
        });
    }
}
