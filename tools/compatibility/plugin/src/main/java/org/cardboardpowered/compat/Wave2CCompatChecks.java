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
 * Point 4 Wave 2C: destructive-but-restored persistence semantics probe for
 * ChunkUnloadEvent#setSaveChunk(false).
 *
 * <p>The probe writes a temporary marker in a distant chunk, flushes a known
 * baseline to disk, changes the marker in memory, sets saveChunk=false from the
 * unload event, waits for a real unload, then reloads the chunk.
 *
 * <p>Reference Paper 26.2 build 110 persists the mutation on this event-driven
 * unload path. Point 4 therefore treats that observed behavior as the
 * compatibility baseline. Explicit World#unloadChunk(x, z, false) semantics are
 * tested separately.
 *
 * <p>The original block data is restored and flushed before completion.
 */
final class Wave2CCompatChecks {

    private static final int UNLOAD_TIMEOUT_TICKS = 600;
    private static final int VERIFY_SETTLE_TICKS = 2;

    private Wave2CCompatChecks() {
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

        SavePolicyProbe probe = new SavePolicyProbe(
            plugin,
            world,
            target[0],
            target[1],
            pass,
            fail,
            skip,
            completion
        );
        probe.start();
    }

    private static int[] findTestChunk(World world) {
        Location spawn = world.getSpawnLocation();
        int spawnX = spawn.getBlockX() >> 4;
        int spawnZ = spawn.getBlockZ() >> 4;
        int[] offsets = {80, 96, 112, 128, -80, -96, -112, -128};

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
                    // Environmental candidate failure: try another location.
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

    private static void skipAll(
        BiConsumer<String, String> skip,
        String reason
    ) {
        for (String id : allIds()) {
            skip.accept(id, reason);
        }
    }

    private static String[] allIds() {
        return new String[] {
            "chunk.save-policy.target",
            "chunk.save-policy.marker",
            "chunk.save-policy.baseline-write",
            "chunk.save-policy.baseline-save",
            "chunk.save-policy.baseline-unload",
            "chunk.save-policy.baseline-persisted",
            "chunk.save-policy.mutation-write",
            "chunk.save-policy.event",
            "chunk.save-policy.flag-false",
            "chunk.save-policy.unloaded",
            "chunk.save-policy.reload-api",
            "chunk.save-policy.disk-state",
            "chunk.save-policy.cleanup"
        };
    }

    private enum Stage {
        INITIAL,
        BASELINE_UNLOAD,
        SAVE_FALSE_UNLOAD,
        VERIFY_RELOAD,
        CLEANUP,
        FINISHED
    }

    private static final class SavePolicyProbe implements Listener {
        private final JavaPlugin plugin;
        private final World world;
        private final int chunkX;
        private final int chunkZ;
        private final BiConsumer<String, String> pass;
        private final BiConsumer<String, String> fail;
        private final BiConsumer<String, String> skip;
        private final Runnable completion;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Stage stage = Stage.INITIAL;
        private BlockData originalData;
        private int markerX;
        private int markerY;
        private int markerZ;
        private boolean saveFalseEventSeen;
        private boolean saveFalseFlagAccepted;

        private SavePolicyProbe(
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
                "chunk.save-policy.target",
                world.getName() + " " + chunkX + "," + chunkZ
            );
            Bukkit.getPluginManager().registerEvents(this, plugin);

            try {
                boolean loaded = world.loadChunk(chunkX, chunkZ, true);
                if (!loaded || !world.isChunkLoaded(chunkX, chunkZ)) {
                    fail.accept(
                        "chunk.save-policy.marker",
                        "target chunk could not be loaded for marker selection"
                    );
                    skipAfter("chunk.save-policy.marker", "target chunk load failed");
                    finishWithoutCleanup();
                    return;
                }
            } catch (Throwable throwable) {
                fail.accept("chunk.save-policy.marker", describe(throwable));
                skipAfter("chunk.save-policy.marker", "target chunk load failed");
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
                    "chunk.save-policy.marker",
                    "no safe air marker location found in target chunk"
                );
                skipAfter("chunk.save-policy.marker", "no safe marker location");
                finishWithoutCleanup();
                return;
            }

            markerX = marker.getX();
            markerY = marker.getY();
            markerZ = marker.getZ();
            originalData = marker.getBlockData();
            pass.accept(
                "chunk.save-policy.marker",
                markerX + "," + markerY + "," + markerZ
                    + " original=" + marker.getType()
            );

            try {
                marker.setType(Material.GOLD_BLOCK, false);
                if (marker.getType() == Material.GOLD_BLOCK) {
                    pass.accept(
                        "chunk.save-policy.baseline-write",
                        "temporary GOLD_BLOCK baseline written"
                    );
                } else {
                    fail.accept(
                        "chunk.save-policy.baseline-write",
                        "marker did not become GOLD_BLOCK"
                    );
                    skipAfter(
                        "chunk.save-policy.baseline-write",
                        "baseline marker write failed"
                    );
                    cleanup();
                    return;
                }

                world.save(true);
                pass.accept(
                    "chunk.save-policy.baseline-save",
                    "baseline world save flushed"
                );
            } catch (Throwable throwable) {
                fail.accept("chunk.save-policy.baseline-save", describe(throwable));
                skipAfter(
                    "chunk.save-policy.baseline-save",
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
                        "chunk.save-policy.baseline-unload",
                        "baseline unload request returned false"
                    );
                    skipAfter(
                        "chunk.save-policy.baseline-unload",
                        "baseline unload request failed"
                    );
                    cleanup();
                    return;
                }
            } catch (Throwable throwable) {
                fail.accept("chunk.save-policy.baseline-unload", describe(throwable));
                skipAfter(
                    "chunk.save-policy.baseline-unload",
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
                    "chunk.save-policy.baseline-unload",
                    "baseline chunk fully unloaded"
                );
                reloadBaseline();
                return;
            }

            if (elapsed >= UNLOAD_TIMEOUT_TICKS) {
                fail.accept(
                    "chunk.save-policy.baseline-unload",
                    "baseline chunk remained loaded after "
                        + UNLOAD_TIMEOUT_TICKS + " ticks"
                );
                skipAfter(
                    "chunk.save-policy.baseline-unload",
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
                        "chunk.save-policy.baseline-persisted",
                        "baseline chunk failed to reload"
                    );
                    skipAfter(
                        "chunk.save-policy.baseline-persisted",
                        "baseline reload failed"
                    );
                    cleanup();
                    return;
                }
            } catch (Throwable throwable) {
                fail.accept("chunk.save-policy.baseline-persisted", describe(throwable));
                skipAfter(
                    "chunk.save-policy.baseline-persisted",
                    "baseline reload failed"
                );
                cleanup();
                return;
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Block marker = marker();
                if (marker.getType() != Material.GOLD_BLOCK) {
                    fail.accept(
                        "chunk.save-policy.baseline-persisted",
                        "expected GOLD_BLOCK after baseline reload, got "
                            + marker.getType()
                    );
                    skipAfter(
                        "chunk.save-policy.baseline-persisted",
                        "known baseline did not persist"
                    );
                    cleanup();
                    return;
                }

                pass.accept(
                    "chunk.save-policy.baseline-persisted",
                    "flushed GOLD_BLOCK baseline survived unload/reload"
                );
                writeMutationAndUnload();
            }, VERIFY_SETTLE_TICKS);
        }

        private void writeMutationAndUnload() {
            try {
                Block marker = marker();
                marker.setType(Material.DIAMOND_BLOCK, false);
                if (marker.getType() == Material.DIAMOND_BLOCK) {
                    pass.accept(
                        "chunk.save-policy.mutation-write",
                        "unsaved DIAMOND_BLOCK mutation written"
                    );
                } else {
                    fail.accept(
                        "chunk.save-policy.mutation-write",
                        "marker did not become DIAMOND_BLOCK"
                    );
                    skipAfter(
                        "chunk.save-policy.mutation-write",
                        "mutation write failed"
                    );
                    cleanup();
                    return;
                }
            } catch (Throwable throwable) {
                fail.accept("chunk.save-policy.mutation-write", describe(throwable));
                skipAfter(
                    "chunk.save-policy.mutation-write",
                    "mutation write failed"
                );
                cleanup();
                return;
            }

            saveFalseEventSeen = false;
            saveFalseFlagAccepted = false;
            stage = Stage.SAVE_FALSE_UNLOAD;

            try {
                boolean accepted = world.unloadChunkRequest(chunkX, chunkZ);
                if (!accepted) {
                    fail.accept(
                        "chunk.save-policy.unloaded",
                        "save=false unload request returned false"
                    );
                    skip.accept(
                        "chunk.save-policy.event",
                        "save=false unload request failed"
                    );
                    skip.accept(
                        "chunk.save-policy.flag-false",
                        "save=false unload request failed"
                    );
                    skip.accept(
                        "chunk.save-policy.reload-api",
                        "save=false unload request failed"
                    );
                    skip.accept(
                        "chunk.save-policy.disk-state",
                        "save=false unload request failed"
                    );
                    cleanup();
                    return;
                }
            } catch (Throwable throwable) {
                fail.accept("chunk.save-policy.unloaded", describe(throwable));
                skip.accept("chunk.save-policy.event", "save=false unload request failed");
                skip.accept("chunk.save-policy.flag-false", "save=false unload request failed");
                skip.accept("chunk.save-policy.reload-api", "save=false unload request failed");
                skip.accept("chunk.save-policy.disk-state", "save=false unload request failed");
                cleanup();
                return;
            }

            waitForSaveFalseUnload(0);
        }

        private void waitForSaveFalseUnload(int elapsed) {
            if (finished.get()) {
                return;
            }

            boolean unloaded = false;
            try {
                unloaded = !world.isChunkLoaded(chunkX, chunkZ);
            } catch (Throwable ignored) {
            }

            if (unloaded) {
                recordSaveFalseEventResults();
                pass.accept(
                    "chunk.save-policy.unloaded",
                    "chunk fully unloaded after save=false event"
                );
                verifyReload();
                return;
            }

            if (elapsed >= UNLOAD_TIMEOUT_TICKS) {
                recordSaveFalseEventResults();
                fail.accept(
                    "chunk.save-policy.unloaded",
                    "chunk remained loaded after " + UNLOAD_TIMEOUT_TICKS + " ticks"
                );
                skip.accept(
                    "chunk.save-policy.reload-api",
                    "chunk did not unload"
                );
                skip.accept(
                    "chunk.save-policy.disk-state",
                    "chunk did not unload"
                );
                cleanup();
                return;
            }

            Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> waitForSaveFalseUnload(elapsed + 1),
                1L
            );
        }

        private void recordSaveFalseEventResults() {
            if (saveFalseEventSeen) {
                pass.accept(
                    "chunk.save-policy.event",
                    "ChunkUnloadEvent observed for save=false probe"
                );
            } else {
                fail.accept(
                    "chunk.save-policy.event",
                    "ChunkUnloadEvent was not observed for save=false probe"
                );
            }

            if (saveFalseFlagAccepted) {
                pass.accept(
                    "chunk.save-policy.flag-false",
                    "ChunkUnloadEvent reported saveChunk=false after listener update"
                );
            } else if (saveFalseEventSeen) {
                fail.accept(
                    "chunk.save-policy.flag-false",
                    "event did not retain saveChunk=false"
                );
            } else {
                skip.accept(
                    "chunk.save-policy.flag-false",
                    "ChunkUnloadEvent was not observed"
                );
            }
        }

        private void verifyReload() {
            stage = Stage.VERIFY_RELOAD;
            try {
                boolean loaded = world.loadChunk(chunkX, chunkZ, true);
                if (loaded && world.isChunkLoaded(chunkX, chunkZ)) {
                    pass.accept(
                        "chunk.save-policy.reload-api",
                        "chunk reloaded after save=false unload"
                    );
                } else {
                    fail.accept(
                        "chunk.save-policy.reload-api",
                        "chunk failed to reload after save=false unload"
                    );
                    skip.accept(
                        "chunk.save-policy.disk-state",
                        "verification reload failed"
                    );
                    cleanup();
                    return;
                }
            } catch (Throwable throwable) {
                fail.accept("chunk.save-policy.reload-api", describe(throwable));
                skip.accept("chunk.save-policy.disk-state", "verification reload failed");
                cleanup();
                return;
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Material actual = marker().getType();
                if (!saveFalseEventSeen || !saveFalseFlagAccepted) {
                    skip.accept(
                        "chunk.save-policy.disk-state",
                        "save=false was not established by the unload event"
                    );
                } else if (actual == Material.DIAMOND_BLOCK) {
                    pass.accept(
                        "chunk.save-policy.disk-state",
                        "DIAMOND_BLOCK mutation persisted, matching Paper 26.2 build 110"
                    );
                } else if (actual == Material.GOLD_BLOCK) {
                    fail.accept(
                        "chunk.save-policy.disk-state",
                        "mutation was discarded, differing from Paper 26.2 build 110"
                    );
                } else {
                    fail.accept(
                        "chunk.save-policy.disk-state",
                        "expected GOLD_BLOCK baseline after reload, got " + actual
                    );
                }
                cleanup();
            }, VERIFY_SETTLE_TICKS);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onChunkUnload(ChunkUnloadEvent event) {
            if (stage != Stage.SAVE_FALSE_UNLOAD || !matches(event)) {
                return;
            }

            saveFalseEventSeen = true;
            event.setSaveChunk(false);
            saveFalseFlagAccepted = !event.isSaveChunk();
        }

        private boolean matches(ChunkUnloadEvent event) {
            return event.getWorld().equals(world)
                && event.getChunk().getX() == chunkX
                && event.getChunk().getZ() == chunkZ;
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
                skip.accept("chunk.save-policy.cleanup", "no marker was created");
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
                        "chunk.save-policy.cleanup",
                        "original block data restored and flushed"
                    );
                } else {
                    fail.accept(
                        "chunk.save-policy.cleanup",
                        "original block data did not restore exactly"
                    );
                }
            } catch (Throwable throwable) {
                fail.accept("chunk.save-policy.cleanup", describe(throwable));
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
                if (after && !id.equals("chunk.save-policy.cleanup")) {
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
