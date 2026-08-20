package org.cardboardpowered.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.server.level.FullChunkStatus;
import org.junit.jupiter.api.Test;

final class ChunkLifecycleBridgeTest {
    @Test
    void onlyCrossingIntoFullStartsABukkitLoadedLifecycle() {
        assertTrue(ChunkLifecycleBridge.crossesIntoBukkitLoaded(
            FullChunkStatus.INACCESSIBLE,
            FullChunkStatus.FULL
        ));
        assertTrue(ChunkLifecycleBridge.crossesIntoBukkitLoaded(
            FullChunkStatus.INACCESSIBLE,
            FullChunkStatus.ENTITY_TICKING
        ));

        assertFalse(ChunkLifecycleBridge.crossesIntoBukkitLoaded(
            FullChunkStatus.FULL,
            FullChunkStatus.BLOCK_TICKING
        ));
        assertFalse(ChunkLifecycleBridge.crossesIntoBukkitLoaded(
            FullChunkStatus.BLOCK_TICKING,
            FullChunkStatus.ENTITY_TICKING
        ));
    }

    @Test
    void onlyCrossingBelowFullEndsABukkitLoadedLifecycle() {
        assertTrue(ChunkLifecycleBridge.crossesOutOfBukkitLoaded(
            FullChunkStatus.FULL,
            FullChunkStatus.INACCESSIBLE
        ));
        assertTrue(ChunkLifecycleBridge.crossesOutOfBukkitLoaded(
            FullChunkStatus.ENTITY_TICKING,
            FullChunkStatus.INACCESSIBLE
        ));

        assertFalse(ChunkLifecycleBridge.crossesOutOfBukkitLoaded(
            FullChunkStatus.ENTITY_TICKING,
            FullChunkStatus.BLOCK_TICKING
        ));
        assertFalse(ChunkLifecycleBridge.crossesOutOfBukkitLoaded(
            FullChunkStatus.BLOCK_TICKING,
            FullChunkStatus.FULL
        ));
    }
}
