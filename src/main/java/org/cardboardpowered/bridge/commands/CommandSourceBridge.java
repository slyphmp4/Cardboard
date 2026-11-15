/**
 * The Bukkit for Fabric Project
 * Copyright (C) 2020-2025 Isaiah & Cardboard contributors
 */
package org.cardboardpowered.bridge.commands;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.bukkit.command.CommandSender;

public interface CommandSourceBridge {

	/**
	 */
    default CommandSender getBukkitSender(CommandSourceStack source) {
        return resolveSender(source);
    }

    static CommandSender resolveSender(CommandSourceStack source) {
        CommandSource output = source.source;
        // The initiating output is stable when /execute or withExecutor changes the executor.
        if (output instanceof net.minecraft.server.MinecraftServer) {
            return org.bukkit.craftbukkit.CraftServer.INSTANCE.getConsoleSender();
        }
        if (output instanceof net.minecraft.server.network.ServerGamePacketListenerImpl listener) {
            return (CommandSender) ((org.cardboardpowered.bridge.world.entity.EntityBridge) listener.player).getBukkitEntity();
        }
        if (source.isPlayer()) return ((CommandSourceBridge) source.getPlayer()).getBukkitSender(source);
        if (source.getEntity() != null) return ((CommandSourceBridge) source.getEntity()).getBukkitSender(source);
        throw new AbstractMethodError("No Bukkit sender for command output " + output.getClass().getName());
    }
}

