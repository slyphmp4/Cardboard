package org.cardboardpowered.mixin.resources;

import java.util.concurrent.Executor;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceManagerRegistryLoadTask;
import org.cardboardpowered.bridge.resources.ResourceManagerRegistryLoadTaskBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ResourceManagerRegistryLoadTask.class)
public class ResourceManagerRegistryLoadTaskMixin implements ResourceManagerRegistryLoadTaskBridge {

    @Unique
    private RegistryOps.RegistryInfoLookup cardboard$registryLookup;

    @Inject(method = "load", at = @At("HEAD"))
    private void cardboard$saveRegistryLookup(RegistryOps.RegistryInfoLookup context, Executor executor, CallbackInfoReturnable<?> ci) {
        this.cardboard$registryLookup = context;
    }

    @Override
    public RegistryOps.RegistryInfoLookup cardboard$registryLookup() {
        return this.cardboard$registryLookup;
    }
}
