package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import org.bukkit.craftbukkit.CraftServer;

/**
 * Generic CraftBukkit wrappers for Fabric/modded entity classes which have a
 * Bukkit EntityType but no dedicated CraftEntityTypes conversion entry.
 *
 * <p>The nested wrappers preserve the broad Bukkit contract of common NMS
 * base classes so Cardboard does not degrade a projectile to a plain Entity
 * (or a mob to a plain LivingEntity) merely because its concrete class comes
 * from a mod.</p>
 */
public final class CraftModdedEntity extends CraftEntity {

    public CraftModdedEntity(CraftServer server, Entity entity) {
        super(entity);
    }

    public static final class ModdedProjectile extends CraftProjectile {

        public ModdedProjectile(CraftServer server, Projectile entity) {
            super(server, entity);
        }
    }

    public static final class ModdedThrowableProjectile extends CraftThrowableProjectile {

        public ModdedThrowableProjectile(CraftServer server, ThrowableItemProjectile entity) {
            super(server, entity);
        }
    }

    public static final class ModdedMob extends CraftMob {

        public ModdedMob(CraftServer server, Mob entity) {
            super(server, entity);
        }
    }
}
