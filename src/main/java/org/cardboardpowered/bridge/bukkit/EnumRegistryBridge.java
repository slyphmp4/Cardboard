package org.cardboardpowered.bridge.bukkit;

import org.bukkit.Keyed;

/** Startup registration of mod enum values into already cached Bukkit registries. */
public interface EnumRegistryBridge<T extends Enum<T> & Keyed> {
    void cardboard$registerValue(T value);
}
