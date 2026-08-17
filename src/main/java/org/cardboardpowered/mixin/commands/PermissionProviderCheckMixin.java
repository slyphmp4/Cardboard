package org.cardboardpowered.mixin.commands;

import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionProviderCheck;
import net.minecraft.server.permissions.PermissionSetSupplier;

import org.cardboardpowered.bridge.commands.CommandSourceStackBridge;
import org.cardboardpowered.bridge.commands.PermissionProviderCheckBridge;
import org.cardboardpowered.impl.command.MinecraftCommandWrapper;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(PermissionProviderCheck.class)
public abstract class PermissionProviderCheckMixin
        implements PermissionProviderCheckBridge {

    @Shadow
    @Final
    private PermissionCheck test;

    @Unique
    private CommandNode<CommandSourceStack> cardboard$vanillaNode;

    @Override
    public void cardboard$setVanillaNode(
            CommandNode<CommandSourceStack> node
    ) {
        this.cardboard$vanillaNode = node;
    }

    /**
     * Minecraft 26.2 vanilla permission check with Bukkit/LuckPerms support.
     *
     * @author Cardboard
     * @reason Bridge Minecraft 26.2 PermissionCheck into Bukkit permissions
     */
    @Overwrite
    public boolean test(PermissionSetSupplier supplier) {

        if (this.cardboard$vanillaNode != null
                && supplier instanceof CommandSourceStack commandSourceStack
                && this.test instanceof PermissionCheck.Require requiredPermission) {

            String bukkitPermission =
                    MinecraftCommandWrapper.getPermission(
                            this.cardboard$vanillaNode
                    );

            return ((CommandSourceStackBridge) commandSourceStack)
                    .cardboard$hasPermission(
                            requiredPermission.permission(),
                            bukkitPermission
                    );
        }

        // Preserve normal vanilla behaviour for everything else.
        return this.test.check(supplier.permissions());
    }
}