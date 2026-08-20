package org.cardboardpowered.torture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class CardboardTorturePlugin extends JavaPlugin implements Listener {
    private static final long CHUNK_PROBE_PERIOD_TICKS = 20L;
    private static final long CHUNK_UNLOAD_DELAY_TICKS = 5L;
    private static final long CHUNK_VERIFY_INTERVAL_TICKS = 5L;
    private static final long CHUNK_VERIFY_TIMEOUT_MILLIS = 40_000L;
    private static final long ENTITY_PREPARATION_SETTLE_TICKS = 100L;

    private final Map<UUID, Entity> spawned = new ConcurrentHashMap<>();
    private final Map<ChunkKey, ChunkProbe> chunkProbes = new ConcurrentHashMap<>();
    private final Map<ChunkKey, ChunkTarget> saturationTargets = new ConcurrentHashMap<>();
    private final Set<BukkitTask> workloadTasks = ConcurrentHashMap.newKeySet();
    private final Set<BukkitTask> auxiliaryTasks = ConcurrentHashMap.newKeySet();
    private final List<EntityAnchor> entityAnchors = new ArrayList<>();
    private final Map<ChunkKey, ChunkTarget> entityTicketTargets = new ConcurrentHashMap<>();
    private final List<ChunkTarget> normalChunkTargets = new ArrayList<>();
    private final ThreadLocal<ExpectedSpawn> expectedSpawn = new ThreadLocal<>();
    private final AtomicLong runSequence = new AtomicLong();
    private final AtomicLong activeRunId = new AtomicLong();

    private final AtomicLong entitySpawnEvents = new AtomicLong();
    private final AtomicLong creatureSpawnEvents = new AtomicLong();
    private final AtomicLong projectileLaunchEvents = new AtomicLong();
    private final AtomicLong chunkLoadEvents = new AtomicLong();
    private final AtomicLong chunkUnloadEvents = new AtomicLong();

    private final AtomicLong schedulerSyncIterations = new AtomicLong();
    private final AtomicLong schedulerAsyncIterations = new AtomicLong();

    private final AtomicLong apiIterations = new AtomicLong();
    private final AtomicLong apiInventoriesBuilt = new AtomicLong();
    private final AtomicLong apiItemsBuilt = new AtomicLong();
    private final AtomicLong apiKeysCreated = new AtomicLong();
    private final AtomicLong apiFailures = new AtomicLong();

    private final AtomicLong entityIterations = new AtomicLong();
    private final AtomicLong entitySpawnAttempts = new AtomicLong();
    private final AtomicLong entitiesSpawned = new AtomicLong();
    private final AtomicLong entitiesRemoved = new AtomicLong();
    private final AtomicLong targetEntitySpawnEvents = new AtomicLong();
    private final AtomicLong targetCreatureSpawnEvents = new AtomicLong();
    private final AtomicLong targetProjectileLaunchEvents = new AtomicLong();
    private final AtomicLong entityFailures = new AtomicLong();
    private final AtomicLong entitiesRetired = new AtomicLong();
    private final AtomicLong entitiesAlreadyInvalid = new AtomicLong();

    private final AtomicLong chunkIterations = new AtomicLong();
    private final AtomicLong chunkLoadAttempts = new AtomicLong();
    private final AtomicLong chunkLoadAccepted = new AtomicLong();
    private final AtomicLong targetChunkLoadEvents = new AtomicLong();
    private final AtomicLong chunkUnloadRequests = new AtomicLong();
    private final AtomicLong chunkUnloadAccepted = new AtomicLong();
    private final AtomicLong chunkUnloadRejected = new AtomicLong();
    private final AtomicLong targetChunkUnloadEvents = new AtomicLong();
    private final AtomicLong chunkUnloadAccessChecks = new AtomicLong();
    private final AtomicLong chunkUnloadAccessFailures = new AtomicLong();
    private final AtomicLong chunkUnloadVerified = new AtomicLong();
    private final AtomicLong chunkUnloadTimeouts = new AtomicLong();
    private final AtomicLong chunkUnloadWaitPolls = new AtomicLong();
    private final AtomicLong chunkUnloadEventMaxLatencyMillis = new AtomicLong();
    private final AtomicLong chunkPreconditionUnloadRequests = new AtomicLong();
    private final AtomicLong unexpectedGeneratedChunks = new AtomicLong();
    private final AtomicLong chunkBusySkips = new AtomicLong();
    private final AtomicLong chunkAborted = new AtomicLong();
    private final AtomicLong chunkCleanupUnloadRequests = new AtomicLong();
    private final AtomicLong chunkFailures = new AtomicLong();

    private final AtomicLong saturationIterations = new AtomicLong();
    private final AtomicLong saturationLoadAttempts = new AtomicLong();
    private final AtomicLong saturationGeneratedChunks = new AtomicLong();
    private final AtomicLong saturationUnloadRequests = new AtomicLong();
    private final AtomicLong saturationUnloadAccepted = new AtomicLong();
    private final AtomicLong saturationAborted = new AtomicLong();
    private final AtomicLong saturationCleanupUnloadRequests = new AtomicLong();
    private final AtomicLong saturationFailures = new AtomicLong();
    private final AtomicLong workloadFailures = new AtomicLong();
    private final AtomicBoolean failureStopScheduled = new AtomicBoolean();

    private BukkitTask preparationTask;
    private BukkitTask entityPreparationTask;
    private List<ChunkTarget> preparationTargets = List.of();
    private int preparationIndex;
    private int preparationTotalTargets;
    private long preparationAlreadyGenerated;
    private long preparationGenerated;
    private long preparationFailures;
    private boolean entityPrepared;
    private long entityPreparationFailures;

    private WorkloadPlan.Profile activeProfile;
    private WorkloadPlan.Profile lastProfile = WorkloadPlan.Profile.STABILITY;
    private long startedAtMillis;
    private long deadlineMillis;
    private long chunkDrainWindowMillis = WorkloadPlan.chunkDrainWindowMillis(300L);
    private long normalChunkSequence;
    private long saturationSequence;
    private long entityAnchorSequence;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info(
            "CardboardTorture ready. Use /cardboardtorture start [seconds] [profile]."
        );
    }

    @Override
    public void onDisable() {
        cancelChunkPreparation();
        cancelEntityPreparation();
        stopWorkload();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("cardboardtorture")) {
            return false;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                try {
                    WorkloadPlan.StartRequest request = WorkloadPlan.parseStartArguments(
                        Arrays.copyOfRange(args, 1, args.length)
                    );
                    String failure = startWorkload(request.seconds(), request.profile());
                    if (failure == null) {
                        sender.sendMessage(
                            "Cardboard torture profile " + profileName(request.profile())
                                + " started for " + request.seconds() + " seconds."
                        );
                    } else {
                        sender.sendMessage("Cardboard torture was not started: " + failure);
                    }
                } catch (IllegalArgumentException exception) {
                    sender.sendMessage(exception.getMessage());
                    sendUsage(sender);
                }
                return true;
            }
            case "prepare" -> {
                if (args.length != 2) {
                    sender.sendMessage("Usage: /cardboardtorture prepare <chunks|entity>");
                    return true;
                }
                if (args[1].equalsIgnoreCase("chunks")) {
                    sender.sendMessage(startChunkPreparation());
                } else if (args[1].equalsIgnoreCase("entity")) {
                    sender.sendMessage(startEntityPreparation());
                } else {
                    sender.sendMessage("Usage: /cardboardtorture prepare <chunks|entity>");
                }
                return true;
            }
            case "stop" -> {
                cancelChunkPreparation();
                cancelEntityPreparation();
                stopWorkload();
                sender.sendMessage(
                    "Cardboard torture stopped; child tasks and generated entities were cleaned up."
                );
                return true;
            }
            case "status" -> {
                sendStatus(sender);
                return true;
            }
            case "profiles", "help" -> {
                sendUsage(sender);
                return true;
            }
            default -> {
                sendUsage(sender);
                return true;
            }
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("Usage: /cardboardtorture <start|prepare|stop|status|profiles>");
        sender.sendMessage(
            "Start: /cardboardtorture start [seconds] "
                + "[stability|scheduler|api|entity|chunks|saturation]"
        );
        sender.sendMessage(
            "Default is 300 seconds in stability; prepare chunks, then prepare entity."
        );
    }

    private String startWorkload(long seconds, WorkloadPlan.Profile profile) {
        if (this.preparationTask != null && !this.preparationTask.isCancelled()) {
            return "bounded chunk preparation is still running; wait for completion or use stop";
        }
        if (this.entityPreparationTask != null && !this.entityPreparationTask.isCancelled()) {
            return "entity anchor preparation is still settling; wait for completion or use stop";
        }
        if (isRunning()) {
            stopWorkload();
        }

        List<World> worlds = List.copyOf(Bukkit.getWorlds());
        if (worlds.isEmpty()) {
            return "no Bukkit worlds are loaded";
        }

        if (profile.chunks()) {
            List<ChunkTarget> unprepared = findUnpreparedChunkTargets(worlds);
            if (!unprepared.isEmpty()) {
                return unprepared.size() + " of " + allChunkTargets(worlds).size()
                    + " bounded chunk targets are not pregenerated; run "
                    + "/cardboardtorture prepare chunks first";
            }
        }

        if (profile.entity()) {
            if (!this.entityPrepared || this.entityAnchors.size() != 1
                || this.entityTicketTargets.size() != 1) {
                return "entity anchor is not prepared; run /cardboardtorture prepare entity first";
            }
            EntityAnchor anchor = this.entityAnchors.get(0);
            if (!anchor.world().isChunkLoaded(anchor.chunkX(), anchor.chunkZ())) {
                cancelEntityPreparation();
                return "prepared entity anchor became unloaded; prepare entity again";
            }
        } else if (this.entityPrepared || !this.entityAnchors.isEmpty()
            || !this.entityTicketTargets.isEmpty()) {
            cancelEntityPreparation();
            if (!this.entityTicketTargets.isEmpty()) {
                return "entity ticket cleanup is incomplete; use stop to retry before starting";
            }
        }

        resetCounters();
        this.activeProfile = profile;
        this.lastProfile = profile;
        this.startedAtMillis = System.currentTimeMillis();
        this.deadlineMillis = this.startedAtMillis + seconds * 1000L;
        this.chunkDrainWindowMillis = WorkloadPlan.chunkDrainWindowMillis(seconds);
        this.normalChunkSequence = 0L;
        this.saturationSequence = 0L;
        this.entityAnchorSequence = 0L;
        this.failureStopScheduled.set(false);
        this.normalChunkTargets.clear();
        if (profile.chunks()) {
            this.normalChunkTargets.addAll(allChunkTargets(worlds));
            if (profile.entity()) {
                Set<ChunkKey> entityAnchorKeys = new HashSet<>();
                for (EntityAnchor anchor : this.entityAnchors) {
                    entityAnchorKeys.add(new ChunkKey(
                        anchor.world().getUID(),
                        anchor.chunkX(),
                        anchor.chunkZ()
                    ));
                }
                this.normalChunkTargets.removeIf(
                    target -> entityAnchorKeys.contains(target.key())
                );
            }
        }

        long runId = this.runSequence.incrementAndGet();
        this.activeRunId.set(runId);

        try {
            if (profile.scheduler()) {
                trackWorkloadTask(Bukkit.getScheduler().runTaskTimer(
                    this,
                    guarded(runId, "SCHEDULER_SYNC", () -> schedulerSyncIteration(runId)),
                    1L,
                    1L
                ));
                trackWorkloadTask(Bukkit.getScheduler().runTaskTimerAsynchronously(
                    this,
                    guarded(runId, "SCHEDULER_ASYNC", () -> schedulerAsyncIteration(runId)),
                    1L,
                    1L
                ));
            }
            if (profile.api()) {
                trackWorkloadTask(Bukkit.getScheduler().runTaskTimer(
                    this,
                    guarded(runId, "API", () -> apiIteration(runId)),
                    1L,
                    1L
                ));
            }
            if (profile.entity()) {
                trackWorkloadTask(Bukkit.getScheduler().runTaskTimer(
                    this,
                    guarded(runId, "ENTITY", () -> entityIteration(runId)),
                    1L,
                    1L
                ));
            }
            if (profile.chunks()) {
                trackWorkloadTask(Bukkit.getScheduler().runTaskTimer(
                    this,
                    guarded(runId, "CHUNK", () -> normalChunkIteration(runId)),
                    1L,
                    CHUNK_PROBE_PERIOD_TICKS
                ));
            }
            if (profile.saturation()) {
                trackWorkloadTask(Bukkit.getScheduler().runTaskTimer(
                    this,
                    guarded(runId, "SATURATION", () -> saturationIteration(runId, worlds)),
                    1L,
                    CHUNK_PROBE_PERIOD_TICKS
                ));
            }
            trackWorkloadTask(Bukkit.getScheduler().runTaskTimer(
                this,
                guarded(runId, "PROGRESS", () -> progressIteration(runId)),
                100L,
                100L
            ));
            trackWorkloadTask(Bukkit.getScheduler().runTaskTimer(
                this,
                guarded(runId, "CONTROL", () -> deadlineIteration(runId)),
                20L,
                20L
            ));
        } catch (RuntimeException exception) {
            recordGeneralFailure(
                "START",
                exception.getClass().getName() + ": " + exception.getMessage()
            );
            stopWorkload();
            return "scheduler setup failed; see server log";
        }

        if (profile.saturation()) {
            getLogger().warning(
                "TORTURE_SATURATION_STARTED: continuous chunk generation is enabled; "
                    + "TPS degradation is expected and this is not a normal stability profile"
            );
        }
        getLogger().info(
            "TORTURE_STARTED run=" + runId
                + " profile=" + profileName(profile)
                + " seconds=" + seconds
                + " worlds=" + worlds.size()
                + " entityAnchors=" + this.entityAnchors.size()
                + " chunkPool=" + this.normalChunkTargets.size()
        );
        return null;
    }

    private void schedulerSyncIteration(long runId) {
        if (isRunActive(runId)) {
            this.schedulerSyncIterations.incrementAndGet();
        }
    }

    private void schedulerAsyncIteration(long runId) {
        if (!isRunActive(runId)) {
            return;
        }
        long iteration = this.schedulerAsyncIterations.incrementAndGet();
        long value = iteration ^ 0x9E3779B97F4A7C15L;
        for (int i = 0; i < 2048; i++) {
            value ^= value << 13;
            value ^= value >>> 7;
            value ^= value << 17;
        }
        if (value == Long.MIN_VALUE) {
            getLogger().fine("Impossible-ish async sentinel: " + value);
        }
    }

    private void apiIteration(long runId) {
        if (!isRunActive(runId)) {
            return;
        }

        long iteration = this.apiIterations.incrementAndGet();
        NamespacedKey iterationKey = new NamespacedKey(
            this,
            "torture_iteration_" + (iteration % 128L)
        );
        this.apiKeysCreated.incrementAndGet();
        Inventory inventory = Bukkit.createInventory(null, 54);
        this.apiInventoriesBuilt.incrementAndGet();

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = new ItemStack((slot & 1) == 0 ? Material.STONE : Material.PAPER, 1);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                recordApiFailure("null ItemMeta at iteration=" + iteration + " slot=" + slot);
                return;
            }

            meta.setDisplayName("Cardboard torture #" + iteration + "/" + slot);
            meta.getPersistentDataContainer().set(
                iterationKey,
                PersistentDataType.LONG,
                iteration
            );
            item.setItemMeta(meta);

            ItemMeta verifiedMeta = item.getItemMeta();
            Long verifiedValue = verifiedMeta == null
                ? null
                : verifiedMeta.getPersistentDataContainer().get(
                    iterationKey,
                    PersistentDataType.LONG
                );
            if (verifiedValue == null || verifiedValue.longValue() != iteration) {
                recordApiFailure(
                    "PDC mismatch at iteration=" + iteration + " slot=" + slot
                );
                return;
            }

            inventory.setItem(slot, item);
            this.apiItemsBuilt.incrementAndGet();
        }

        if (inventory.getItem(inventory.getSize() - 1) == null) {
            recordApiFailure("inventory mutation was not retained at iteration=" + iteration);
        }
    }

    private String startEntityPreparation() {
        if (isRunning()) {
            return "Stop the active workload before preparing the entity anchor.";
        }
        if (this.preparationTask != null && !this.preparationTask.isCancelled()) {
            return "Wait for bounded chunk preparation to finish first.";
        }
        if (this.entityPreparationTask != null && !this.entityPreparationTask.isCancelled()) {
            return "Entity anchor preparation is already settling.";
        }
        if (this.entityPrepared && this.entityAnchors.size() == 1
            && this.entityTicketTargets.size() == 1) {
            return "Entity anchor is already loaded, settled, and pinned.";
        }

        cancelEntityPreparation();
        if (!this.entityTicketTargets.isEmpty()) {
            return "A previous entity ticket could not be released; use stop to retry cleanup.";
        }
        this.entityPreparationFailures = 0L;
        List<World> worlds = List.copyOf(Bukkit.getWorlds());
        if (worlds.isEmpty()) {
            return "No Bukkit worlds are loaded.";
        }

        ChunkTarget target = entityAnchorTarget(worlds);
        if (!target.world().isChunkGenerated(target.chunkX(), target.chunkZ())) {
            return "Entity anchor is not pregenerated; run prepare chunks first ("
                + target.describe() + ").";
        }
        this.entityTicketTargets.put(target.key(), target);
        try {
            if (!target.world().addPluginChunkTicket(target.chunkX(), target.chunkZ(), this)) {
                this.entityPreparationFailures++;
                boolean ticketPresent = target.world().getPluginChunkTickets(
                    target.chunkX(),
                    target.chunkZ()
                ).contains(this);
                if (!ticketPresent) {
                    this.entityTicketTargets.remove(target.key(), target);
                } else {
                    releaseEntityAnchors();
                }
                return "Could not add entity anchor ticket (" + target.describe() + ").";
            }
            if (!target.world().isChunkLoaded(target.chunkX(), target.chunkZ())) {
                throw new IllegalStateException(
                    "entity anchor ticket did not load its pregenerated chunk"
                );
            }

            int blockX = (target.chunkX() << 4) + 8;
            int blockZ = (target.chunkZ() << 4) + 8;
            int surfaceY = target.world().getHighestBlockYAt(blockX, blockZ) + 1;
            int y = Math.max(
                target.world().getMinHeight() + 2,
                Math.min(target.world().getMaxHeight() - 3, surfaceY)
            );
            this.entityAnchors.add(new EntityAnchor(
                target.world(),
                target.chunkX(),
                target.chunkZ(),
                new Location(target.world(), blockX + 0.5, y, blockZ + 0.5)
            ));

            this.entityPreparationTask = Bukkit.getScheduler().runTaskLater(
                this,
                this::finishEntityPreparation,
                ENTITY_PREPARATION_SETTLE_TICKS
            );
        } catch (RuntimeException exception) {
            this.entityPreparationFailures++;
            releaseEntityAnchors();
            getLogger().severe(
                "ERROR TORTURE_ENTITY_PREPARE_FAILURE could not prepare anchor: "
                    + exception.getClass().getName() + ": " + exception.getMessage()
            );
            return "Could not prepare entity anchor; see server log.";
        }

        getLogger().warning(
            "TORTURE_ENTITY_PREPARE_STARTED " + target.describe()
                + " settleTicks=" + ENTITY_PREPARATION_SETTLE_TICKS
                + "; entity storage loading is outside the measured workload"
        );
        return "Entity anchor is loaded and pinned; waiting "
            + ENTITY_PREPARATION_SETTLE_TICKS + " server ticks for storage to settle.";
    }

    private void finishEntityPreparation() {
        this.entityPreparationTask = null;
        try {
            if (this.entityAnchors.size() != 1) {
                throw new IllegalStateException("expected exactly one entity anchor");
            }
            EntityAnchor anchor = this.entityAnchors.get(0);
            ChunkKey anchorKey = new ChunkKey(
                anchor.world().getUID(),
                anchor.chunkX(),
                anchor.chunkZ()
            );
            if (this.entityTicketTargets.size() != 1
                || !this.entityTicketTargets.containsKey(anchorKey)
                || !anchor.world().getPluginChunkTickets(
                    anchor.chunkX(),
                    anchor.chunkZ()
                ).contains(this)) {
                throw new IllegalStateException("entity anchor plugin ticket is not present");
            }
            if (!anchor.world().isChunkLoaded(anchor.chunkX(), anchor.chunkZ())) {
                throw new IllegalStateException("entity anchor became unloaded while settling");
            }
            this.entityPrepared = true;
            getLogger().info(
                "TORTURE_ENTITY_PREPARE_FINISHED world=" + anchor.world().getName()
                    + " x=" + anchor.chunkX() + " z=" + anchor.chunkZ()
                    + " failures=" + this.entityPreparationFailures
            );
        } catch (RuntimeException exception) {
            this.entityPreparationFailures++;
            getLogger().severe(
                "ERROR TORTURE_ENTITY_PREPARE_FAILURE " + exception.getClass().getName()
                    + ": " + exception.getMessage()
            );
            releaseEntityAnchors();
        }
    }

    private void cancelEntityPreparation() {
        BukkitTask task = this.entityPreparationTask;
        this.entityPreparationTask = null;
        cancel(task);
        this.entityPrepared = false;
        releaseEntityAnchors();
    }

    private void entityIteration(long runId) {
        if (!isRunActive(runId) || this.entityAnchors.isEmpty()) {
            return;
        }

        long iteration = this.entityIterations.incrementAndGet();
        EntityAnchor anchor = this.entityAnchors.get(
            (int) (this.entityAnchorSequence++ % this.entityAnchors.size())
        );
        if (!anchor.world().isChunkLoaded(anchor.chunkX(), anchor.chunkZ())) {
            recordEntityFailure(
                "pinned entity chunk became unloaded world=" + anchor.world().getName()
                    + " x=" + anchor.chunkX() + " z=" + anchor.chunkZ()
            );
            return;
        }

        Location base = anchor.center().clone().add(
            (iteration % 5L) - 2L,
            0.0,
            ((iteration / 5L) % 5L) - 2L
        );
        if (iteration % 5L == 0L) {
            spawnTemporary(runId, anchor, base, EntityType.ARMOR_STAND, 40L);
        }
        if (iteration % 10L == 0L) {
            spawnTemporary(
                runId,
                anchor,
                base.clone().add(1.0, 0.0, 0.0),
                EntityType.ZOMBIE,
                40L
            );
        }
        if (iteration % 4L == 0L) {
            spawnTemporary(
                runId,
                anchor,
                base.clone().add(0.0, 1.0, 1.0),
                EntityType.SNOWBALL,
                20L
            );
        }
    }

    private void spawnTemporary(
        long runId,
        EntityAnchor anchor,
        Location location,
        EntityType type,
        long lifetimeTicks
    ) {
        if ((location.getBlockX() >> 4) != anchor.chunkX()
            || (location.getBlockZ() >> 4) != anchor.chunkZ()) {
            recordEntityFailure(
                "entity target escaped pinned chunk type=" + type
                    + " world=" + anchor.world().getName()
            );
            return;
        }
        if (!anchor.world().isChunkLoaded(anchor.chunkX(), anchor.chunkZ())) {
            recordEntityFailure(
                "entity spawn attempted in unloaded chunk type=" + type
                    + " world=" + anchor.world().getName()
            );
            return;
        }

        this.entitySpawnAttempts.incrementAndGet();
        ExpectedSpawn expectation = new ExpectedSpawn();
        this.expectedSpawn.set(expectation);
        try {
            Entity entity = anchor.world().spawnEntity(location, type);
            UUID entityId = entity.getUniqueId();

            if (!expectation.entityEventObserved(entityId)) {
                recordEntityFailure("missing EntitySpawnEvent for type=" + type);
            } else {
                this.targetEntitySpawnEvents.incrementAndGet();
            }
            if (type == EntityType.ZOMBIE || type == EntityType.ARMOR_STAND) {
                if (!expectation.creatureEventObserved(entityId)) {
                    recordEntityFailure("missing CreatureSpawnEvent for type=" + type);
                } else {
                    this.targetCreatureSpawnEvents.incrementAndGet();
                }
            }
            if (type == EntityType.SNOWBALL) {
                if (!expectation.projectileEventObserved(entityId)) {
                    recordEntityFailure("missing ProjectileLaunchEvent for type=" + type);
                } else {
                    this.targetProjectileLaunchEvents.incrementAndGet();
                }
            }

            if (!entity.isValid()) {
                recordEntityFailure("spawn returned an invalid/cancelled entity type=" + type);
                return;
            }

            this.spawned.put(entityId, entity);
            this.entitiesSpawned.incrementAndGet();

            scheduleAuxiliary(
                runId,
                () -> removeTracked(entityId),
                lifetimeTicks
            );
        } catch (RuntimeException exception) {
            recordEntityFailure(
                "spawn type=" + type + " " + exception.getClass().getName()
                    + ": " + exception.getMessage()
            );
        } finally {
            this.expectedSpawn.remove();
        }
    }

    private void removeTracked(UUID uuid) {
        Entity entity = this.spawned.remove(uuid);
        if (entity == null) {
            return;
        }
        if (entity.isValid()) {
            entity.remove();
            this.entitiesRemoved.incrementAndGet();
        } else {
            this.entitiesAlreadyInvalid.incrementAndGet();
        }
        this.entitiesRetired.incrementAndGet();
    }

    private void normalChunkIteration(long runId) {
        if (!isRunActive(runId) || this.normalChunkTargets.isEmpty()) {
            return;
        }
        if (this.deadlineMillis - System.currentTimeMillis() < this.chunkDrainWindowMillis) {
            return;
        }

        this.chunkIterations.incrementAndGet();
        ChunkTarget target = this.normalChunkTargets.get(
            (int) (this.normalChunkSequence++ % this.normalChunkTargets.size())
        );
        ChunkKey key = target.key();
        if (!target.world().isChunkGenerated(target.chunkX(), target.chunkZ())) {
            this.unexpectedGeneratedChunks.incrementAndGet();
            recordChunkFailure(null, "normal target is not pregenerated " + target.describe());
            return;
        }
        if (target.world().isChunkLoaded(target.chunkX(), target.chunkZ())) {
            this.chunkPreconditionUnloadRequests.incrementAndGet();
            if (!target.world().unloadChunkRequest(target.chunkX(), target.chunkZ())) {
                recordChunkFailure(
                    null,
                    "normal target was already loaded and cleanup unload was rejected "
                        + target.describe()
                );
            }
            return;
        }

        ChunkProbe probe = new ChunkProbe(runId, target);
        if (this.chunkProbes.putIfAbsent(key, probe) != null) {
            this.chunkBusySkips.incrementAndGet();
            return;
        }

        this.chunkLoadAttempts.incrementAndGet();
        probe.markLoadRequested();
        boolean accepted;
        try {
            accepted = target.world().loadChunk(target.chunkX(), target.chunkZ(), false);
        } catch (RuntimeException exception) {
            this.chunkProbes.remove(key, probe);
            recordChunkFailure(
                probe,
                "load threw for " + target.describe() + " "
                    + exception.getClass().getName() + ": " + exception.getMessage()
            );
            return;
        }

        if (!accepted) {
            this.chunkProbes.remove(key, probe);
            recordChunkFailure(
                probe,
                "load(generate=false) was not accepted for " + target.describe()
            );
            return;
        }
        if (!target.world().isChunkLoaded(target.chunkX(), target.chunkZ())) {
            this.chunkCleanupUnloadRequests.incrementAndGet();
            cleanupNormalTarget(target);
            this.chunkProbes.remove(key, probe);
            recordChunkFailure(
                probe,
                "accepted load was not visible as loaded for " + target.describe()
            );
            return;
        }
        this.chunkLoadAccepted.incrementAndGet();
        scheduleAuxiliary(
            runId,
            () -> requestNormalChunkUnload(probe),
            CHUNK_UNLOAD_DELAY_TICKS
        );
    }

    private void requestNormalChunkUnload(ChunkProbe probe) {
        if (!isCurrentProbe(probe)) {
            return;
        }
        if (!probe.loadEventObserved()) {
            recordChunkFailure(
                probe,
                "target ChunkLoadEvent was not observed for " + probe.target().describe()
            );
        }

        this.chunkUnloadRequests.incrementAndGet();
        probe.markUnloadRequested();
        boolean accepted;
        try {
            accepted = probe.target().world().unloadChunkRequest(
                probe.target().chunkX(),
                probe.target().chunkZ()
            );
        } catch (RuntimeException exception) {
            accepted = false;
            recordChunkFailure(
                probe,
                "unload request threw for " + probe.target().describe() + " "
                    + exception.getClass().getName() + ": " + exception.getMessage()
            );
        }

        if (!accepted) {
            this.chunkUnloadRejected.incrementAndGet();
            recordChunkFailure(
                probe,
                "unload request was rejected for " + probe.target().describe()
            );
            return;
        }

        this.chunkUnloadAccepted.incrementAndGet();
        scheduleAuxiliary(
            probe.runId(),
            () -> verifyNormalChunkUnload(probe),
            CHUNK_VERIFY_INTERVAL_TICKS
        );
    }

    private void verifyNormalChunkUnload(ChunkProbe probe) {
        if (!isCurrentProbe(probe)) {
            return;
        }

        ChunkTarget target = probe.target();
        boolean chunkLoaded = target.world().isChunkLoaded(target.chunkX(), target.chunkZ());
        boolean unloadEventObserved = probe.unloadEventObserved();
        WorkloadPlan.UnloadVerificationDecision decision = WorkloadPlan.evaluateChunkUnload(
            chunkLoaded,
            unloadEventObserved,
            probe.unloadElapsedMillis(),
            CHUNK_VERIFY_TIMEOUT_MILLIS
        );
        switch (decision) {
            case VERIFIED -> {
                if (!probe.failed() && probe.loadEventObserved()) {
                    this.chunkUnloadVerified.incrementAndGet();
                }
                this.chunkProbes.remove(target.key(), probe);
            }
            case LOADED_TIMEOUT -> {
                this.chunkUnloadTimeouts.incrementAndGet();
                recordChunkFailure(
                    probe,
                    "unload timed out for " + target.describe()
                        + " after " + CHUNK_VERIFY_TIMEOUT_MILLIS + " ms"
                );
                this.chunkProbes.remove(target.key(), probe);
            }
            case EVENT_ORDER_FAILURE -> {
                recordChunkFailure(
                    probe,
                    "Paper ordering violated: chunk became unloaded before target "
                        + "ChunkUnloadEvent for " + target.describe()
                );
                this.chunkProbes.remove(target.key(), probe);
            }
            case RETRY -> {
                this.chunkUnloadWaitPolls.incrementAndGet();
                scheduleAuxiliary(
                    probe.runId(),
                    () -> verifyNormalChunkUnload(probe),
                    CHUNK_VERIFY_INTERVAL_TICKS
                );
            }
        }
    }

    private void saturationIteration(long runId, List<World> worlds) {
        if (!isRunActive(runId) || worlds.isEmpty()) {
            return;
        }
        if (this.deadlineMillis - System.currentTimeMillis() < this.chunkDrainWindowMillis) {
            return;
        }

        long sequence = this.saturationSequence++;
        this.saturationIterations.incrementAndGet();
        World world = worlds.get((int) (sequence % worlds.size()));
        long worldSequence = sequence / worlds.size();
        WorkloadPlan.ChunkOffset offset = WorkloadPlan.saturationOffset(worldSequence);
        Location spawn = world.getSpawnLocation();
        int chunkX = (spawn.getBlockX() >> 4) + offset.x();
        int chunkZ = (spawn.getBlockZ() >> 4) + offset.z();
        boolean generatedBefore = world.isChunkGenerated(chunkX, chunkZ);

        this.saturationLoadAttempts.incrementAndGet();
        boolean accepted;
        try {
            accepted = world.loadChunk(chunkX, chunkZ, true);
        } catch (RuntimeException exception) {
            recordSaturationFailure(
                "load threw world=" + world.getName() + " x=" + chunkX + " z=" + chunkZ
                    + " " + exception.getClass().getName() + ": " + exception.getMessage()
            );
            return;
        }
        if (!accepted) {
            recordSaturationFailure(
                "load was not accepted world=" + world.getName()
                    + " x=" + chunkX + " z=" + chunkZ
            );
            return;
        }
        ChunkTarget target = new ChunkTarget(world, chunkX, chunkZ);
        this.saturationTargets.put(target.key(), target);
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            this.saturationCleanupUnloadRequests.incrementAndGet();
            cleanupSaturationTarget(target);
            this.saturationTargets.remove(target.key(), target);
            recordSaturationFailure(
                "accepted load was not visible as loaded world=" + world.getName()
                    + " x=" + chunkX + " z=" + chunkZ
            );
            return;
        }
        if (!generatedBefore && world.isChunkGenerated(chunkX, chunkZ)) {
            this.saturationGeneratedChunks.incrementAndGet();
        }

        scheduleAuxiliary(runId, () -> {
            this.saturationUnloadRequests.incrementAndGet();
            try {
                if (world.unloadChunkRequest(chunkX, chunkZ)) {
                    this.saturationUnloadAccepted.incrementAndGet();
                    this.saturationTargets.remove(target.key(), target);
                    return;
                }
                recordSaturationFailure(
                    "unload request rejected world=" + world.getName()
                        + " x=" + chunkX + " z=" + chunkZ
                );
            } catch (RuntimeException exception) {
                recordSaturationFailure(
                    "unload request threw world=" + world.getName()
                        + " x=" + chunkX + " z=" + chunkZ + " "
                        + exception.getClass().getName() + ": " + exception.getMessage()
                );
            }
        }, CHUNK_UNLOAD_DELAY_TICKS);
    }

    private void progressIteration(long runId) {
        if (isRunActive(runId)) {
            getLogger().info("TORTURE_PROGRESS " + statusSummary());
        }
    }

    private void deadlineIteration(long runId) {
        if (isRunActive(runId) && System.currentTimeMillis() >= this.deadlineMillis) {
            stopWorkload(true);
        }
    }

    private String startChunkPreparation() {
        if (isRunning()) {
            return "Stop the active workload before preparing chunks.";
        }
        if (this.preparationTask != null && !this.preparationTask.isCancelled()) {
            return "Chunk pool preparation is already running.";
        }

        List<World> worlds = List.copyOf(Bukkit.getWorlds());
        if (worlds.isEmpty()) {
            return "No Bukkit worlds are loaded.";
        }

        List<ChunkTarget> allTargets = allChunkTargets(worlds);
        List<ChunkTarget> missing = new ArrayList<>();
        for (ChunkTarget target : allTargets) {
            if (!target.world().isChunkGenerated(target.chunkX(), target.chunkZ())) {
                missing.add(target);
            }
        }

        this.preparationTargets = List.copyOf(missing);
        this.preparationIndex = 0;
        this.preparationTotalTargets = allTargets.size();
        this.preparationAlreadyGenerated = allTargets.size() - missing.size();
        this.preparationGenerated = 0L;
        this.preparationFailures = 0L;
        if (missing.isEmpty()) {
            return "Bounded chunk pool is already pregenerated (" + allTargets.size() + " targets).";
        }

        try {
            this.preparationTask = Bukkit.getScheduler().runTaskTimer(
                this,
                this::prepareNextChunk,
                1L,
                1L
            );
        } catch (RuntimeException exception) {
            this.preparationFailures++;
            getLogger().severe(
                "ERROR TORTURE_CHUNK_PREPARE_FAILURE could not schedule preparation: "
                    + exception.getClass().getName() + ": " + exception.getMessage()
            );
            return "Could not schedule bounded chunk preparation; see server log.";
        }
        getLogger().warning(
            "TORTURE_CHUNK_PREPARE_STARTED missing=" + missing.size()
                + " total=" + allTargets.size()
                + "; generation is outside the measured stability workload"
        );
        return "Preparing " + missing.size() + " bounded chunk targets, one per server tick."
            + " Use /cardboardtorture status to monitor completion.";
    }

    private void prepareNextChunk() {
        try {
            prepareNextChunkChecked();
        } catch (RuntimeException exception) {
            this.preparationFailures++;
            BukkitTask task = this.preparationTask;
            this.preparationTask = null;
            try {
                cancel(task);
            } catch (RuntimeException cancelException) {
                exception.addSuppressed(cancelException);
            }
            getLogger().severe(
                "ERROR TORTURE_CHUNK_PREPARE_FAILURE unexpected preparation error: "
                    + exception.getClass().getName() + ": " + exception.getMessage()
            );
        }
    }

    private void prepareNextChunkChecked() {
        if (this.preparationIndex >= this.preparationTargets.size()) {
            finishChunkPreparation();
            return;
        }

        ChunkTarget target = this.preparationTargets.get(this.preparationIndex++);
        if (target.world().isChunkGenerated(target.chunkX(), target.chunkZ())) {
            this.preparationAlreadyGenerated++;
            return;
        }

        boolean loaded = false;
        try {
            loaded = target.world().loadChunk(target.chunkX(), target.chunkZ(), true);
            if (!loaded || !target.world().isChunkGenerated(target.chunkX(), target.chunkZ())) {
                this.preparationFailures++;
                getLogger().severe(
                    "ERROR TORTURE_CHUNK_PREPARE_FAILURE load failed " + target.describe()
                );
                return;
            }
            this.preparationGenerated++;
        } catch (RuntimeException exception) {
            this.preparationFailures++;
            getLogger().severe(
                "ERROR TORTURE_CHUNK_PREPARE_FAILURE " + target.describe() + " "
                    + exception.getClass().getName() + ": " + exception.getMessage()
            );
        } finally {
            if (loaded) {
                try {
                    if (!target.world().unloadChunkRequest(target.chunkX(), target.chunkZ())) {
                        this.preparationFailures++;
                        getLogger().severe(
                            "ERROR TORTURE_CHUNK_PREPARE_FAILURE unload rejected "
                                + target.describe()
                        );
                    }
                } catch (RuntimeException exception) {
                    this.preparationFailures++;
                    getLogger().severe(
                        "ERROR TORTURE_CHUNK_PREPARE_FAILURE unload threw "
                            + target.describe() + " " + exception.getClass().getName()
                            + ": " + exception.getMessage()
                    );
                }
            }
        }
    }

    private void finishChunkPreparation() {
        BukkitTask task = this.preparationTask;
        this.preparationTask = null;
        cancel(task);
        getLogger().info(
            "TORTURE_CHUNK_PREPARE_FINISHED processed=" + this.preparationIndex
                + " generated=" + this.preparationGenerated
                + " alreadyGenerated=" + this.preparationAlreadyGenerated
                + " failures=" + this.preparationFailures
        );
    }

    private void cancelChunkPreparation() {
        BukkitTask task = this.preparationTask;
        this.preparationTask = null;
        cancel(task);
        this.preparationTargets = List.of();
        this.preparationIndex = 0;
    }

    private List<ChunkTarget> findUnpreparedChunkTargets(List<World> worlds) {
        List<ChunkTarget> missing = new ArrayList<>();
        for (ChunkTarget target : allChunkTargets(worlds)) {
            if (!target.world().isChunkGenerated(target.chunkX(), target.chunkZ())) {
                missing.add(target);
            }
        }
        return missing;
    }

    private List<ChunkTarget> allChunkTargets(List<World> worlds) {
        List<ChunkTarget> targets = new ArrayList<>(
            worlds.size() * WorkloadPlan.boundedChunkOffsets().size()
        );
        for (WorkloadPlan.ChunkOffset offset : WorkloadPlan.boundedChunkOffsets()) {
            for (World world : worlds) {
                Location spawn = world.getSpawnLocation();
                int baseX = spawn.getBlockX() >> 4;
                int baseZ = spawn.getBlockZ() >> 4;
                targets.add(new ChunkTarget(
                    world,
                    baseX + offset.x(),
                    baseZ + offset.z()
                ));
            }
        }
        return targets;
    }

    private ChunkTarget entityAnchorTarget(List<World> worlds) {
        World world = worlds.stream()
            .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
            .findFirst()
            .orElse(worlds.get(0));
        Location spawn = world.getSpawnLocation();
        WorkloadPlan.ChunkOffset offset = WorkloadPlan.entityAnchorOffset();
        return new ChunkTarget(
            world,
            (spawn.getBlockX() >> 4) + offset.x(),
            (spawn.getBlockZ() >> 4) + offset.z()
        );
    }

    private void stopWorkload() {
        stopWorkload(false);
    }

    private void stopWorkload(boolean naturalDeadline) {
        long stoppedRunId = this.activeRunId.getAndSet(0L);
        WorkloadPlan.Profile stoppedProfile = this.activeProfile;
        this.activeProfile = null;

        cancelTasks(this.workloadTasks);
        cancelTasks(this.auxiliaryTasks);

        int pendingProbes = this.chunkProbes.size();
        if (pendingProbes > 0) {
            this.chunkAborted.addAndGet(pendingProbes);
            if (naturalDeadline) {
                recordChunkFailure(
                    null,
                    "natural deadline reached with " + pendingProbes + " incomplete probe(s)"
                );
            }
            for (ChunkProbe probe : List.copyOf(this.chunkProbes.values())) {
                this.chunkCleanupUnloadRequests.incrementAndGet();
                cleanupNormalTarget(probe.target());
            }
            this.chunkProbes.clear();
        }

        int pendingSaturation = this.saturationTargets.size();
        if (pendingSaturation > 0) {
            this.saturationAborted.addAndGet(pendingSaturation);
            for (ChunkTarget target : List.copyOf(this.saturationTargets.values())) {
                this.saturationCleanupUnloadRequests.incrementAndGet();
                cleanupSaturationTarget(target);
            }
            this.saturationTargets.clear();
        }

        for (UUID uuid : List.copyOf(this.spawned.keySet())) {
            removeTracked(uuid);
        }
        releaseEntityAnchors();
        this.normalChunkTargets.clear();

        if (naturalDeadline && stoppedProfile != null && stoppedProfile.chunks()) {
            validateNaturalChunkResults();
        }

        if (this.startedAtMillis != 0L) {
            getLogger().info(
                "TORTURE_FINISHED run=" + stoppedRunId
                    + " profile=" + profileName(
                        stoppedProfile == null ? this.lastProfile : stoppedProfile
                    )
                    + " durationMs=" + (System.currentTimeMillis() - this.startedAtMillis)
                    + " " + statusSummary()
            );
        }
        this.startedAtMillis = 0L;
        this.deadlineMillis = 0L;
    }

    private void validateNaturalChunkResults() {
        long attempts = this.chunkLoadAttempts.get();
        long loadAccepted = this.chunkLoadAccepted.get();
        long loadEvents = this.targetChunkLoadEvents.get();
        long unloadRequests = this.chunkUnloadRequests.get();
        long unloadAccepted = this.chunkUnloadAccepted.get();
        long unloadEvents = this.targetChunkUnloadEvents.get();
        long accessChecks = this.chunkUnloadAccessChecks.get();
        long verified = this.chunkUnloadVerified.get();

        List<String> mismatches = new ArrayList<>();
        if (attempts == 0L) {
            mismatches.add("no lifecycle probes completed useful work");
        }
        if (attempts != loadAccepted
            || attempts != loadEvents
            || attempts != unloadRequests
            || attempts != unloadAccepted
            || attempts != unloadEvents
            || attempts != accessChecks
            || attempts != verified) {
            mismatches.add(
                "attempts/loadAccepted/loadEvents/unloadRequests/unloadAccepted/"
                    + "unloadEvents/accessChecks/verified="
                    + attempts + "/" + loadAccepted + "/" + loadEvents + "/"
                    + unloadRequests + "/" + unloadAccepted + "/" + unloadEvents
                    + "/" + accessChecks + "/" + verified
            );
        }
        if (this.chunkUnloadAccessFailures.get() != 0L) {
            mismatches.add("accessFailures=" + this.chunkUnloadAccessFailures.get());
        }
        if (!mismatches.isEmpty()) {
            recordChunkFailure(
                null,
                "natural completion invariants failed: " + String.join("; ", mismatches)
            );
        }
    }

    private void releaseEntityAnchors() {
        Map<ChunkKey, ChunkTarget> cleanupTargets = new HashMap<>(
            this.entityTicketTargets
        );
        for (EntityAnchor anchor : this.entityAnchors) {
            ChunkTarget target = new ChunkTarget(
                anchor.world(),
                anchor.chunkX(),
                anchor.chunkZ()
            );
            cleanupTargets.putIfAbsent(target.key(), target);
        }

        Set<World> worlds = new HashSet<>();
        for (ChunkTarget target : cleanupTargets.values()) {
            worlds.add(target.world());
        }
        for (World world : worlds) {
            try {
                world.removePluginChunkTickets(this);
            } catch (RuntimeException exception) {
                recordEntityFailure(
                    "entity plugin ticket cleanup threw world=" + world.getName() + " "
                        + exception.getClass().getName() + ": " + exception.getMessage()
                );
                continue;
            }

            for (ChunkTarget target : cleanupTargets.values()) {
                if (target.world() != world) {
                    continue;
                }
                try {
                    if (world.getPluginChunkTickets(
                        target.chunkX(),
                        target.chunkZ()
                    ).contains(this)) {
                        recordEntityFailure(
                            "entity plugin ticket remained after cleanup " + target.describe()
                        );
                        continue;
                    }
                    this.entityTicketTargets.remove(target.key());
                    this.entityAnchors.removeIf(anchor ->
                        anchor.world() == world
                            && anchor.chunkX() == target.chunkX()
                            && anchor.chunkZ() == target.chunkZ()
                    );
                } catch (RuntimeException exception) {
                    recordEntityFailure(
                        "entity plugin ticket verification threw " + target.describe() + " "
                            + exception.getClass().getName() + ": " + exception.getMessage()
                    );
                }
            }
        }
        this.entityPrepared = false;
    }

    private void cleanupNormalTarget(ChunkTarget target) {
        try {
            if (!target.world().unloadChunkRequest(target.chunkX(), target.chunkZ())) {
                recordChunkFailure(null, "cleanup unload rejected " + target.describe());
            }
        } catch (RuntimeException exception) {
            recordChunkFailure(
                null,
                "cleanup unload threw " + target.describe() + " "
                    + exception.getClass().getName() + ": " + exception.getMessage()
            );
        }
    }

    private void cleanupSaturationTarget(ChunkTarget target) {
        try {
            if (!target.world().unloadChunkRequest(target.chunkX(), target.chunkZ())) {
                recordSaturationFailure("cleanup unload rejected " + target.describe());
            }
        } catch (RuntimeException exception) {
            recordSaturationFailure(
                "cleanup unload threw " + target.describe() + " "
                    + exception.getClass().getName() + ": " + exception.getMessage()
            );
        }
    }

    private void trackWorkloadTask(BukkitTask task) {
        this.workloadTasks.add(task);
        if (!isRunning()) {
            task.cancel();
            this.workloadTasks.remove(task);
        }
    }

    private void scheduleAuxiliary(long runId, Runnable action, long delayTicks) {
        if (!isRunActive(runId)) {
            return;
        }

        AtomicReference<BukkitTask> reference = new AtomicReference<>();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            BukkitTask current = reference.get();
            if (current != null) {
                this.auxiliaryTasks.remove(current);
            }
            if (isRunActive(runId)) {
                runGuarded(runId, "AUXILIARY", action);
            }
        }, delayTicks);
        reference.set(task);
        this.auxiliaryTasks.add(task);
        if (!isRunActive(runId)) {
            task.cancel();
            this.auxiliaryTasks.remove(task);
        }
    }

    private void cancelTasks(Set<BukkitTask> tasks) {
        for (BukkitTask task : List.copyOf(tasks)) {
            cancel(task);
        }
        tasks.clear();
    }

    private Runnable guarded(long runId, String workload, Runnable action) {
        return () -> runGuarded(runId, workload, action);
    }

    private void runGuarded(long runId, String workload, Runnable action) {
        if (!isRunActive(runId)) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException exception) {
            recordGeneralFailure(
                "UNCAUGHT_" + workload,
                exception.getClass().getName() + ": " + exception.getMessage()
            );
            scheduleFailureStop(runId);
        }
    }

    private void scheduleFailureStop(long runId) {
        if (!this.failureStopScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(this, () -> {
                if (isRunActive(runId)) {
                    stopWorkload();
                }
            });
        } catch (RuntimeException exception) {
            getLogger().severe(
                "ERROR TORTURE_CONTROL_FAILURE could not schedule failure cleanup: "
                    + exception.getClass().getName() + ": " + exception.getMessage()
            );
        }
    }

    private void cancel(BukkitTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    private boolean isRunning() {
        return this.activeRunId.get() != 0L;
    }

    private boolean isRunActive(long runId) {
        return runId != 0L && this.activeRunId.get() == runId;
    }

    private boolean isCurrentProbe(ChunkProbe probe) {
        return isRunActive(probe.runId())
            && this.chunkProbes.get(probe.target().key()) == probe;
    }

    private void recordApiFailure(String detail) {
        this.apiFailures.incrementAndGet();
        recordGeneralFailure("API", detail);
    }

    private void recordEntityFailure(String detail) {
        this.entityFailures.incrementAndGet();
        recordGeneralFailure("ENTITY", detail);
    }

    private void recordChunkFailure(ChunkProbe probe, String detail) {
        if (probe != null && !probe.markFailed()) {
            return;
        }
        this.chunkFailures.incrementAndGet();
        recordGeneralFailure("CHUNK", detail);
    }

    private void recordSaturationFailure(String detail) {
        this.saturationFailures.incrementAndGet();
        recordGeneralFailure("SATURATION", detail);
    }

    private void recordGeneralFailure(String workload, String detail) {
        this.workloadFailures.incrementAndGet();
        getLogger().severe("ERROR TORTURE_" + workload + "_FAILURE " + detail);
    }

    private void resetCounters() {
        this.entitySpawnEvents.set(0L);
        this.creatureSpawnEvents.set(0L);
        this.projectileLaunchEvents.set(0L);
        this.chunkLoadEvents.set(0L);
        this.chunkUnloadEvents.set(0L);
        this.schedulerSyncIterations.set(0L);
        this.schedulerAsyncIterations.set(0L);
        this.apiIterations.set(0L);
        this.apiInventoriesBuilt.set(0L);
        this.apiItemsBuilt.set(0L);
        this.apiKeysCreated.set(0L);
        this.apiFailures.set(0L);
        this.entityIterations.set(0L);
        this.entitySpawnAttempts.set(0L);
        this.entitiesSpawned.set(0L);
        this.entitiesRemoved.set(0L);
        this.entitiesRetired.set(0L);
        this.entitiesAlreadyInvalid.set(0L);
        this.targetEntitySpawnEvents.set(0L);
        this.targetCreatureSpawnEvents.set(0L);
        this.targetProjectileLaunchEvents.set(0L);
        this.entityFailures.set(0L);
        this.chunkIterations.set(0L);
        this.chunkLoadAttempts.set(0L);
        this.chunkLoadAccepted.set(0L);
        this.targetChunkLoadEvents.set(0L);
        this.chunkUnloadRequests.set(0L);
        this.chunkUnloadAccepted.set(0L);
        this.chunkUnloadRejected.set(0L);
        this.targetChunkUnloadEvents.set(0L);
        this.chunkUnloadAccessChecks.set(0L);
        this.chunkUnloadAccessFailures.set(0L);
        this.chunkUnloadVerified.set(0L);
        this.chunkUnloadTimeouts.set(0L);
        this.chunkUnloadWaitPolls.set(0L);
        this.chunkUnloadEventMaxLatencyMillis.set(0L);
        this.chunkPreconditionUnloadRequests.set(0L);
        this.unexpectedGeneratedChunks.set(0L);
        this.chunkBusySkips.set(0L);
        this.chunkAborted.set(0L);
        this.chunkCleanupUnloadRequests.set(0L);
        this.chunkFailures.set(0L);
        this.saturationIterations.set(0L);
        this.saturationLoadAttempts.set(0L);
        this.saturationGeneratedChunks.set(0L);
        this.saturationUnloadRequests.set(0L);
        this.saturationUnloadAccepted.set(0L);
        this.saturationAborted.set(0L);
        this.saturationCleanupUnloadRequests.set(0L);
        this.saturationFailures.set(0L);
        this.workloadFailures.set(0L);
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(
            "CardboardTorture running=" + isRunning()
                + " profile=" + profileName(
                    this.activeProfile == null ? this.lastProfile : this.activeProfile
                )
                + " preparingChunks=" + (this.preparationTask != null)
                + " preparingEntity=" + (this.entityPreparationTask != null)
                + " entityPrepared=" + this.entityPrepared
        );
        sender.sendMessage(
            "sync=" + this.schedulerSyncIterations.get()
                + " async=" + this.schedulerAsyncIterations.get()
                + " trackedEntities=" + this.spawned.size()
        );
        sender.sendMessage(
            "api iterations=" + this.apiIterations.get()
                + " inventories=" + this.apiInventoriesBuilt.get()
                + " items=" + this.apiItemsBuilt.get()
                + " keys=" + this.apiKeysCreated.get()
                + " failures=" + this.apiFailures.get()
        );
        sender.sendMessage(
            "entities iterations=" + this.entityIterations.get()
                + " attempts=" + this.entitySpawnAttempts.get()
                + " spawned=" + this.entitiesSpawned.get()
                + " removed=" + this.entitiesRemoved.get()
                + " retired=" + this.entitiesRetired.get()
                + " alreadyInvalid=" + this.entitiesAlreadyInvalid.get()
                + " failures=" + this.entityFailures.get()
        );
        sender.sendMessage(
            "events entity=" + this.entitySpawnEvents.get()
                + " creature=" + this.creatureSpawnEvents.get()
                + " projectile=" + this.projectileLaunchEvents.get()
                + " chunkLoad=" + this.chunkLoadEvents.get()
                + " chunkUnload=" + this.chunkUnloadEvents.get()
        );
        sender.sendMessage(
            "targetEvents entity=" + this.targetEntitySpawnEvents.get()
                + " creature=" + this.targetCreatureSpawnEvents.get()
                + " projectile=" + this.targetProjectileLaunchEvents.get()
                + " chunkLoad=" + this.targetChunkLoadEvents.get()
                + " chunkUnload=" + this.targetChunkUnloadEvents.get()
        );
        sender.sendMessage(
            "chunks attempts=" + this.chunkLoadAttempts.get()
                + " loadAccepted=" + this.chunkLoadAccepted.get()
                + " unloadRequests=" + this.chunkUnloadRequests.get()
                + " unloadAccepted=" + this.chunkUnloadAccepted.get()
                + " unloadVerified=" + this.chunkUnloadVerified.get()
                + " pending=" + this.chunkProbes.size()
                + " aborted=" + this.chunkAborted.get()
                + " cleanupUnloads=" + this.chunkCleanupUnloadRequests.get()
                + " failures=" + this.chunkFailures.get()
        );
        sender.sendMessage(
            "chunkDetails rejected=" + this.chunkUnloadRejected.get()
                + " timeouts=" + this.chunkUnloadTimeouts.get()
                + " accessChecks=" + this.chunkUnloadAccessChecks.get()
                + " accessFailures=" + this.chunkUnloadAccessFailures.get()
                + " waitPolls=" + this.chunkUnloadWaitPolls.get()
                + " eventMaxLatencyMs=" + this.chunkUnloadEventMaxLatencyMillis.get()
                + " preconditionUnloads=" + this.chunkPreconditionUnloadRequests.get()
                + " unexpectedGenerated=" + this.unexpectedGeneratedChunks.get()
                + " busySkips=" + this.chunkBusySkips.get()
        );
        sender.sendMessage(
            "saturation iterations=" + this.saturationIterations.get()
                + " attempts=" + this.saturationLoadAttempts.get()
                + " generated=" + this.saturationGeneratedChunks.get()
                + " unloadRequests=" + this.saturationUnloadRequests.get()
                + " unloadAccepted=" + this.saturationUnloadAccepted.get()
                + " pending=" + this.saturationTargets.size()
                + " aborted=" + this.saturationAborted.get()
                + " cleanupUnloads=" + this.saturationCleanupUnloadRequests.get()
                + " failures=" + this.saturationFailures.get()
        );
        sender.sendMessage(
            "workload failures=" + this.workloadFailures.get()
                + " auxiliaryTasks=" + this.auxiliaryTasks.size()
                + " chunkPool=" + this.normalChunkTargets.size()
                + " entityAnchors=" + this.entityAnchors.size()
                + " entityTickets=" + this.entityTicketTargets.size()
        );
        sender.sendMessage(
            "prepare running=" + (this.preparationTask != null)
                + " processed=" + this.preparationIndex + "/" + this.preparationTargets.size()
                + " preparedTargets="
                + (this.preparationAlreadyGenerated + this.preparationGenerated)
                + "/" + this.preparationTotalTargets
                + " generated=" + this.preparationGenerated
                + " alreadyGenerated=" + this.preparationAlreadyGenerated
                + " failures=" + this.preparationFailures
        );
        sender.sendMessage(
            "entityPrepare running=" + (this.entityPreparationTask != null)
                + " ready=" + this.entityPrepared
                + " anchors=" + this.entityAnchors.size()
                + " tickets=" + this.entityTicketTargets.size()
                + " failures=" + this.entityPreparationFailures
        );
    }

    private String statusSummary() {
        return "profile=" + profileName(
            this.activeProfile == null ? this.lastProfile : this.activeProfile
        )
            + " sync=" + this.schedulerSyncIterations.get()
            + " async=" + this.schedulerAsyncIterations.get()
            + " apiIterations=" + this.apiIterations.get()
            + " apiItems=" + this.apiItemsBuilt.get()
            + " entityIterations=" + this.entityIterations.get()
            + " entityAttempts=" + this.entitySpawnAttempts.get()
            + " entitySpawned=" + this.entitiesSpawned.get()
            + " entityRemoved=" + this.entitiesRemoved.get()
            + " entityRetired=" + this.entitiesRetired.get()
            + " entityAlreadyInvalid=" + this.entitiesAlreadyInvalid.get()
            + " trackedEntities=" + this.spawned.size()
            + " chunkAttempts=" + this.chunkLoadAttempts.get()
            + " chunkLoadEvents=" + this.targetChunkLoadEvents.get()
            + " chunkUnloadRequests=" + this.chunkUnloadRequests.get()
            + " chunkUnloadEvents=" + this.targetChunkUnloadEvents.get()
            + " chunkUnloadAccessChecks=" + this.chunkUnloadAccessChecks.get()
            + " chunkUnloadAccessFailures=" + this.chunkUnloadAccessFailures.get()
            + " chunkUnloadVerified=" + this.chunkUnloadVerified.get()
            + " chunkPending=" + this.chunkProbes.size()
            + " chunkAborted=" + this.chunkAborted.get()
            + " saturationGenerated=" + this.saturationGeneratedChunks.get()
            + " failures=" + this.workloadFailures.get();
    }

    private static String profileName(WorkloadPlan.Profile profile) {
        return profile.name().toLowerCase(Locale.ROOT);
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!isRunning()) {
            return;
        }
        this.entitySpawnEvents.incrementAndGet();
        ExpectedSpawn expectation = this.expectedSpawn.get();
        if (expectation != null) {
            expectation.observeEntityEvent(event.getEntity().getUniqueId());
        }
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!isRunning()) {
            return;
        }
        this.creatureSpawnEvents.incrementAndGet();
        ExpectedSpawn expectation = this.expectedSpawn.get();
        if (expectation != null) {
            expectation.observeCreatureEvent(event.getEntity().getUniqueId());
        }
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!isRunning()) {
            return;
        }
        this.projectileLaunchEvents.incrementAndGet();
        ExpectedSpawn expectation = this.expectedSpawn.get();
        if (expectation != null) {
            expectation.observeProjectileEvent(event.getEntity().getUniqueId());
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!isRunning()) {
            return;
        }
        this.chunkLoadEvents.incrementAndGet();
        ChunkKey key = new ChunkKey(
            event.getWorld().getUID(),
            event.getChunk().getX(),
            event.getChunk().getZ()
        );
        ChunkProbe probe = this.chunkProbes.get(key);
        if (probe != null && !probe.loadRequested()) {
            recordChunkFailure(
                probe,
                "target ChunkLoadEvent arrived before load request for "
                    + probe.target().describe()
            );
        } else if (probe != null && probe.unloadRequested()) {
            recordChunkFailure(
                probe,
                "target ChunkLoadEvent arrived after unload request for "
                    + probe.target().describe()
            );
        } else if (probe != null && probe.observeLoadEvent()) {
            this.targetChunkLoadEvents.incrementAndGet();
            if (event.isNewChunk()) {
                this.unexpectedGeneratedChunks.incrementAndGet();
                recordChunkFailure(
                    probe,
                    "normal load unexpectedly generated terrain " + probe.target().describe()
                );
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (!isRunning()) {
            return;
        }
        this.chunkUnloadEvents.incrementAndGet();
        ChunkKey key = new ChunkKey(
            event.getWorld().getUID(),
            event.getChunk().getX(),
            event.getChunk().getZ()
        );
        ChunkProbe probe = this.chunkProbes.get(key);
        if (probe != null && !event.getWorld().isChunkLoaded(
            event.getChunk().getX(),
            event.getChunk().getZ()
        )) {
            recordChunkFailure(
                probe,
                "Paper ordering violated: target chunk was not loaded during ChunkUnloadEvent for "
                    + probe.target().describe()
            );
        }
        if (probe != null && !probe.unloadRequested()) {
            recordChunkFailure(
                probe,
                "target ChunkUnloadEvent arrived before unload request for "
                    + probe.target().describe()
            );
        } else if (probe != null && probe.observeUnloadEvent()) {
            this.targetChunkUnloadEvents.incrementAndGet();
            verifyChunkUnloadAccess(event, probe);
            this.chunkUnloadEventMaxLatencyMillis.accumulateAndGet(
                probe.unloadElapsedMillis(),
                Math::max
            );
        }
    }

    private void verifyChunkUnloadAccess(ChunkUnloadEvent event, ChunkProbe probe) {
        this.chunkUnloadAccessChecks.incrementAndGet();
        try {
            Chunk.LoadLevel loadLevel = event.getChunk().getLoadLevel();
            if (loadLevel == Chunk.LoadLevel.INACCESSIBLE
                || loadLevel == Chunk.LoadLevel.UNLOADED) {
                this.chunkUnloadAccessFailures.incrementAndGet();
                recordChunkFailure(
                    probe,
                    "Paper accessibility violated: target chunk loadLevel=" + loadLevel
                        + " during ChunkUnloadEvent for " + probe.target().describe()
                );
                return;
            }

            if (!event.getChunk().isGenerated()) {
                this.chunkUnloadAccessFailures.incrementAndGet();
                recordChunkFailure(
                    probe,
                    "Paper accessibility violated: pregenerated target reported not generated "
                        + "during ChunkUnloadEvent for " + probe.target().describe()
                );
                return;
            }
            event.getChunk().getBlock(0, event.getWorld().getMinHeight(), 0).getType();
        } catch (RuntimeException exception) {
            this.chunkUnloadAccessFailures.incrementAndGet();
            recordChunkFailure(
                probe,
                "Paper accessibility violated: target chunk read threw during "
                    + "ChunkUnloadEvent for " + probe.target().describe() + " "
                    + exception.getClass().getName() + ": " + exception.getMessage()
            );
        }
    }

    private record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    }

    private record ChunkTarget(World world, int chunkX, int chunkZ) {
        private ChunkKey key() {
            return new ChunkKey(this.world.getUID(), this.chunkX, this.chunkZ);
        }

        private String describe() {
            return "world=" + this.world.getName() + " x=" + this.chunkX + " z=" + this.chunkZ;
        }
    }

    private record EntityAnchor(World world, int chunkX, int chunkZ, Location center) {
    }

    private static final class ChunkProbe {
        private final long runId;
        private final ChunkTarget target;
        private final AtomicBoolean loadRequested = new AtomicBoolean();
        private final AtomicBoolean loadEventObserved = new AtomicBoolean();
        private final AtomicBoolean unloadRequested = new AtomicBoolean();
        private final AtomicBoolean unloadEventObserved = new AtomicBoolean();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final AtomicLong unloadRequestedAtNanos = new AtomicLong();

        private ChunkProbe(long runId, ChunkTarget target) {
            this.runId = runId;
            this.target = target;
        }

        private long runId() {
            return this.runId;
        }

        private ChunkTarget target() {
            return this.target;
        }

        private boolean observeLoadEvent() {
            return this.loadEventObserved.compareAndSet(false, true);
        }

        private void markLoadRequested() {
            this.loadRequested.set(true);
        }

        private boolean loadRequested() {
            return this.loadRequested.get();
        }

        private boolean observeUnloadEvent() {
            return this.unloadEventObserved.compareAndSet(false, true);
        }

        private void markUnloadRequested() {
            this.unloadRequestedAtNanos.set(System.nanoTime());
            this.unloadRequested.set(true);
        }

        private boolean unloadRequested() {
            return this.unloadRequested.get();
        }

        private boolean loadEventObserved() {
            return this.loadEventObserved.get();
        }

        private boolean unloadEventObserved() {
            return this.unloadEventObserved.get();
        }

        private long unloadElapsedMillis() {
            long requestedAt = this.unloadRequestedAtNanos.get();
            if (requestedAt == 0L) {
                return 0L;
            }
            return Math.max(0L, (System.nanoTime() - requestedAt) / 1_000_000L);
        }

        private boolean markFailed() {
            return this.failed.compareAndSet(false, true);
        }

        private boolean failed() {
            return this.failed.get();
        }
    }

    private static final class ExpectedSpawn {
        private final Set<UUID> entityEvents = new HashSet<>();
        private final Set<UUID> creatureEvents = new HashSet<>();
        private final Set<UUID> projectileEvents = new HashSet<>();

        private void observeEntityEvent(UUID entityId) {
            this.entityEvents.add(entityId);
        }

        private void observeCreatureEvent(UUID entityId) {
            this.creatureEvents.add(entityId);
        }

        private void observeProjectileEvent(UUID entityId) {
            this.projectileEvents.add(entityId);
        }

        private boolean entityEventObserved(UUID entityId) {
            return this.entityEvents.contains(entityId);
        }

        private boolean creatureEventObserved(UUID entityId) {
            return this.creatureEvents.contains(entityId);
        }

        private boolean projectileEventObserved(UUID entityId) {
            return this.projectileEvents.contains(entityId);
        }
    }
}
