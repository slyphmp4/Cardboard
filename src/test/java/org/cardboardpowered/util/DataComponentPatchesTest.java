package org.cardboardpowered.util;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Codec;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import org.junit.jupiter.api.Test;

class DataComponentPatchesTest {
    @Test
    void distinguishesRemovedAbsentAndCustomComponents() {
        DataComponentType<String> value = DataComponentType.<String>builder().persistent(Codec.STRING).build();
        DataComponentType<String> removed = DataComponentType.<String>builder().persistent(Codec.STRING).build();
        DataComponentType<String> absent = DataComponentType.<String>builder().persistent(Codec.STRING).build();
        DataComponentPatch patch = DataComponentPatch.builder().set(value, "mod payload").remove(removed).build();
        Map<DataComponentType<?>, Optional<?>> entries = DataComponentPatches.entries(patch).stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertEquals(Optional.of("mod payload"), entries.get(value));
        assertEquals(Optional.empty(), entries.get(removed));
        assertFalse(entries.containsKey(absent));
        assertEquals(2, patch.size());
    }
}
