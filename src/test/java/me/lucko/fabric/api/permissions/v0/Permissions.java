package me.lucko.fabric.api.permissions.v0;

import net.minecraft.world.entity.Entity;

/** Test-only optional provider. This class is never included in the mod JAR. */
public final class Permissions {
    public static boolean fail;
    public static int calls;
    public static String lastPermission;

    public static boolean check(Entity entity, String permission) {
        calls++;
        lastPermission = permission;
        if (fail) throw new IllegalStateException("Test provider failure");
        return permission.equals("test.allowed");
    }
}
