package org.cardboardpowered.util;

import io.papermc.paper.world.chunk.ChunkGenerationState;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

public final class ChunkGenerationStates {
    private ChunkGenerationStates() {}

    public static ChunkGenerationState fromSaved(Optional<CompoundTag> saved, int currentVersion) {
        if (saved.isEmpty()) return ChunkGenerationState.NOT_GENERATED;
        CompoundTag tag = saved.get();
        int version = tag.getIntOr("DataVersion", -1);
        if (version > currentVersion) throw new IllegalArgumentException("Chunk was saved by a newer Minecraft version: " + version);
        if (version < currentVersion || tag.contains("below_zero_retrogen")) return ChunkGenerationState.OUTDATED;
        return SerializableChunkData.getChunkStatusFromTag(tag) == ChunkStatus.FULL
                ? ChunkGenerationState.GENERATED : ChunkGenerationState.PARTIAL;
    }
}
