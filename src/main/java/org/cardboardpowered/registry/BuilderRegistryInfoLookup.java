package org.cardboardpowered.registry;

import java.util.Optional;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import org.cardboardpowered.bridge.resources.RegistryInfoLookupBridge;

/** Keeps vanilla decoding and Paper builder lookups on their respective providers. */
public record BuilderRegistryInfoLookup(
        RegistryOps.RegistryInfoLookup delegate,
        HolderLookup.Provider builders) implements RegistryOps.RegistryInfoLookup, RegistryInfoLookupBridge {

    @Override
    public <T> Optional<HolderGetter<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
        return this.delegate.lookup(key);
    }

    @Override
    public HolderLookup.Provider lookupForValueCopyViaBuilders() {
        return this.builders;
    }
}
