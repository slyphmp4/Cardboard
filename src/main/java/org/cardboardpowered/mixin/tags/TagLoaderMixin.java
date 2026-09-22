package org.cardboardpowered.mixin.tags;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.registry.PaperRegistryAccess;
import io.papermc.paper.tag.PaperTagListenerManager;
import io.papermc.paper.tag.TagEventConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TagLoader.class)
public abstract class TagLoaderMixin<T> {

    @Unique
    private static final ThreadLocal<TagEventConfig<?, ?>> cardboard$eventConfig = new ThreadLocal<>();

    @Inject(method = "loadPendingTags", at = @At("HEAD"))
    private static <M> void cardboard$existingRegistryTags(ResourceManager manager, Registry<M> registry, CallbackInfoReturnable<?> ci) {
        cardboard$eventConfig.set(PaperTagListenerManager.INSTANCE.createEventConfig(registry, ReloadableRegistrarEvent.Cause.INITIAL));
    }

    @Inject(method = "loadPendingTags", at = @At("RETURN"))
    private static <M> void cardboard$clearExistingRegistryTags(ResourceManager manager, Registry<M> registry, CallbackInfoReturnable<?> ci) {
        cardboard$eventConfig.remove();
    }

    @Inject(method = "loadTagsForRegistry(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/tags/TagLoader$ElementLookup;)Ljava/util/Map;", at = @At("HEAD"))
    private static <M> void cardboard$newRegistryTags(ResourceManager manager, ResourceKey<? extends Registry<M>> key, TagLoader.ElementLookup<Holder<M>> lookup, CallbackInfoReturnable<?> ci) {
        final Registry<M> registry = PaperRegistryAccess.instance().getNativeRegistry(key);
        if (registry != null) {
            cardboard$eventConfig.set(PaperTagListenerManager.INSTANCE.createEventConfig(registry, ReloadableRegistrarEvent.Cause.INITIAL));
        }
    }

    @Inject(method = "loadTagsForRegistry(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/tags/TagLoader$ElementLookup;)Ljava/util/Map;", at = @At("RETURN"))
    private static <M> void cardboard$clearNewRegistryTags(ResourceManager manager, ResourceKey<? extends Registry<M>> key, TagLoader.ElementLookup<Holder<M>> lookup, CallbackInfoReturnable<?> ci) {
        cardboard$eventConfig.remove();
    }

    @SuppressWarnings("unchecked")
    @ModifyVariable(method = "build", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Map<Identifier, List<TagLoader.EntryWithSource>> cardboard$preFlatten(Map<Identifier, List<TagLoader.EntryWithSource>> builders) {
        final TagEventConfig<?, ?> config = cardboard$eventConfig.get();
        return config == null ? builders : PaperTagListenerManager.INSTANCE.firePreFlattenEvent(builders, (TagEventConfig<?, T>) config);
    }

    @SuppressWarnings("unchecked")
    @ModifyReturnValue(method = "build", at = @At("RETURN"))
    private Map<Identifier, List<T>> cardboard$postFlatten(Map<Identifier, List<T>> tags) {
        final TagEventConfig<?, ?> config = cardboard$eventConfig.get();
        return config == null ? tags : PaperTagListenerManager.INSTANCE.firePostFlattenEvent(tags, (TagEventConfig<T, ?>) config);
    }
}
