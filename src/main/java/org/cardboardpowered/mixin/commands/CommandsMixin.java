/**
 * CardboardPowered - Bukkit/Spigot for Fabric
 * Copyright (C) CardboardPowered.org and contributors
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.cardboardpowered.mixin.commands;

import com.google.common.collect.Maps;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionProviderCheck;

import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandSendEvent;

import org.cardboardpowered.bridge.commands.PermissionProviderCheckBridge;
import org.cardboardpowered.bridge.server.level.ServerPlayerBridge;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;

@Mixin(Commands.class)
public class CommandsMixin {

    @Shadow
    public com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher;

    @Shadow
    @Final
    private static ClientboundCommandsPacket.NodeInspector<CommandSourceStack> COMMAND_NODE_INSPECTOR;

    @Shadow
    private static <S> void fillUsableCommands(
            CommandNode<S> root,
            CommandNode<S> newRoot,
            S source,
            Map<CommandNode<S>, CommandNode<S>> nodes
    ) {
    }

    /**
     * Minecraft 26.2 stores vanilla command permissions inside
     * PermissionProviderCheck predicates.
     *
     * Bind each predicate to its actual root Brigadier command node so that
     * PermissionProviderCheckMixin can translate it to the Bukkit permission
     * such as "minecraft.command.gamemode".
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void cardboard$bindVanillaPermissionNodes(
            Commands.CommandSelection commandSelection,
            CommandBuildContext context,
            CallbackInfo ci
    ) {
        for (CommandNode<CommandSourceStack> node :
                this.dispatcher.getRoot().getChildren()) {

            if (node.getRequirement()
                    instanceof PermissionProviderCheck<?> permissionCheck) {

                ((PermissionProviderCheckBridge) (Object) permissionCheck)
                        .cardboard$setVanillaNode(node);
            }
        }
    }

    /**
     * Build the command tree for this specific player, expose its top-level
     * labels through Bukkit's PlayerCommandSendEvent, then actually apply any
     * removals plugins made to the event before the Brigadier tree is sent to
     * the client.
     *
     * Cardboard previously fired PlayerCommandSendEvent but ignored changes to
     * event.getCommands(), which made command-hiding plugins unable to remove
     * commands from client-side slash/TAB suggestions.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(at = @At("HEAD"), method = "sendCommands", cancellable = true)
    private void cardboard$filterAndSendCommands(
            ServerPlayer entityplayer,
            CallbackInfo ci
    ) {
        Map<CommandNode<CommandSourceStack>, CommandNode<CommandSourceStack>> map =
                Maps.newIdentityHashMap();

        RootCommandNode<CommandSourceStack> rootCommandNode =
                new RootCommandNode<>();

        map.put(this.dispatcher.getRoot(), rootCommandNode);

        fillUsableCommands(
                this.dispatcher.getRoot(),
                rootCommandNode,
                entityplayer.createCommandSourceStack(),
                (Map) map
        );

        Collection<String> originalCommands = new LinkedHashSet<>();
        for (CommandNode<CommandSourceStack> node : rootCommandNode.getChildren()) {
            originalCommands.add(node.getName());
        }

        PlayerCommandSendEvent event = new PlayerCommandSendEvent(
                (Player) ((ServerPlayerBridge) entityplayer).getBukkitEntity(),
                new LinkedHashSet<>(originalCommands)
        );
        CraftEventFactory.callEvent(event);

        for (String command : originalCommands) {
            if (!event.getCommands().contains(command)) {
                rootCommandNode.removeCommand(command);
            }
        }

        entityplayer.connection.send(
                new ClientboundCommandsPacket(rootCommandNode, COMMAND_NODE_INSPECTOR)
        );

        ci.cancel();
    }
}
