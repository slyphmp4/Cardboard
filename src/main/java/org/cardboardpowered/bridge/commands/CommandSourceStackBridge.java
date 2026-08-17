/**
 * Cardboard - The Bukkit for Fabric Project
 * Copyright (C) 2020-2025 Isaiah and contributors
 */
package org.cardboardpowered.bridge.commands;

import net.minecraft.server.permissions.Permission;
import org.bukkit.command.CommandSender;
import org.cardboardpowered.mixin.commands.CommandSourceStackMixin;

/**
 * Injection Interface for ServerCommandSource.
 *
 * @see CommandSourceStackMixin
 */
public interface CommandSourceStackBridge {

	CommandSender getBukkitSender();

	boolean cardboard$hasPermission(
			Permission permission,
			String bukkitPermission
	);
}