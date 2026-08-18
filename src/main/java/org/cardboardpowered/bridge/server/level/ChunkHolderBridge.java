package org.cardboardpowered.bridge.server.level;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public interface ChunkHolderBridge {

    // CraftBukkit start
    /*static WorldChunk getFullChunk(ChunkHolder holder) {
        if (!ChunkHolder.getLevelType(holder.lastTickLevel).isAfter(ChunkHolder.LevelType.BORDER)) return null; // note: using oldTicketLevel for isLoaded checks
        CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> statusFuture = holder.getFutureFor(ChunkStatus.FULL);
        Either<Chunk, ChunkHolder.Unloaded> either = statusFuture.getNow(null);
        return either == null ? null : (WorldChunk) either.left().orElse(null);
    }*/
    // CraftBukkit end

    static LevelChunk getFullChunkNow(ChunkHolder holder) {
        int ticketLevel = holder.oldTicketLevel;

        /*
         * C2ME's rewritten chunk system uses its own ChunkHolder subclass.
         * The inherited vanilla ticket fields are intentionally not kept
         * authoritative there, so use the holder's virtual ticket-level API.
         *
         * Keep oldTicketLevel for vanilla/Paper parity.
         */
        if (holder.getClass().getName().equals(
                "com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface"
        )) {
            ticketLevel = holder.getTicketLevel();
        }

        if (!ChunkLevel.fullStatus(ticketLevel).isOrAfter(FullChunkStatus.FULL)) {
            return null;
        }

        return getFullChunkNowUnchecked(holder);
    }
    static LevelChunk getFullChunkNowUnchecked(ChunkHolder holder) {
    	// CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> statusFuture = holder.getFutureFor(ChunkStatus.FULL);
        // Either<Chunk, ChunkHolder.Unloaded> either = statusFuture.getNow(null);
        // return (either == null) ? null : (WorldChunk) either.left().orElse(null);
        
    	return (LevelChunk) holder.getChunkIfPresentUnchecked(ChunkStatus.FULL);
    	
    	// CompletableFuture<OptionalChunk<Chunk>> statusFuture = holder.getFutureFor(ChunkStatus.FULL);
    	// OptionalChunk<Chunk>  either = statusFuture.getNow(null);
        // return (either == null) ? null : (WorldChunk) either.orElse(null);
        
    }


}
