package org.cardboardpowered.impl.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.permissions.Permission;
import org.junit.jupiter.api.Test;

class NullCommandSenderTest {
    @Test
    void syntheticSourceCannotGainPluginCommandPermissions() {
        var sender = NullCommandSender.INSTANCE;
        assertFalse(sender.hasPermission("axtrade.trade"));
        assertFalse(sender.hasPermission(new Permission("axtrade.trade")));
        assertFalse(sender.isPermissionSet("axtrade.trade"));
        assertFalse(sender.isOp());
        assertTrue(sender.getEffectivePermissions().isEmpty());
    }
}
