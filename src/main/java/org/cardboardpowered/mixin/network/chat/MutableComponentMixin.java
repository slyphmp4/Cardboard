package org.cardboardpowered.mixin.network.chat;

import io.papermc.paper.adventure.AdventureComponent;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MutableComponent.class)
public abstract class MutableComponentMixin {

    @Inject(method = "equals", at = @At("HEAD"), cancellable = true)
    private void cardboard$matchAdventureComponent(Object other, CallbackInfoReturnable<Boolean> cir) {
        if (other instanceof AdventureComponent adventureComponent) {
            cir.setReturnValue(((MutableComponent) (Object) this).equals(adventureComponent.deepConverted()));
        }
    }
}
