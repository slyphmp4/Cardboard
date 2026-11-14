package org.cardboardpowered.mixin.commands;

import com.mojang.brigadier.tree.CommandNode;
import org.cardboardpowered.bridge.commands.RestrictedCommandPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.commands.Commands$1")
public class Commands_NodeInspectorMixin {
    @Inject(method = "isRestricted", at = @At("HEAD"), cancellable = true)
    private void cardboard$explicitRestriction(CommandNode<?> node, CallbackInfoReturnable<Boolean> cir) {
        if (node.getRequirement() instanceof RestrictedCommandPredicate restriction) {
            cir.setReturnValue(restriction.restricted());
        }
    }
}
