package org.cardboardpowered.mixin.resources;

import java.util.Map;
import java.util.stream.Stream;

import com.mojang.serialization.Lifecycle;
import io.papermc.paper.registry.PaperRegistryAccess;
import io.papermc.paper.registry.PaperRegistryListenerManager;
import io.papermc.paper.registry.data.util.Conversions;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryLoadTask;
import net.minecraft.resources.RegistryLoadTask.PendingRegistration;
import net.minecraft.resources.ResourceManagerRegistryLoadTask;
import org.cardboardpowered.bridge.resources.ResourceManagerRegistryLoadTaskBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RegistryLoadTask.class)
public abstract class RegistryLoadTaskMixin<T> {

	@Shadow
	public WritableRegistry<T> registry;

	@Inject(
			method = "<init>(Lnet/minecraft/resources/RegistryDataLoader$RegistryData;Lcom/mojang/serialization/Lifecycle;Ljava/util/Map;)V",
			at = @At("TAIL")
	)
	private void cardboard$registerPaperRegistry(
			RegistryDataLoader.RegistryData<T> data,
			Lifecycle lifecycle,
			Map<?, ?> loadingErrors,
			CallbackInfo ci
	) {
		PaperRegistryAccess.instance().registerRegistry(this.registry);
	}

	@Inject(
			method = "registerElements",
			at = @At("TAIL")
	)
	private void cardboard$lockReferenceHolders(
			Stream<PendingRegistration<T>> registrations,
			CallbackInfo ci
	) {
		if ((Object) this instanceof ResourceManagerRegistryLoadTask<?>) {
			PaperRegistryAccess.instance()
					.lockReferenceHolders(this.registry.key());
			final var lookup = ((ResourceManagerRegistryLoadTaskBridge) this).cardboard$registryLookup();
			if (lookup == null) {
				throw new IllegalStateException("Registry conversions are unavailable for " + this.registry.key());
			}
			PaperRegistryListenerManager.INSTANCE.runFreezeListeners(this.registry.key(), new Conversions(lookup));
		}
	}
}
