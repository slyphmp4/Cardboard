package org.bukkit.craftbukkit.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.commands.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class VanillaCommandWrapperTest {

    @Test
    void exposesCraftBukkitSenderConversionForReflectiveCommandFrameworks() throws Exception {
        assertEquals(
                CommandSourceStack.class,
                Class.forName("org.bukkit.craftbukkit.command.VanillaCommandWrapper")
                        .getMethod("getListener", CommandSender.class)
                        .getReturnType()
        );
    }
}
