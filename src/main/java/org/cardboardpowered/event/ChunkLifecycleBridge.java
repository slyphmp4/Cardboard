package org.cardboardpowered.event;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
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
 * the bridge compatible with alternate chunk schedulers such as C2ME. Bukkit's
 * logical unload boundary is the transition below {@link FullChunkStatus#FULL};
 * Fabric's physical CHUNK_UNLOAD callback can occur much later.</p>
 */
public final class ChunkLifecycleBridge implements ModInitializer {
    private static final ChunkLifecycleVisibility<LogicalChunkKey, LevelChunk> BUKKIT_VISIBILITY =
        new ChunkLifecycleVisibility<>();
    private static final ConcurrentHashMap<LogicalChunkKey, PhysicalLoadMetadata>
        PHYSICAL_LOAD_METADATA = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<PendingLifecycleEvent> PENDING_EVENTS =
        new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<LifecycleDispatchContext> ACTIVE_DISPATCH =
        new ThreadLocal<>();

    @Override
    public void onInitialize() {
        ServerChunkEvents.CHUNK_LOAD.register(this::onChunkLoad);
        ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.register(this::onFullChunkStatusChange);
        ServerChunkEvents.CHUNK_UNLOAD.register(this::onChunkUnload);
        ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
        ServerLevelEvents.UNLOAD.register((server, level) -> {
            BUKKIT_VISIBILITY.removeIf(key -> key.level() == level);
            PHYSICAL_LOAD_METADATA.keySet().removeIf(key -> key.level() == level);
            PENDING_EVENTS.removeIf(event -> event.level() == level);
        });
    }

    private void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean newlyGenerated) {
        LogicalChunkKey key = new LogicalChunkKey(
            level,
            chunk.getPos().x(),
            chunk.getPos().z()
        );
        PHYSICAL_LOAD_METADATA.put(
            key,
            new PhysicalLoadMetadata(chunk, newlyGenerated)
        );
    }

    private void onFullChunkStatusChange(
        ServerLevel level,
        LevelChunk chunk,
        FullChunkStatus oldStatus,
        FullChunkStatus newStatus
    ) {
        boolean loadTransition = crossesIntoBukkitLoaded(oldStatus, newStatus);
        boolean unloadTransition = crossesOutOfBukkitLoaded(oldStatus, newStatus);
        if (!loadTransition && !unloadTransition) {
            return;
        }

        LogicalChunkKey key = new LogicalChunkKey(
            level,
            chunk.getPos().x(),
            chunk.getPos().z()
        );

        if (loadTransition) {
            BUKKIT_VISIBILITY.beginLoad(
                key,
                chunk,
                handle -> PENDING_EVENTS.add(
                    new PendingLoadEvent(level, chunk, key, handle)
                )
            );
            return;
        }

        BUKKIT_VISIBILITY.beginUnload(
            key,
            chunk,
            handle -> PENDING_EVENTS.add(
                new PendingUnloadEvent(level, chunk, key, handle)
            )
        );
    }

    static boolean crossesIntoBukkitLoaded(
        FullChunkStatus oldStatus,
        FullChunkStatus newStatus
    ) {
        return !oldStatus.isOrAfter(FullChunkStatus.FULL)
            && newStatus.isOrAfter(FullChunkStatus.FULL);
    }

    static boolean crossesOutOfBukkitLoaded(
        FullChunkStatus oldStatus,
        FullChunkStatus newStatus
    ) {
        return oldStatus.isOrAfter(FullChunkStatus.FULL)
            && !newStatus.isOrAfter(FullChunkStatus.FULL);
    }

    private void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        LogicalChunkKey key = new LogicalChunkKey(
            level,
            chunk.getPos().x(),
            chunk.getPos().z()
        );

        /*
         * Usually END_SERVER_TICK has already delivered the logical event. If an
         * alternate scheduler physically evicts within the same tick, flush the
         * queued unload now, after Fabric's FULL callback has returned, so a
         * replacement generation cannot suppress the old unload.
         */
        clearPhysicalLoadMetadata(key, chunk);
        MinecraftServer server = level.getServer();
        PendingUnloadEvent pendingUnload = server.isSameThread()
            ? takePendingUnload(key, chunk)
            : null;
        BUKKIT_VISIBILITY.markPhysicallyUnloaded(key, chunk);
        if (pendingUnload != null) {
            dispatchBukkitChunkUnload(pendingUnload);
        }
    }

    private void onEndServerTick(MinecraftServer server) {
        int eventsToProcess = PENDING_EVENTS.size();
        for (int index = 0; index < eventsToProcess; index++) {
            PendingLifecycleEvent event = PENDING_EVENTS.poll();
            if (event == null) {
                return;
            }
            if (event.level().getServer() != server) {
                PENDING_EVENTS.add(event);
            } else if (event instanceof PendingLoadEvent loadEvent) {
                dispatchBukkitChunkLoad(loadEvent);
            } else if (event instanceof PendingUnloadEvent unloadEvent) {
                dispatchBukkitChunkUnload(unloadEvent);
            }
        }
    }

    private void dispatchBukkitChunkLoad(PendingLoadEvent pending) {
        boolean newlyGenerated = isNewlyGenerated(pending.key(), pending.chunk());
        BUKKIT_VISIBILITY.dispatchLoad(
            pending.key(),
            pending.handle(),
            () -> withDispatchContext(pending, false, () -> {
                clearPhysicalLoadMetadata(pending.key(), pending.chunk());
                if (!isBukkitWorldReady(pending.level())) {
                    return;
                }

                Chunk bukkitChunk =
                    ((LevelChunkBridge) (Object) pending.chunk()).getBukkitChunk();
                Bukkit.getPluginManager().callEvent(
                    new ChunkLoadEvent(bukkitChunk, newlyGenerated)
                );
            })
        );
    }

    private void dispatchBukkitChunkUnload(PendingUnloadEvent pending) {
        BUKKIT_VISIBILITY.dispatchUnload(
            pending.key(),
            pending.handle(),
            () -> withDispatchContext(pending, true, () -> {
                ServerLevel level = pending.level();
                if (!isBukkitWorldReady(level)) {
                    return;
                }

                pending.chunk().markUnsaved();
                Chunk bukkitChunk =
                    ((LevelChunkBridge) (Object) pending.chunk()).getBukkitChunk();
                // Paper defaults to save=true; listener overrides belong to the save path.
                ChunkUnloadEvent event = new ChunkUnloadEvent(bukkitChunk);
                Bukkit.getPluginManager().callEvent(event);
            })
        );
    }

    private static PendingUnloadEvent takePendingUnload(
        LogicalChunkKey key,
        LevelChunk owner
    ) {
        for (PendingLifecycleEvent event : PENDING_EVENTS) {
            if (event instanceof PendingUnloadEvent unload
                && unload.key().equals(key)
                && unload.chunk() == owner
                && PENDING_EVENTS.remove(event)) {
                return unload;
            }
        }
        return null;
    }

    public static boolean isBukkitChunkVisible(ServerLevel level, int chunkX, int chunkZ) {
        LogicalChunkKey key = new LogicalChunkKey(level, chunkX, chunkZ);
        if (isActiveDispatch(key)) {
            return true;
        }
        return BUKKIT_VISIBILITY.isVisible(key);
    }

    public static boolean isBukkitChunkUnloadDispatching(
        ServerLevel level,
        int chunkX,
        int chunkZ
    ) {
        LogicalChunkKey key = new LogicalChunkKey(level, chunkX, chunkZ);
        LifecycleDispatchContext active = ACTIVE_DISPATCH.get();
        if (active != null && active.key().equals(key)) {
            return active.unloading();
        }
        return BUKKIT_VISIBILITY.isUnloadPendingOrDispatching(key);
    }

    public static LevelChunk getBukkitChunkVisibleOwner(
        ServerLevel level,
        int chunkX,
        int chunkZ
    ) {
        LogicalChunkKey key = new LogicalChunkKey(level, chunkX, chunkZ);
        LifecycleDispatchContext active = ACTIVE_DISPATCH.get();
        if (active != null && active.key().equals(key)) {
            return active.owner();
        }
        return BUKKIT_VISIBILITY.visibleOwner(key);
    }

    private static boolean isActiveDispatch(LogicalChunkKey key) {
        LifecycleDispatchContext active = ACTIVE_DISPATCH.get();
        return active != null && active.key().equals(key);
    }

    private static void withDispatchContext(
        PendingLifecycleEvent pending,
        boolean unloading,
        Runnable dispatch
    ) {
        LifecycleDispatchContext previous = ACTIVE_DISPATCH.get();
        ACTIVE_DISPATCH.set(
            new LifecycleDispatchContext(pending.key(), pending.chunk(), unloading)
        );
        try {
            dispatch.run();
        } finally {
            if (previous == null) {
                ACTIVE_DISPATCH.remove();
            } else {
                ACTIVE_DISPATCH.set(previous);
            }
        }
    }

    private static boolean isNewlyGenerated(LogicalChunkKey key, LevelChunk owner) {
        PhysicalLoadMetadata metadata = PHYSICAL_LOAD_METADATA.get(key);
        return metadata != null && metadata.owner() == owner && metadata.newlyGenerated();
    }

    private static void clearPhysicalLoadMetadata(LogicalChunkKey key, LevelChunk owner) {
        PHYSICAL_LOAD_METADATA.computeIfPresent(
            key,
            (ignored, metadata) -> metadata.owner() == owner ? null : metadata
        );
    }

    private static boolean isBukkitWorldReady(ServerLevel level) {
        if (CraftServer.INSTANCE == null || Bukkit.getServer() == null) {
            return false;
        }

        return ((LevelBridge) (Object) level).cardboard$getWorld() != null;
    }

    private record LogicalChunkKey(ServerLevel level, int chunkX, int chunkZ) {
    }

    private record PhysicalLoadMetadata(LevelChunk owner, boolean newlyGenerated) {
    }

    private sealed interface PendingLifecycleEvent permits PendingLoadEvent, PendingUnloadEvent {
        ServerLevel level();

        LevelChunk chunk();

        LogicalChunkKey key();
    }

    private record LifecycleDispatchContext(
        LogicalChunkKey key,
        LevelChunk owner,
        boolean unloading
    ) {
    }

    private record PendingLoadEvent(
        ServerLevel level,
        LevelChunk chunk,
        LogicalChunkKey key,
        ChunkLifecycleVisibility.LoadHandle<LevelChunk> handle
    ) implements PendingLifecycleEvent {
    }

    private record PendingUnloadEvent(
        ServerLevel level,
        LevelChunk chunk,
        LogicalChunkKey key,
        ChunkLifecycleVisibility.UnloadHandle<LevelChunk> handle
    ) implements PendingLifecycleEvent {
    }
}
