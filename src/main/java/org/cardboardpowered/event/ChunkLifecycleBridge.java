package org.cardboardpowered.event;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.cardboardpowered.bridge.world.level.LevelBridge;
import org.cardboardpowered.bridge.world.level.chunk.LevelChunkBridge;

/**
 * Bridges Fabric's authoritative server chunk lifecycle callbacks to Bukkit.
 *
 * <p>Using Fabric lifecycle events rather than a vanilla ChunkHolder mixin keeps
 * the bridge compatible with alternate chunk schedulers such as C2ME. Fabric
 * guarantees that CHUNK_LOAD runs after the chunk has entered the world and
 * CHUNK_UNLOAD while the chunk is still present.</p>
 */
public final class ChunkLifecycleBridge implements ModInitializer {

    @Override
    public void onInitialize() {
        ServerChunkEvents.CHUNK_LOAD.register(this::onChunkLoad);
        ServerChunkEvents.CHUNK_UNLOAD.register(this::onChunkUnload);
    }

    private void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean newlyGenerated) {
        if (!isBukkitWorldReady(level)) {
            return;
        }

        Chunk bukkitChunk = ((LevelChunkBridge) (Object) chunk).getBukkitChunk();
        Bukkit.getPluginManager().callEvent(new ChunkLoadEvent(bukkitChunk, newlyGenerated));
    }

    private void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        if (!isBukkitWorldReady(level)) {
            return;
        }

        Chunk bukkitChunk = ((LevelChunkBridge) (Object) chunk).getBukkitChunk();
        ChunkUnloadEvent event = new ChunkUnloadEvent(bukkitChunk, chunk.isUnsaved());
        Bukkit.getPluginManager().callEvent(event);

        /*
         * The Fabric callback is intentionally used as the compatibility point
         * because it survives C2ME's chunk-system replacement. The Bukkit save
         * preference is observable to plugins here; persistence-policy parity
         * is handled separately from event delivery.
         */
    }

    private static boolean isBukkitWorldReady(ServerLevel level) {
        if (CraftServer.INSTANCE == null || Bukkit.getServer() == null) {
            return false;
        }

        return ((LevelBridge) (Object) level).cardboard$getWorld() != null;
    }
}
