package org.cardboardpowered.mixin.resources;

import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryLoadTask;
import net.minecraft.resources.RegistryOps;
import org.cardboardpowered.registry.BuilderRegistryInfoLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RegistryDataLoader.class)
public class RegistryDataLoaderMixin {

    @Inject(method = "createContext", at = @At("RETURN"), cancellable = true)
    private static void cardboard$attachBuilderLookup(
            List<HolderLookup.RegistryLookup<?>> registries,
            List<RegistryLoadTask<?>> tasks,
            CallbackInfoReturnable<RegistryOps.RegistryInfoLookup> cir) {
        // Keep vanilla's concurrent registration getters for cross-registry decoding.
        // Paper builders also need access to values already loaded into each registry.
        HolderLookup.Provider builders = HolderLookup.Provider.create(Stream.concat(
                registries.stream(), tasks.stream().map(task -> task.registry)));
        cir.setReturnValue(new BuilderRegistryInfoLookup(cir.getReturnValue(), builders));
    }
}
