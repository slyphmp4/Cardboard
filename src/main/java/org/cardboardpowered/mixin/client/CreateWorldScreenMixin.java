package org.cardboardpowered.mixin.client;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.cardboardpowered.util.IntegratedServerLifecycle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {
    @Inject(method = "openCreateWorldScreen", at = @At("HEAD"))
    private static void cardboard$preparePluginsBeforeRegistryPreview(CallbackInfo ci) {
        IntegratedServerLifecycle.prepareWorldLoad();
    }
}
