package org.cardboardpowered.mixin.bukkit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.cardboardpowered.bridge.bukkit.EnumRegistryBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = Registry.SimpleRegistry.class, remap = false)
public abstract class SimpleRegistryMixin<T extends Enum<T> & Keyed> implements EnumRegistryBridge<T> {
    @Shadow @Final private Class<T> type;
    @Shadow @Final @Mutable private Map<NamespacedKey, T> map;

    @Override
    public synchronized void cardboard$registerValue(T value) {
        if (value.getDeclaringClass() != this.type) throw new IllegalArgumentException("Wrong registry type");
        T previous = this.map.get(value.getKey());
        if (previous == value) return;
        if (previous != null) throw new IllegalArgumentException("Duplicate registry key: " + value.getKey());
        var updated = new LinkedHashMap<>(this.map);
        updated.put(value.getKey(), value);
        // Replace the entire snapshot so existing iterators stay valid.
        this.map = Collections.unmodifiableMap(updated);
    }
}
