package org.cardboardpowered.compat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Point 4 Wave 2D: explicit World#unloadChunk(x, z, false) persistence probe.
 *
 * <p>The probe flushes a GOLD_BLOCK baseline, verifies that baseline after an
 * unload/reload, writes an unsaved DIAMOND_BLOCK mutation, then invokes
 * World#unloadChunk(x, z, false). Reference Paper 26.2 build 110 calls
 * LevelChunk#tryMarkSaved before requesting the unload on this API path, so the
 * compatibility expectation is that the unsaved DIAMOND_BLOCK mutation is
 * discarded and the flushed GOLD_BLOCK baseline returns after reload.</p>
 *
 * <p>The original block data is restored and flushed before completion.</p>
 */
final class Wave2DCompatChecks {

    private static final int UNLOAD_TIMEOUT_TICKS = 600;
    private static final int VERIFY_SETTLE_TICKS = 2;

    private Wave2DCompatChecks() {
    }

    static void start(
        JavaPlugin plugin,
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail,
        BiConsumer<String, String> skip,
        Runnable completion
    ) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(
                plugin,
                () -> start(plugin, pass, fail, skip, completion)
            );
            return;
        }

        if (Bukkit.getWorlds().isEmpty()) {
            skipAll(skip, "no world available");
            completion.run();
            return;
        }

        World world = Bukkit.getWorlds().get(0);
        int[] target = findTestChunk(world);
        if (target == null) {
            skipAll(
                skip,
                "could not find a distant unloaded chunk inside the world border"
            );
            completion.run();
            return;
        }

        new ExplicitUnloadProbe(
            plugin,
            world,
            target[0],
            target[1],
            pass,
            fail,
            skip,
            completion
        ).start();
    }

    private static String[] allIds() {
        return new String[] {
            "chunk.explicit-save-false.target",
            "chunk.explicit-save-false.marker",
            "chunk.explicit-save-false.baseline-write",
            "chunk.explicit-save-false.baseline-save",
            "chunk.explicit-save-false.baseline-unload",
            "chunk.explicit-save-false.baseline-persisted",
            "chunk.explicit-save-false.mutation-write",
            "chunk.explicit-save-false.return",
            "chunk.explicit-save-false.event",
            "chunk.explicit-save-false.unloaded",
            "chunk.explicit-save-false.reload-api",
            "chunk.explicit-save-false.disk-state",
            "chunk.explicit-save-false.cleanup"
        };
    }

    private static void skipAll(BiConsumer<String, String> skip, String reason) {
        for (String id : allIds()) {
            skip.accept(id, reason);
        }
    }

    private static int[] findTestChunk(World world) {
        Location spawn = world.getSpawnLocation();
        int spawnX = spawn.getBlockX() >> 4;
        int spawnZ = spawn.getBlockZ() >> 4;
        int[] offsets = {88, 104, 120, 136, 152, -88, -104, -120, -136, -152};

        for (int offset : offsets) {
            int[][] candidates = {
                {spawnX + offset, spawnZ + offset},
                {spawnX + offset, spawnZ - offset},
                {spawnX - offset, spawnZ + offset},
                {spawnX - offset, spawnZ - offset}
            };

            for (int[] candidate : candidates) {
                int chunkX = candidate[0];
                int chunkZ = candidate[1];
                Location center = new Location(
                    world,
                    (chunkX << 4) + 8.0,
                    spawn.getY(),
                    (chunkZ << 4) + 8.0
                );

                try {
                    if (!world.getWorldBorder().isInside(center)) {
                        continue;
                    }
                    if (world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }
                    if (world.isChunkForceLoaded(chunkX, chunkZ)) {
                        continue;
                    }
                    if (!world.getPluginChunkTickets(chunkX, chunkZ).isEmpty()) {
                        continue;
                    }
                    if (playerNearChunk(world, chunkX, chunkZ, 24)) {
                        continue;
                    }
                    return new int[] {chunkX, chunkZ};
                } catch (Throwable ignored) {
                    // Environmental candidate failure: try another coordinate.
                }
            }
        }

        return null;
    }

    private static boolean playerNearChunk(
        World world,
        int chunkX,
        int chunkZ,
        int radiusChunks
    ) {
        for (Player player : world.getPlayers()) {
            Location location = player.getLocation();
            int playerX = location.getBlockX() >> 4;
            int playerZ = location.getBlockZ() >> 4;
            if (Math.abs(playerX - chunkX) <= radiusChunks
                && Math.abs(playerZ - chunkZ) <= radiusChunks) {
                return true;
            }
        }
        return false;
    }

    private enum Stage {
        PREPARE,
        BASELINE_UNLOAD,
        EXPLICIT_FALSE_UNLOAD,
        VERIFY_RELOAD,
        CLEANUP,
        FINISHED
    }

    private static final class ExplicitUnloadProbe implements Listener {
        private final JavaPlugin plugin;
        private final World world;
        private final int chunkX;
        private final int chunkZ;
        private final BiConsumer<String, String> pass;
        private final BiConsumer<String, String> fail;
        private final BiConsumer<String, String> skip;
        private final Runnable completion;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Stage stage = Stage.PREPARE;
        private int markerX;
        private int markerY;
        private int markerZ;
        private BlockData originalData;
        private boolean explicitUnloadEventSeen;

        private ExplicitUnloadProbe(
            JavaPlugin plugin,
            World world,
            int chunkX,
            int chunkZ,
            BiConsumer<String, String> pass,
            BiConsumer<String, String> fail,
            BiConsumer<String, String> skip,
            Runnable completion
        ) {
            this.plugin = plugin;
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.pass = pass;
            this.fail = fail;
            this.skip = skip;
            this.completion = completion;
        }

        private void start() {
            pass.accept(
                "chunk.explicit-save-false.target",
                world.getName() + " " + chunkX + "," + chunkZ
            );
            Bukkit.getPluginManager().registerEvents(this, plugin);

            try {
                boolean loaded = world.loadChunk(chunkX, chunkZ, true);
                if (!loaded || !world.isChunkLoaded(chunkX, chunkZ)) {
                    fail.accept(
                        "chunk.explicit-save-false.marker",
                        "target chunk failed to load before marker preparation"
                    );
                    skipAfter(
                        "chunk.explicit-save-false.marker",
                        "target chunk load failed"
                    );
                    finishWithoutCleanup();
                    return;
                }
            } catch (Throwable throwable) {
                fail.accept(
                    "chunk.explicit-save-false.marker",
                    describe(throwable)
                );
                skipAfter(
                    "chunk.explicit-save-false.marker",
                    "target chunk load failed"
                );
                finishWithoutCleanup();
                return;
            }

            Bukkit.getScheduler().runTaskLater(plugin, this::prepareMarker, 2L);
        }

        private void prepareMarker() {
            if (finished.get()) {
                return;
            }

            Block marker = findMarkerBlock();
            if (marker == null) {
                fail.accept(
                    "chunk.explicit-save-false.marker",
                    "no safe air marker location found in target chunk"
                );
                skipAfter(
                    "chunk.explicit-save-false.marker",
                    "no safe marker location"
                );
                finishWithoutCleanup();
                return;
            }

            markerX = marker.getX();
            markerY = marker.getY();
            markerZ = marker.getZ();
            originalData = marker.getBlockData();
            pass.accept(
                "chunk.explicit-save-false.marker",
                markerX + "," + markerY + "," + markerZ
                    + " original=" + marker.getType()
            );

            try {
                marker.setType(Material.GOLD_BLOCK, false);
                if (marker.getType() != Material.GOLD_BLOCK) {
                    fail.accept(
                        "chunk.explicit-save-false.baseline-write",
                        "marker did not become GOLD_BLOCK"
                    );
                    skipAfter(
                        "chunk.explicit-save-false.baseline-write",
                        "baseline marker write failed"
                    );
                    cleanup();
                    return;
                }
                pass.accept(
                    "chunk.explicit-save-false.baseline-write",
                    "temporary GOLD_BLOCK baseline written"
                );

                world.save(true);
                pass.accept(
                    "chunk.explicit-save-false.baseline-save",
                    "baseline world save flushed"
                );
            } catch (Throwable throwable) {
                fail.accept(
                    "chunk.explicit-save-false.baseline-save",
                    describe(throwable)
                );
                skipAfter(
                    "chunk.explicit-save-false.baseline-save",
                    "baseline flush failed"
                );
                cleanup();
                return;
            }

            stage = Stage.BASELINE_UNLOAD;
            requestBaselineUnload();
        }

        private Block findMarkerBlock() {
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;
            int[] local = {4, 8, 12};

            for (int localX : local) {
                for (int localZ : local) {
                    int x = baseX + localX;
                    int z = baseZ + localZ;
                    try {
                        int top = world.getHighestBlockYAt(x, z);
                        for (int delta = 2; delta <= 6; delta++) {
                            int y = top + delta;
                            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                                continue;
                            }
                            Block block = world.getBlockAt(x, y, z);
                            if (block.getType().isAir()) {
                                return block;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            return null;
        }

        private void requestBaselineUnload() {
            try {
                boolean accepted = world.unloadChunkRequest(chunkX, chunkZ);
                if (!accepted) {
                    fail.accept(
                        "chunk.explicit-save-false.baseline-unload",
                        "baseline unload request returned false"
                    );
                    skipAfter(
                        "chunk.explicit-save-false.baseline-unload",
                        "baseline unload request failed"
                    );
                    cleanup();
                    return;
                }
            } catch (Throwable throwable) {
                fail.accept(
                    "chunk.explicit-save-false.baseline-unload",
                    describe(throwable)
                );
                skipAfter(
                    "chunk.explicit-save-false.baseline-unload",
                    "baseline unload request failed"
                );
                cleanup();
                return;
            }
            waitForBaselineUnload(0);
        }

        private void waitForBaselineUnload(int elapsed) {
            if (finished.get()) {
                return;
            }

            boolean unloaded = false;
            try {
                unloaded = !world.isChunkLoaded(chunkX, chunkZ);
            } catch (Throwable ignored) {
            }

            if (unloaded) {
                pass.accept(
                    "chunk.explicit-save-false.baseline-unload",
                    "baseline chunk fully unloaded"
                );
                reloadBaseline();
                return;
            }

            if (elapsed >= UNLOAD_TIMEOUT_TICKS) {
                fail.accept(
                    "chunk.explicit-save-false.baseline-unload",
                    "baseline chunk remained loaded after "
                        + UNLOAD_TIMEOUT_TICKS + " ticks"
                );
                skipAfter(
                    "chunk.explicit-save-false.baseline-unload",
                    "baseline chunk did not unload"
                );
                cleanup();
                return;
            }

            Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> waitForBaselineUnload(elapsed + 1),
                1L
            );
        }

        private void reloadBaseline() {
            try {
                boolean loaded = world.loadChunk(chunkX, chunkZ, true);
                if (!loaded || !world.isChunkLoaded(chunkX, chunkZ)) {
                    fail.accept(
                        "chunk.explicit-save-false.baseline-persisted",
                        "baseline chunk failed to reload"
                    );
                    skipAfter(
                        "chunk.explicit-save-false.baseline-persisted",
                        "baseline reload failed"
                    );
                    cleanup();
                    return;
                }
            } catch (Throwable throwable) {
                fail.accept(
                    "chunk.explicit-save-false.baseline-persisted",
                    describe(throwable)
                );
                skipAfter(
                    "chunk.explicit-save-false.baseline-persisted",
                    "baseline reload failed"
                );
                cleanup();
                return;
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Block marker = marker();
                if (marker.getType() != Material.GOLD_BLOCK) {
                    fail.accept(
                        "chunk.explicit-save-false.baseline-persisted",
                        "expected GOLD_BLOCK after baseline reload, got "
                            + marker.getType()
                    );
                    skipAfter(
                        "chunk.explicit-save-false.baseline-persisted",
                        "known baseline did not persist"
                    );
                    cleanup();
                    return;
                }

                pass.accept(
                    "chunk.explicit-save-false.baseline-persisted",
                    "flushed GOLD_BLOCK baseline survived unload/reload"
                );
                writeMutationAndExplicitUnload();
            }, VERIFY_SETTLE_TICKS);
        }

        private void writeMutationAndExplicitUnload() {
            try {
                Block marker = marker();
                marker.setType(Material.DIAMOND_BLOCK, false);
                if (marker.getType() != Material.DIAMOND_BLOCK) {
                    fail.accept(
                        "chunk.explicit-save-false.mutation-write",
                        "marker did not become DIAMOND_BLOCK"
                    );
                    skipAfter(
                        "chunk.explicit-save-false.mutation-write",
                        "mutation write failed"
                    );
                    cleanup();
                    return;
                }
                pass.accept(
                    "chunk.explicit-save-false.mutation-write",
                    "unsaved DIAMOND_BLOCK mutation written"
                );
            } catch (Throwable throwable) {
                fail.accept(
                    "chunk.explicit-save-false.mutation-write",
                    describe(throwable)
                );
                skipAfter(
                    "chunk.explicit-save-false.mutation-write",
                    "mutation write failed"
                );
                cleanup();
                return;
            }

            explicitUnloadEventSeen = false;
            stage = Stage.EXPLICIT_FALSE_UNLOAD;

            try {
                boolean returned = world.unloadChunk(chunkX, chunkZ, false);
                if (returned) {
                    pass.accept(
                        "chunk.explicit-save-false.return",
                        "unloadChunk(x,z,false) returned true"
                    );
                } else {
                    fail.accept(
                        "chunk.explicit-save-false.return",
                        "unloadChunk(x,z,false) returned false"
                    );
                }
            } catch (Throwable throwable) {
                fail.accept(
                    "chunk.explicit-save-false.return",
                    describe(throwable)
                );
                skip.accept(
                    "chunk.explicit-save-false.event",
                    "explicit unload call failed"
                );
                skip.accept(
                    "chunk.explicit-save-false.unloaded",
                    "explicit unload call failed"
                );
                skip.accept(
                    "chunk.explicit-save-false.reload-api",
                    "explicit unload call failed"
                );
                skip.accept(
                    "chunk.explicit-save-false.disk-state",
                    "explicit unload call failed"
                );
                cleanup();
                return;
            }

            waitForExplicitUnload(0);
        }

        private void waitForExplicitUnload(int elapsed) {
            if (finished.get()) {
                return;
            }

            boolean unloaded = false;
            try {
                unloaded = !world.isChunkLoaded(chunkX, chunkZ);
            } catch (Throwable ignored) {
            }

            if (unloaded) {
                if (explicitUnloadEventSeen) {
                    pass.accept(
                        "chunk.explicit-save-false.event",
                        "ChunkUnloadEvent observed during explicit save=false unload"
                    );
                } else {
                    fail.accept(
                        "chunk.explicit-save-false.event",
                        "ChunkUnloadEvent not observed during explicit save=false unload"
                    );
                }
                pass.accept(
                    "chunk.explicit-save-false.unloaded",
                    "chunk fully unloaded after unloadChunk(x,z,false)"
                );
                verifyReload();
                return;
            }

            if (elapsed >= UNLOAD_TIMEOUT_TICKS) {
                if (explicitUnloadEventSeen) {
                    pass.accept(
                        "chunk.explicit-save-false.event",
                        "ChunkUnloadEvent observed during explicit save=false unload"
                    );
                } else {
                    fail.accept(
                        "chunk.explicit-save-false.event",
                        "ChunkUnloadEvent not observed during explicit save=false unload"
                    );
                }
                fail.accept(
                    "chunk.explicit-save-false.unloaded",
                    "chunk remained loaded after " + UNLOAD_TIMEOUT_TICKS + " ticks"
                );
                skip.accept(
                    "chunk.explicit-save-false.reload-api",
                    "chunk did not unload"
                );
                skip.accept(
                    "chunk.explicit-save-false.disk-state",
                    "chunk did not unload"
                );
                cleanup();
                return;
            }

            Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> waitForExplicitUnload(elapsed + 1),
                1L
            );
        }

        private void verifyReload() {
            stage = Stage.VERIFY_RELOAD;
            try {
                boolean loaded = world.loadChunk(chunkX, chunkZ, true);
                if (loaded && world.isChunkLoaded(chunkX, chunkZ)) {
                    pass.accept(
                        "chunk.explicit-save-false.reload-api",
                        "chunk reloaded after explicit save=false unload"
                    );
                } else {
                    fail.accept(
                        "chunk.explicit-save-false.reload-api",
                        "chunk failed to reload after explicit save=false unload"
                    );
                    skip.accept(
                        "chunk.explicit-save-false.disk-state",
                        "verification reload failed"
                    );
                    cleanup();
                    return;
                }
            } catch (Throwable throwable) {
                fail.accept(
                    "chunk.explicit-save-false.reload-api",
                    describe(throwable)
                );
                skip.accept(
                    "chunk.explicit-save-false.disk-state",
                    "verification reload failed"
                );
                cleanup();
                return;
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Material actual = marker().getType();
                if (actual == Material.GOLD_BLOCK) {
                    pass.accept(
                        "chunk.explicit-save-false.disk-state",
                        "unsaved DIAMOND_BLOCK mutation was discarded; GOLD_BLOCK baseline returned"
                    );
                } else if (actual == Material.DIAMOND_BLOCK) {
                    fail.accept(
                        "chunk.explicit-save-false.disk-state",
                        "DIAMOND_BLOCK mutation persisted despite unloadChunk(x,z,false)"
                    );
                } else {
                    fail.accept(
                        "chunk.explicit-save-false.disk-state",
                        "expected GOLD_BLOCK baseline after reload, got " + actual
                    );
                }
                cleanup();
            }, VERIFY_SETTLE_TICKS);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onChunkUnload(ChunkUnloadEvent event) {
            if (stage != Stage.EXPLICIT_FALSE_UNLOAD) {
                return;
            }
            if (!event.getWorld().equals(world)
                || event.getChunk().getX() != chunkX
                || event.getChunk().getZ() != chunkZ) {
                return;
            }
            explicitUnloadEventSeen = true;
        }

        private Block marker() {
            return world.getBlockAt(markerX, markerY, markerZ);
        }

        private void cleanup() {
            if (finished.get()) {
                return;
            }
            stage = Stage.CLEANUP;

            if (originalData == null) {
                skip.accept(
                    "chunk.explicit-save-false.cleanup",
                    "no marker was created"
                );
                finishWithoutCleanup();
                return;
            }

            try {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    world.loadChunk(chunkX, chunkZ, true);
                }
                Block marker = marker();
                marker.setBlockData(originalData, false);
                world.save(true);

                if (sameData(marker.getBlockData(), originalData)) {
                    pass.accept(
                        "chunk.explicit-save-false.cleanup",
                        "original block data restored and flushed"
                    );
                } else {
                    fail.accept(
                        "chunk.explicit-save-false.cleanup",
                        "original block data did not restore exactly"
                    );
                }
            } catch (Throwable throwable) {
                fail.accept(
                    "chunk.explicit-save-false.cleanup",
                    describe(throwable)
                );
            }

            try {
                world.unloadChunkRequest(chunkX, chunkZ);
            } catch (Throwable ignored) {
            }
            finishWithoutCleanup();
        }

        private void skipAfter(String completedId, String reason) {
            boolean after = false;
            for (String id : allIds()) {
                if (id.equals(completedId)) {
                    after = true;
                    continue;
                }
                if (after && !id.equals("chunk.explicit-save-false.cleanup")) {
                    skip.accept(id, reason);
                }
            }
        }

        private void finishWithoutCleanup() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            stage = Stage.FINISHED;
            HandlerList.unregisterAll(this);
            try {
                world.unloadChunkRequest(chunkX, chunkZ);
            } catch (Throwable ignored) {
            }
            completion.run();
        }

        private static boolean sameData(BlockData first, BlockData second) {
            return first.getAsString().equals(second.getAsString());
        }
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getName()
            + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
