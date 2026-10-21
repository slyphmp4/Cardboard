package me.lucko.fabric.api.permissions.v0;

import net.minecraft.world.entity.Entity;

/** An actual optional Fabric provider on the fixture classpath, with a live player. */
public final class Permissions {
    public static boolean check(Entity entity, String permission) {
        if (entity == null) throw new AssertionError("Permission check lost the live entity");
        return permission.equals("cardboard.smoke.fabric");
    }
}
