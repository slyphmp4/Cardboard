package io.papermc.paper.registry;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.types.CardboardLifecycleEventRunner;
import io.papermc.paper.plugin.lifecycle.event.types.CardboardPrioritizableEventType;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType;
import io.papermc.paper.registry.RegistryBuilder;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.data.util.Conversions;
import io.papermc.paper.registry.entry.RegistryEntry;
import io.papermc.paper.registry.entry.RegistryEntryInfo;
import io.papermc.paper.registry.entry.RegistryEntryMeta;
import io.papermc.paper.registry.event.RegistryEntryAddEvent;
import io.papermc.paper.registry.event.RegistryEventMap;
import io.papermc.paper.registry.event.RegistryEventProvider;
import io.papermc.paper.registry.event.RegistryComposeEvent;
import io.papermc.paper.registry.event.type.CardboardRegistryEntryAddEventType;
import io.papermc.paper.registry.event.type.RegistryEntryAddEventType;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.bukkit.Keyed;
import org.cardboardpowered.Registries_Bridge;
import org.cardboardpowered.bridge.core.MappedRegistryBridge;
import org.checkerframework.checker.nullness.qual.Nullable;

public class PaperRegistryListenerManager {
    public static final PaperRegistryListenerManager INSTANCE = new PaperRegistryListenerManager();
    
    public final RegistryEventMap valueAddEventTypes = new RegistryEventMap("value add");
    public final RegistryEventMap composeEventTypes = new RegistryEventMap("compose");

    private PaperRegistryListenerManager() {
    }

    public <M> M registerWithListeners(Registry<M> registry, String id, M nms) {
        return this.registerWithListeners(registry, Identifier.withDefaultNamespace(id), nms);
    }

    public <M> M registerWithListeners(Registry<M> registry, Identifier loc, M nms) {
        return this.registerWithListeners(registry, ResourceKey.create(registry.key(), loc), nms);
    }

    public <M> M registerWithListeners(Registry<M> registry, ResourceKey<M> key, M nms) {
        return (M)this.registerWithListeners(registry, key, nms, net.minecraft.core.RegistrationInfo.BUILT_IN, PaperRegistryListenerManager::registerWithInstance, Registries_Bridge.BUILT_IN_CONVERSIONS);
    }

    public <M> net.minecraft.core.Holder.Reference<M> registerForHolderWithListeners(Registry<M> registry, Identifier loc, M nms) {
        return this.registerForHolderWithListeners(registry, ResourceKey.create(registry.key(), loc), nms);
    }

    public <M> net.minecraft.core.Holder.Reference<M> registerForHolderWithListeners(Registry<M> registry, ResourceKey<M> key, M nms) {
        return this.registerWithListeners(registry, key, nms, net.minecraft.core.RegistrationInfo.BUILT_IN, WritableRegistry::register, Registries_Bridge.BUILT_IN_CONVERSIONS);
    }

    public <M> void registerWithListeners(Registry<M> registry, ResourceKey<M> key, M nms, net.minecraft.core.RegistrationInfo registrationInfo, Conversions conversions) {
        this.registerWithListeners(registry, key, nms, registrationInfo, WritableRegistry::register, conversions);
    }
    
    

    public <M, T extends Keyed, B extends PaperRegistryBuilder<M, T>, R> R registerWithListeners(
    		Registry<M> registry,
    		ResourceKey<M> key,
    		M nms,
    		net.minecraft.core.RegistrationInfo registrationInfo,
    		RegisterMethod<M, R> registerMethod,
    		Conversions conversions
    	) {
        @Nullable RegistryEntry<M, T> entry = PaperRegistries.getEntry(registry.key());
        if (entry == null || !entry.meta().modificationApiSupport().canModify() || !this.valueAddEventTypes.hasHandlers(entry.apiKey())) {
            return (R) registerMethod.register((WritableRegistry)registry, key, nms, registrationInfo);
        }
        @SuppressWarnings("unchecked")
        final RegistryEntryMeta.Buildable<M, T, B> meta = (RegistryEntryMeta.Buildable<M, T, B>) entry.meta();
        return this.registerWithListeners(registry, meta, key, nms, meta.builderFiller().fill(conversions, nms), registrationInfo, registerMethod, conversions);
    }
    
    <M, T extends Keyed, B extends PaperRegistryBuilder<M, T>> void registerWithListeners( // TODO remove Keyed
            final WritableRegistry<M> registry,
            final RegistryEntryMeta.Buildable<M, T, B> entry,
            final ResourceKey<M> key,
            final B builder,
            final net.minecraft.core.RegistrationInfo registrationInfo,
            final Conversions conversions
        ) {
            if (!entry.modificationApiSupport().canModify() || !this.valueAddEventTypes.hasHandlers(entry.apiKey())) {
                registry.register(key, builder.build(), registrationInfo);
                return;
            }
            this.registerWithListeners(registry, entry, key, null, builder, registrationInfo, WritableRegistry::register, conversions);
        }
    
    
    
    

    <M, T extends Keyed, B extends PaperRegistryBuilder<M, T>> void registerWithListeners(WritableRegistry<M> registry, RegistryEntryInfo<M, T> entry, ResourceKey<M> key, B builder, net.minecraft.core.RegistrationInfo registrationInfo, Conversions conversions) {
        // if (!RegistryEntry.Modifiable.isModifiable(entry) || !this.valueAddEventTypes.hasHandlers(entry.apiKey())) {
            registry.register(key, builder.build(), registrationInfo);
            return;
        // }
        // this.registerWithListeners(registry, RegistryEntry.Modifiable.asModifiable(entry), key, null, builder, registrationInfo, MutableRegistry::add, conversions);
    }

    /*
    public <M, T extends Keyed, B extends PaperRegistryBuilder<M, T>, R> R registerWithListeners(Registry<M> registry, RegistryEntry.Modifiable<M, T, B> entry, RegistryKey<M> key, @Nullable M oldNms, B builder, net.minecraft.registry.entry.RegistryEntryInfo registrationInfo, RegisterMethod<M, R> registerMethod, Conversions conversions) {
        M newNms = oldNms;
        
        return (R) registerMethod.register((MutableRegistry)registry, key, newNms, registrationInfo);
    }
    */
    
    public <M, T extends Keyed, B extends PaperRegistryBuilder<M, T>, R> R registerWithListeners( // TODO remove Keyed
            final Registry<M> registry,
            final RegistryEntryMeta.Buildable<M, T, B> entry,
            final ResourceKey<M> key,
            final @Nullable M oldNms,
            final B builder,
            net.minecraft.core.RegistrationInfo registrationInfo,
            final RegisterMethod<M, R> registerMethod,
            final Conversions conversions
        ) {
            final Identifier beingAdded = key.identifier();
            final TypedKey<T> typedKey = TypedKey.create(entry.apiKey(), Key.key(beingAdded.getNamespace(), beingAdded.getPath()));
            final var event = entry.createEntryAddEvent(typedKey, builder, conversions);
            CardboardLifecycleEventRunner.fireStrict(this.valueAddEventTypes.getEventType(entry.apiKey()), event);

            if (oldNms != null) {
                ((MappedRegistryBridge<M>) registry).clearIntrusiveHolder(oldNms);
            }
            final M newNms = event.builder().build();
            if (oldNms != null && !newNms.equals(oldNms)) {
                registrationInfo = new net.minecraft.core.RegistrationInfo(Optional.empty(), com.mojang.serialization.Lifecycle.experimental());
            }
            return registerMethod.register((WritableRegistry<M>) registry, key, newNms, registrationInfo);
        }

    private static <M> M registerWithInstance(WritableRegistry<M> writableRegistry, ResourceKey<M> key, M value, net.minecraft.core.RegistrationInfo registrationInfo) {
        writableRegistry.register(key, value, registrationInfo);
        return value;
    }

    public <M, T extends Keyed, B extends PaperRegistryBuilder<M, T>> void runFreezeListeners(ResourceKey<? extends Registry<M>> resourceKey, Conversions conversions) {
        @Nullable RegistryEntry<M, T> entry = PaperRegistries.getEntry(resourceKey);
        if (entry == null || !entry.meta().modificationApiSupport().canAdd() || !this.composeEventTypes.hasHandlers(entry.apiKey())) {
            return;
        }
        @SuppressWarnings("unchecked")
        final RegistryEntryMeta.Buildable<M, T, B> meta = (RegistryEntryMeta.Buildable<M, T, B>) entry.meta();
        final WritableCraftRegistry<M, T, B> writable = PaperRegistryAccess.instance().getWritableRegistry(entry.apiKey());
        final var event = meta.createPostLoadEvent(writable, conversions);
        final int before = PaperRegistryAccess.instance().getNativeRegistry(resourceKey).size();
        CardboardLifecycleEventRunner.fireStrict(this.composeEventTypes.getEventType(entry.apiKey()), event);
        final int added = PaperRegistryAccess.instance().getNativeRegistry(resourceKey).size() - before;
        org.cardboardpowered.CardboardMod.LOGGER.info("Cardboard registry " + entry.apiKey() + " registered " + added + " entries during compose");
    }

    public <T, B extends RegistryBuilder<T>> RegistryEntryAddEventType<T, B> getRegistryValueAddEventType(RegistryEventProvider<T, B> type) {
        final RegistryEntry<?, ?> entry = PaperRegistries.getEntry(type.registryKey());
        if (entry == null || !entry.meta().modificationApiSupport().canModify()) {
            throw new IllegalArgumentException(type.registryKey() + " does not support RegistryEntryAddEvent");
        }
        return this.valueAddEventTypes.getOrCreate(type.registryKey(), CardboardRegistryEntryAddEventType::new);
    }

    public <T, B extends RegistryBuilder<T>> LifecycleEventType.Prioritizable<BootstrapContext, RegistryComposeEvent<T, B>> getRegistryComposeEventType(final RegistryEventProvider<T, B> type) {
        final RegistryEntry<?, ?> entry = PaperRegistries.getEntry(type.registryKey());
        if (entry == null || !entry.meta().modificationApiSupport().canAdd()) {
            throw new IllegalArgumentException(type.registryKey() + " does not support RegistryComposeEvent");
        }
        return this.composeEventTypes.getOrCreate(type.registryKey(), (key, name) -> new CardboardPrioritizableEventType<>(key + " / " + name));
    }

    @FunctionalInterface
    public static interface RegisterMethod<M, R> {
        public R register(WritableRegistry<M> var1, ResourceKey<M> var2, M var3, net.minecraft.core.RegistrationInfo var4);
    }
    
    
    
}
