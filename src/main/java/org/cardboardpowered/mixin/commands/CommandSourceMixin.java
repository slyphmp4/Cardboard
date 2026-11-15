package org.cardboardpowered.mixin.commands;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.spongepowered.asm.mixin.Mixin;
import org.cardboardpowered.bridge.commands.CommandSourceBridge;

@Mixin(CommandSource.class)
public interface CommandSourceMixin extends CommandSourceBridge {
    @Override
    default CommandSender getBukkitSender(CommandSourceStack source) {
        return CommandSourceBridge.resolveSender(source);
    }
}
