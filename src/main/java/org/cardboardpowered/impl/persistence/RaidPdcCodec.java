package org.cardboardpowered.impl.persistence;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.world.entity.raid.Raid;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry;
import org.cardboardpowered.bridge.world.entity.raid.RaidBridge;

/** Adds one optional field without replacing vanilla's raid schema or constructors. */
public final class RaidPdcCodec {
    private static final CraftPersistentDataTypeRegistry REGISTRY = new CraftPersistentDataTypeRegistry();
    private static final MapCodec<Optional<CraftPersistentDataContainer>> VALUES =
            CraftPersistentDataContainer.createCodec(REGISTRY).lenientOptionalFieldOf("BukkitValues");

    private RaidPdcCodec() { }

    public static CraftPersistentDataTypeRegistry registry() { return REGISTRY; }

    public static MapCodec<Raid> wrap(MapCodec<Raid> original) {
        return new MapCodec<>() {
            @Override
            public <T> Stream<T> keys(DynamicOps<T> ops) {
                return Stream.concat(original.keys(ops), VALUES.keys(ops));
            }

            @Override
            public <T> DataResult<Raid> decode(DynamicOps<T> ops, MapLike<T> input) {
                return original.decode(ops, input).flatMap(raid -> VALUES.decode(ops, input).map(values -> {
                    var container = ((RaidBridge) raid).cardboard$getPdcState().container();
                    container.clear();
                    values.ifPresent(value -> container.putAll(value.getTagsCloned()));
                    return raid;
                }));
            }

            @Override
            public <T> RecordBuilder<T> encode(Raid raid, DynamicOps<T> ops, RecordBuilder<T> prefix) {
                var container = ((RaidBridge) raid).cardboard$getPdcState().container();
                return VALUES.encode(container.isEmpty() ? Optional.empty() : Optional.of(container),
                        ops, original.encode(raid, ops, prefix));
            }
        };
    }
}
