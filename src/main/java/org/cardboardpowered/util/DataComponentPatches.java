package org.cardboardpowered.util;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;

/** Legacy optional-entry view over 26.3's component patch removal markers. */
public final class DataComponentPatches {
    private DataComponentPatches() {}

    public static Set<Map.Entry<DataComponentType<?>, Optional<?>>> entries(DataComponentPatch patch) {
        Set<Map.Entry<DataComponentType<?>, Optional<?>>> entries = new LinkedHashSet<>();
        var split = patch.split();
        for (var component : split.added()) {
            entries.add(Map.entry(component.type(), Optional.of(component.value())));
        }
        for (var type : split.removed()) {
            entries.add(Map.entry(type, Optional.empty()));
        }
        return entries;
    }
}
