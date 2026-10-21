package org.cardboardpowered.mixin.client;

import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.cardboardpowered.util.IntegratedServerLifecycle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void cardboard$preparePluginsBeforeWorldLoading(CallbackInfo ci) {
        IntegratedServerLifecycle.prepareWorldLoad();
    }
}
