package org.cardboardpowered.util;

import static org.junit.jupiter.api.Assertions.*;
import io.papermc.paper.world.chunk.ChunkGenerationState;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ChunkGenerationStatesTest {
    @Test
    void legacyGeneratedFlagRequiresFullSavedDataAndKeepsOldFullChunks() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        assertFalse(ChunkGenerationStates.isFull(Optional.empty()));
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", 99);
        tag.putString("Status", "minecraft:features");
        assertFalse(ChunkGenerationStates.isFull(Optional.of(tag)));
        tag.putString("Status", "minecraft:full");
        assertTrue(ChunkGenerationStates.isFull(Optional.of(tag)));
        assertEquals(ChunkGenerationState.OUTDATED, ChunkGenerationStates.fromSaved(Optional.of(tag), 100));
    }
    @Test
    void absentAndOldChunksDoNotBecomeGenerated() {
        assertEquals(ChunkGenerationState.NOT_GENERATED, ChunkGenerationStates.fromSaved(Optional.empty(), 100));
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", 99);
        assertEquals(ChunkGenerationState.OUTDATED, ChunkGenerationStates.fromSaved(Optional.of(tag), 100));
        tag.putInt("DataVersion", 101);
        assertThrows(IllegalArgumentException.class, () -> ChunkGenerationStates.fromSaved(Optional.of(tag), 100));
    }

    @Test
    void currentChunkStatusAndPendingRetrogenAreDistinguished() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", 100);
        tag.putString("Status", "minecraft:full");
        assertEquals(ChunkGenerationState.GENERATED, ChunkGenerationStates.fromSaved(Optional.of(tag), 100));
        tag.putString("Status", "minecraft:features");
        assertEquals(ChunkGenerationState.PARTIAL, ChunkGenerationStates.fromSaved(Optional.of(tag), 100));
        tag.put("below_zero_retrogen", new CompoundTag());
        assertEquals(ChunkGenerationState.OUTDATED, ChunkGenerationStates.fromSaved(Optional.of(tag), 100));
    }
}
