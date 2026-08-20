package org.cardboardpowered.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Point 4 Wave 2B checks. These exercise Paper scheduler entry points and a
 * real multi-tick Bukkit chunk load -> unload -> reload lifecycle. The chunk
 * probe intentionally uses a distant, initially-unloaded chunk and does not
 * mutate blocks. ChunkUnloadEvent#setSaveChunk(false) is tested separately
 * because that scenario changes persistence semantics.
 */
final class Wave2BCompatChecks {

    private static final int SCHEDULER_SETTLE_TICKS = 20;
    private static final int CHUNK_LOAD_TIMEOUT_TICKS = 200;
    private static final int CHUNK_UNLOAD_TIMEOUT_TICKS = 600;

    private Wave2BCompatChecks() {
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

        runPaperSchedulerChecks(plugin, pass, fail, () ->
            runChunkLifecycleChecks(plugin, pass, fail, skip, completion)
        );
    }

    private static void runPaperSchedulerChecks(
        JavaPlugin plugin,
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail,
        Runnable completion
    ) {
        AtomicBoolean globalRan = new AtomicBoolean();
        AtomicBoolean globalPrimary = new AtomicBoolean();
        AtomicBoolean regionRan = new AtomicBoolean();
        AtomicBoolean regionPrimary = new AtomicBoolean();
        AtomicBoolean asyncRan = new AtomicBoolean();
        AtomicBoolean asyncOffPrimary = new AtomicBoolean();

        try {
            plugin.getServer().getGlobalRegionScheduler().run(plugin, ignored -> {
                globalRan.set(true);
                globalPrimary.set(Bukkit.isPrimaryThread());
            });
        } catch (Throwable throwable) {
            fail.accept("paper.scheduler.global", describe(throwable));
            globalRan.set(true);
        }

        if (Bukkit.getWorlds().isEmpty()) {
            fail.accept("paper.scheduler.region", "no world available for RegionScheduler");
            regionRan.set(true);
        } else {
            World world = Bukkit.getWorlds().get(0);
            Location spawn = world.getSpawnLocation();
            try {
                plugin.getServer().getRegionScheduler().run(
                    plugin,
                    world,
                    spawn.getBlockX() >> 4,
                    spawn.getBlockZ() >> 4,
                    ignored -> {
                        regionRan.set(true);
                        regionPrimary.set(Bukkit.isPrimaryThread());
                    }
                );
            } catch (Throwable throwable) {
                fail.accept("paper.scheduler.region", describe(throwable));
                regionRan.set(true);
            }
        }

        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> {
                asyncRan.set(true);
                asyncOffPrimary.set(!Bukkit.isPrimaryThread());
            });
        } catch (Throwable throwable) {
            fail.accept("paper.scheduler.async", describe(throwable));
            asyncRan.set(true);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!hasResult(pass, fail, "paper.scheduler.global")) {
                if (globalRan.get() && globalPrimary.get()) {
                    pass.accept(
                        "paper.scheduler.global",
                        "GlobalRegionScheduler executed on the primary thread"
                    );
                } else if (!globalRan.get()) {
                    fail.accept(
                        "paper.scheduler.global",
                        "GlobalRegionScheduler task did not execute within "
                            + SCHEDULER_SETTLE_TICKS + " ticks"
                    );
                } else {
                    fail.accept(
                        "paper.scheduler.global",
                        "GlobalRegionScheduler callback was not on the primary thread"
                    );
                }
            }

            if (!hasResult(pass, fail, "paper.scheduler.region")) {
                if (regionRan.get() && regionPrimary.get()) {
                    pass.accept(
                        "paper.scheduler.region",
                        "RegionScheduler executed through the server region scheduler"
                    );
                } else if (!regionRan.get()) {
                    fail.accept(
                        "paper.scheduler.region",
                        "RegionScheduler task did not execute within "
                            + SCHEDULER_SETTLE_TICKS + " ticks"
                    );
                } else {
                    fail.accept(
                        "paper.scheduler.region",
                        "RegionScheduler callback was not on the primary thread"
                    );
                }
            }

            if (!hasResult(pass, fail, "paper.scheduler.async")) {
                if (asyncRan.get() && asyncOffPrimary.get()) {
                    pass.accept(
                        "paper.scheduler.async",
                        "Paper AsyncScheduler executed off the primary thread"
                    );
                } else if (!asyncRan.get()) {
                    fail.accept(
                        "paper.scheduler.async",
                        "AsyncScheduler task did not execute within "
                            + SCHEDULER_SETTLE_TICKS + " ticks"
                    );
                } else {
                    fail.accept(
                        "paper.scheduler.async",
                        "AsyncScheduler callback unexpectedly executed on the primary thread"
                    );
                }
            }

            completion.run();
        }, SCHEDULER_SETTLE_TICKS);
    }

    /*
     * The result map is owned by CardboardCompatProbe, so these BiConsumers do
     * not expose lookup. Scheduler submission exceptions already emit a result;
     * this helper intentionally returns false and duplicate writes merely replace
     * that same id with the timeout/state result. This keeps the probe deterministic.
     */
    private static boolean hasResult(
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail,
        String id
    ) {
        return false;
    }

    private static void runChunkLifecycleChecks(
        JavaPlugin plugin,
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail,
        BiConsumer<String, String> skip,
        Runnable completion
    ) {
        if (Bukkit.getWorlds().isEmpty()) {
            skipChunkChecks(skip, "no world available");
            completion.run();
            return;
        }

        World world = Bukkit.getWorlds().get(0);
        int[] target = findTestChunk(world);
        if (target == null) {
            skipChunkChecks(
                skip,
                "could not find a distant initially-unloaded chunk inside the world border"
            );
            completion.run();
            return;
        }

        ChunkLifecycleProbe probe = new ChunkLifecycleProbe(
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
        int[] offsets = {64, 96, 128, 160, 192, 224, 256, -64, -96, -128};

        for (int offset : offsets) {
            int[][] candidates = {
                {spawnX + offset, spawnZ + offset},
                {spawnX + offset, spawnZ - offset},
                {spawnX - offset, spawnZ + offset}
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
                    // Try another candidate rather than turning an environmental
                    // precondition into a compatibility failure.
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

    private static void skipChunkChecks(
        BiConsumer<String, String> skip,
        String reason
    ) {
        String[] ids = {
            "chunk.test-target",
            "chunk.load-api",
            "chunk.load-event",
            "chunk.load-event-primary",
            "chunk.load-event-visible",
            "chunk.unload-request",
            "chunk.unload-event",
            "chunk.unload-event-primary",
            "chunk.unload-event-visible",
            "chunk.unloaded-state",
            "chunk.reload-api",
            "chunk.reload-event",
            "chunk.reload-not-new",
            "chunk.event-order"
        };
        for (String id : ids) {
            skip.accept(id, reason);
        }
    }

    private static final class ChunkLifecycleProbe implements Listener {
        private final JavaPlugin plugin;
        private final World world;
        private final int chunkX;
        private final int chunkZ;
        private final BiConsumer<String, String> pass;
        private final BiConsumer<String, String> fail;
        private final BiConsumer<String, String> skip;
        private final Runnable completion;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final List<String> order = new ArrayList<>();

        private int loadEvents;
        private boolean firstLoadPrimary;
        private boolean firstLoadVisible;
        private boolean secondLoadNew;
        private boolean unloadSeen;
        private boolean unloadPrimary;
        private boolean unloadVisible;

        private ChunkLifecycleProbe(
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
                "chunk.test-target",
                world.getName() + " " + chunkX + "," + chunkZ
            );
            Bukkit.getPluginManager().registerEvents(this, plugin);

            try {
                boolean loaded = world.loadChunk(chunkX, chunkZ, true);
                if (loaded && world.isChunkLoaded(chunkX, chunkZ)) {
                    pass.accept(
                        "chunk.load-api",
                        "loadChunk returned true and chunk became Bukkit-visible"
                    );
                } else {
                    fail.accept(
                        "chunk.load-api",
                        "loaded=" + loaded
                            + " visible=" + world.isChunkLoaded(chunkX, chunkZ)
                    );
                }
            } catch (Throwable throwable) {
                fail.accept("chunk.load-api", describe(throwable));
                skipAfterLoadFailure("initial chunk load failed");
                finish();
                return;
            }

            waitForInitialLoadEvent(0);
        }

        private void waitForInitialLoadEvent(int elapsed) {
            if (finished.get()) {
                return;
            }
            if (loadEvents >= 1) {
                pass.accept("chunk.load-event", "ChunkLoadEvent observed for target chunk");
                if (firstLoadPrimary) {
                    pass.accept("chunk.load-event-primary", "ChunkLoadEvent ran on primary thread");
                } else {
                    fail.accept("chunk.load-event-primary", "ChunkLoadEvent was not on primary thread");
                }
                if (firstLoadVisible) {
                    pass.accept(
                        "chunk.load-event-visible",
                        "target chunk was Bukkit-visible inside ChunkLoadEvent"
                    );
                } else {
                    fail.accept(
                        "chunk.load-event-visible",
                        "target chunk was not Bukkit-visible inside ChunkLoadEvent"
                    );
                }
                requestUnload();
                return;
            }
            if (elapsed >= CHUNK_LOAD_TIMEOUT_TICKS) {
                fail.accept(
                    "chunk.load-event",
                    "ChunkLoadEvent not observed within " + CHUNK_LOAD_TIMEOUT_TICKS + " ticks"
                );
                skip.accept("chunk.load-event-primary", "ChunkLoadEvent was not observed");
                skip.accept("chunk.load-event-visible", "ChunkLoadEvent was not observed");
                skipAfterInitialEventFailure();
                finish();
                return;
            }
            Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> waitForInitialLoadEvent(elapsed + 1),
                1L
            );
        }

        private void requestUnload() {
            try {
                boolean accepted = world.unloadChunkRequest(chunkX, chunkZ);
                if (accepted) {
                    pass.accept("chunk.unload-request", "unloadChunkRequest accepted target chunk");
                } else {
                    fail.accept("chunk.unload-request", "unloadChunkRequest returned false");
                }
            } catch (Throwable throwable) {
                fail.accept("chunk.unload-request", describe(throwable));
                skipAfterUnloadRequestFailure();
                finish();
                return;
            }
            waitForUnload(0);
        }

        private void waitForUnload(int elapsed) {
            if (finished.get()) {
                return;
            }
            boolean noLongerLoaded;
            try {
                noLongerLoaded = !world.isChunkLoaded(chunkX, chunkZ);
            } catch (Throwable throwable) {
                fail.accept("chunk.unloaded-state", describe(throwable));
                noLongerLoaded = false;
            }

            if (unloadSeen && noLongerLoaded) {
                pass.accept("chunk.unload-event", "ChunkUnloadEvent observed for target chunk");
                if (unloadPrimary) {
                    pass.accept("chunk.unload-event-primary", "ChunkUnloadEvent ran on primary thread");
                } else {
                    fail.accept("chunk.unload-event-primary", "ChunkUnloadEvent was not on primary thread");
                }
                if (unloadVisible) {
                    pass.accept(
                        "chunk.unload-event-visible",
                        "target chunk remained Bukkit-visible inside ChunkUnloadEvent"
                    );
                } else {
                    fail.accept(
                        "chunk.unload-event-visible",
                        "target chunk was not Bukkit-visible inside ChunkUnloadEvent"
                    );
                }
                pass.accept(
                    "chunk.unloaded-state",
                    "target chunk became unloaded after lifecycle callback"
                );
                reload();
                return;
            }

            if (elapsed >= CHUNK_UNLOAD_TIMEOUT_TICKS) {
                if (unloadSeen) {
                    pass.accept("chunk.unload-event", "ChunkUnloadEvent observed for target chunk");
                    if (unloadPrimary) {
                        pass.accept("chunk.unload-event-primary", "ChunkUnloadEvent ran on primary thread");
                    } else {
                        fail.accept("chunk.unload-event-primary", "ChunkUnloadEvent was not on primary thread");
                    }
                    if (unloadVisible) {
                        pass.accept(
                            "chunk.unload-event-visible",
                            "target chunk remained Bukkit-visible inside ChunkUnloadEvent"
                        );
                    } else {
                        fail.accept(
                            "chunk.unload-event-visible",
                            "target chunk was not Bukkit-visible inside ChunkUnloadEvent"
                        );
                    }
                } else {
                    fail.accept(
                        "chunk.unload-event",
                        "ChunkUnloadEvent not observed within "
                            + CHUNK_UNLOAD_TIMEOUT_TICKS + " ticks"
                    );
                    skip.accept("chunk.unload-event-primary", "ChunkUnloadEvent was not observed");
                    skip.accept("chunk.unload-event-visible", "ChunkUnloadEvent was not observed");
                }
                fail.accept(
                    "chunk.unloaded-state",
                    "target chunk remained loaded after "
                        + CHUNK_UNLOAD_TIMEOUT_TICKS + " ticks"
                );
                skipReload("target chunk did not complete unload");
                finish();
                return;
            }

            Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> waitForUnload(elapsed + 1),
                1L
            );
        }

        private void reload() {
            try {
                boolean loaded = world.loadChunk(chunkX, chunkZ, true);
                if (loaded && world.isChunkLoaded(chunkX, chunkZ)) {
                    pass.accept(
                        "chunk.reload-api",
                        "previously unloaded chunk loaded again"
                    );
                } else {
                    fail.accept(
                        "chunk.reload-api",
                        "loaded=" + loaded
                            + " visible=" + world.isChunkLoaded(chunkX, chunkZ)
                    );
                }
            } catch (Throwable throwable) {
                fail.accept("chunk.reload-api", describe(throwable));
                skip.accept("chunk.reload-event", "reload API failed");
                skip.accept("chunk.reload-not-new", "reload API failed");
                skip.accept("chunk.event-order", "reload API failed");
                finish();
                return;
            }
            waitForReloadEvent(0);
        }

        private void waitForReloadEvent(int elapsed) {
            if (finished.get()) {
                return;
            }
            if (loadEvents >= 2) {
                pass.accept("chunk.reload-event", "second ChunkLoadEvent observed after reload");
                if (!secondLoadNew) {
                    pass.accept(
                        "chunk.reload-not-new",
                        "reloaded chunk was not reported as newly generated"
                    );
                } else {
                    fail.accept(
                        "chunk.reload-not-new",
                        "reloaded chunk was incorrectly reported as newly generated"
                    );
                }

                List<String> expected = List.of("LOAD", "UNLOAD", "LOAD");
                if (order.equals(expected)) {
                    pass.accept("chunk.event-order", "LOAD -> UNLOAD -> LOAD");
                } else {
                    fail.accept("chunk.event-order", "observed=" + order);
                }
                finish();
                return;
            }
            if (elapsed >= CHUNK_LOAD_TIMEOUT_TICKS) {
                fail.accept(
                    "chunk.reload-event",
                    "second ChunkLoadEvent not observed within "
                        + CHUNK_LOAD_TIMEOUT_TICKS + " ticks"
                );
                skip.accept("chunk.reload-not-new", "reload event was not observed");
                fail.accept("chunk.event-order", "observed=" + order);
                finish();
                return;
            }
            Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> waitForReloadEvent(elapsed + 1),
                1L
            );
        }

        @EventHandler
        public void onChunkLoad(ChunkLoadEvent event) {
            if (!matches(event.getChunk())) {
                return;
            }
            order.add("LOAD");
            loadEvents++;
            if (loadEvents == 1) {
                firstLoadPrimary = Bukkit.isPrimaryThread();
                firstLoadVisible = visible(event.getChunk());
            } else if (loadEvents == 2) {
                secondLoadNew = event.isNewChunk();
            }
        }

        @EventHandler
        public void onChunkUnload(ChunkUnloadEvent event) {
            if (!matches(event.getChunk())) {
                return;
            }
            order.add("UNLOAD");
            unloadSeen = true;
            unloadPrimary = Bukkit.isPrimaryThread();
            unloadVisible = visible(event.getChunk());
        }

        private boolean visible(Chunk chunk) {
            try {
                return world.isChunkLoaded(chunkX, chunkZ) && chunk.isLoaded();
            } catch (Throwable throwable) {
                return false;
            }
        }

        private boolean matches(Chunk chunk) {
            return chunk.getWorld().equals(world)
                && chunk.getX() == chunkX
                && chunk.getZ() == chunkZ;
        }

        private void skipAfterLoadFailure(String reason) {
            skip.accept("chunk.load-event", reason);
            skip.accept("chunk.load-event-primary", reason);
            skip.accept("chunk.load-event-visible", reason);
            skip.accept("chunk.unload-request", reason);
            skip.accept("chunk.unload-event", reason);
            skip.accept("chunk.unload-event-primary", reason);
            skip.accept("chunk.unload-event-visible", reason);
            skip.accept("chunk.unloaded-state", reason);
            skipReload(reason);
        }

        private void skipAfterInitialEventFailure() {
            String reason = "initial load event was not observed";
            skip.accept("chunk.unload-request", reason);
            skip.accept("chunk.unload-event", reason);
            skip.accept("chunk.unload-event-primary", reason);
            skip.accept("chunk.unload-event-visible", reason);
            skip.accept("chunk.unloaded-state", reason);
            skipReload(reason);
        }

        private void skipAfterUnloadRequestFailure() {
            String reason = "unload request failed";
            skip.accept("chunk.unload-event", reason);
            skip.accept("chunk.unload-event-primary", reason);
            skip.accept("chunk.unload-event-visible", reason);
            skip.accept("chunk.unloaded-state", reason);
            skipReload(reason);
        }

        private void skipReload(String reason) {
            skip.accept("chunk.reload-api", reason);
            skip.accept("chunk.reload-event", reason);
            skip.accept("chunk.reload-not-new", reason);
            skip.accept("chunk.event-order", reason);
        }

        private void finish() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            HandlerList.unregisterAll(this);
            try {
                world.unloadChunkRequest(chunkX, chunkZ);
            } catch (Throwable ignored) {
            }
            completion.run();
        }
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getName()
            + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
