package org.cardboardpowered.bridge.commands;

import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;

public interface PermissionProviderCheckBridge {

    void cardboard$setVanillaNode(
            CommandNode<CommandSourceStack> node
    );
}