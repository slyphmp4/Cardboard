package org.cardboardpowered.util;

import java.io.IOException;
import java.io.InputStream;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PermissionHookTest {
    @BeforeEach
    void resetProvider() {
        Permissions.fail = false;
        Permissions.calls = 0;
        Permissions.lastPermission = null;
    }

    @Test
    void delegatesBothGrantsAndDenialsToOptionalProvider() {
        assertTrue(PermissionHook.hasPermission(null, "test.allowed"));
        assertEquals("test.allowed", Permissions.lastPermission);
        assertFalse(PermissionHook.hasPermission(null, "test.denied"));
        assertEquals("test.denied", Permissions.lastPermission);
        assertEquals(2, Permissions.calls);
    }

    @Test
    void providerFailureDoesNotBreakBukkitPermissionChecks() {
        Permissions.fail = true;
        assertFalse(PermissionHook.hasPermission(null, "test.allowed"));
        Permissions.fail = false;
        assertTrue(PermissionHook.hasPermission(null, "test.allowed"));
    }

    @Test
    void worksWithoutFabricPermissionsApiInstalled() throws Exception {
        ClassLoader isolated = new ClassLoader(PermissionHook.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("me.lucko.fabric.api.permissions.v0.Permissions")) {
                    throw new ClassNotFoundException(name);
                }
                if (!name.equals(PermissionHook.class.getName())) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        try (InputStream input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (input == null) throw new ClassNotFoundException(name);
                            byte[] bytes = input.readAllBytes();
                            type = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    }
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
        var check = isolated.loadClass(PermissionHook.class.getName()).getMethod("hasPermission", Entity.class, String.class);
        assertEquals(false, check.invoke(null, null, "test.allowed"));
        assertEquals(false, check.invoke(null, null, "test.allowed"));
        assertEquals(0, Permissions.calls);
    }
}
