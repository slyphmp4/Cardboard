package org.bukkit.craftbukkit.command;

import net.minecraft.commands.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.cardboardpowered.impl.command.MinecraftCommandWrapper;

/**
 * CraftBukkit compatibility entry point for command frameworks that reflect
 * the vanilla command sender conversion method.
 */
public final class VanillaCommandWrapper {

    private VanillaCommandWrapper() {
    }

    public static CommandSourceStack getListener(CommandSender sender) {
        CommandSourceStack source = MinecraftCommandWrapper.getCommandSource(sender);
        if (source == null) {
            throw new IllegalArgumentException("Cannot make " + sender + " a vanilla command listener");
        }
        return source;
    }
}
