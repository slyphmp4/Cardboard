package org.cardboardpowered.util;

import java.lang.reflect.Method;

import net.minecraft.world.entity.Entity;

/**
 * Lets Bukkit plugins see permissions from a Fabric permissions provider (e.g. LuckPerms-Fabric).
 * Reflection also allows this hook to fail closed when the optional API cannot be loaded.
 * Callers gate on FabricLoader.isModLoaded("fabric-permissions-api-v0").
 */
public class PermissionHook {

    private static final Method CHECK = checkMethod();

    public static boolean hasPermission(Entity e, String permission) {
        Method m = CHECK;
        if (m == null) return false;
        try {
            return (boolean) m.invoke(null, e, permission);
        } catch (ReflectiveOperationException | LinkageError ex) {
            return false;
        }
    }

    private static Method checkMethod() {
        try {
            return Class.forName("me.lucko.fabric.api.permissions.v0.Permissions", false,
                            PermissionHook.class.getClassLoader())
                    .getMethod("check", Entity.class, String.class);
        } catch (ReflectiveOperationException | LinkageError | SecurityException ex) {
            return null;
        }
    }

}
