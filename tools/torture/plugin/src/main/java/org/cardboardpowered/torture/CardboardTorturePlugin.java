package org.cardboardpowered.torture;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
    private final Set<UUID> spawned = ConcurrentHashMap.newKeySet();
    private final AtomicLong entitySpawnEvents = new AtomicLong();
    private final AtomicLong creatureSpawnEvents = new AtomicLong();
    private final AtomicLong projectileLaunchEvents = new AtomicLong();
    private final AtomicLong chunkLoadEvents = new AtomicLong();
    private final AtomicLong chunkUnloadEvents = new AtomicLong();
    private final AtomicLong syncIterations = new AtomicLong();
    private final AtomicLong asyncIterations = new AtomicLong();

    private BukkitTask syncTask;
    private BukkitTask asyncTask;
    private BukkitTask stopTask;
    private NamespacedKey pdcKey;
    private long startedAtMillis;
    private long deadlineMillis;

    @Override
    public void onEnable() {
        this.pdcKey = new NamespacedKey(this, "torture_iteration");
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("CardboardTorture ready. Use /cardboardtorture start [seconds].");
    }

    @Override
    public void onDisable() {
        stopWorkload();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("cardboardtorture")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage("Usage: /cardboardtorture <start|stop|status> [seconds]");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                long seconds = 300L;
                if (args.length >= 2) {
                    try {
                        seconds = Math.max(10L, Long.parseLong(args[1]));
                    } catch (NumberFormatException ignored) {
                        sender.sendMessage("Seconds must be an integer >= 10.");
                        return true;
                    }
                }
                startWorkload(seconds);
                sender.sendMessage("Cardboard torture workload started for " + seconds + " seconds.");
                return true;
            }
            case "stop" -> {
                stopWorkload();
                sender.sendMessage("Cardboard torture workload stopped and generated entities cleaned up.");
                return true;
            }
            case "status" -> {
                sendStatus(sender);
                return true;
            }
            default -> {
                sender.sendMessage("Usage: /cardboardtorture <start|stop|status> [seconds]");
                return true;
            }
        }
    }

    private void startWorkload(long seconds) {
        stopWorkload();
        resetCounters();
        this.startedAtMillis = System.currentTimeMillis();
        this.deadlineMillis = this.startedAtMillis + seconds * 1000L;

        this.syncTask = Bukkit.getScheduler().runTaskTimer(this, this::syncIteration, 1L, 1L);
        this.asyncTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            this,
            this::asyncIteration,
            1L,
            1L
        );
        this.stopTask = Bukkit.getScheduler().runTaskLater(this, this::stopWorkload, seconds * 20L);
    }

    private void syncIteration() {
        if (System.currentTimeMillis() >= this.deadlineMillis) {
            stopWorkload();
            return;
        }

        long iteration = this.syncIterations.incrementAndGet();
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return;
        }
        World world = worlds.get((int) (iteration % worlds.size()));
        Location spawn = world.getSpawnLocation();

        int offsetX = (int) ((iteration % 17L) - 8L);
        int offsetZ = (int) (((iteration / 17L) % 17L) - 8L);
        int chunkX = (spawn.getBlockX() >> 4) + offsetX;
        int chunkZ = (spawn.getBlockZ() >> 4) + offsetZ;

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if ((iteration & 3L) == 0L) {
            world.unloadChunkRequest(chunk.getX(), chunk.getZ());
        }

        Location entityLocation = spawn.clone().add(
            (iteration % 11L) - 5L,
            2.0,
            ((iteration / 11L) % 11L) - 5L
        );

        if (iteration % 5L == 0L) {
            spawnTemporary(world, entityLocation, EntityType.ARMOR_STAND, 40L);
        }
        if (iteration % 10L == 0L) {
            spawnTemporary(world, entityLocation.clone().add(1.0, 0.0, 0.0), EntityType.ZOMBIE, 40L);
        }
        if (iteration % 4L == 0L) {
            spawnTemporary(world, entityLocation.clone().add(0.0, 1.0, 1.0), EntityType.SNOWBALL, 20L);
        }

        Inventory inventory = Bukkit.createInventory(null, 54);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = new ItemStack((slot & 1) == 0 ? Material.STONE : Material.PAPER, 1);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("Cardboard torture #" + iteration + "/" + slot);
                meta.getPersistentDataContainer().set(
                    this.pdcKey,
                    PersistentDataType.LONG,
                    iteration
                );
                item.setItemMeta(meta);
            }
            inventory.setItem(slot, item);
        }

        if (iteration % 100L == 0L) {
            getLogger().info(
                "TORTURE_PROGRESS sync=" + this.syncIterations.get()
                    + " async=" + this.asyncIterations.get()
                    + " trackedEntities=" + this.spawned.size()
                    + " entityEvents=" + this.entitySpawnEvents.get()
                    + " creatureEvents=" + this.creatureSpawnEvents.get()
                    + " projectileEvents=" + this.projectileLaunchEvents.get()
                    + " chunkLoad=" + this.chunkLoadEvents.get()
                    + " chunkUnload=" + this.chunkUnloadEvents.get()
            );
        }
    }

    private void asyncIteration() {
        long iteration = this.asyncIterations.incrementAndGet();
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

    private void spawnTemporary(
        World world,
        Location location,
        EntityType type,
        long lifetimeTicks
    ) {
        try {
            Entity entity = world.spawnEntity(location, type);
            this.spawned.add(entity.getUniqueId());
            Bukkit.getScheduler().runTaskLater(this, () -> removeTracked(entity.getUniqueId()), lifetimeTicks);
        } catch (RuntimeException exception) {
            getLogger().severe(
                "TORTURE_SPAWN_FAILURE type=" + type + " "
                    + exception.getClass().getName() + ": " + exception.getMessage()
            );
            throw exception;
        }
    }

    private void removeTracked(UUID uuid) {
        if (!this.spawned.remove(uuid)) {
            return;
        }
        Entity entity = Bukkit.getEntity(uuid);
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private void stopWorkload() {
        cancel(this.syncTask);
        cancel(this.asyncTask);
        cancel(this.stopTask);
        this.syncTask = null;
        this.asyncTask = null;
        this.stopTask = null;

        List<UUID> toRemove = new ArrayList<>(this.spawned);
        for (UUID uuid : toRemove) {
            removeTracked(uuid);
        }

        if (this.startedAtMillis != 0L) {
            getLogger().info(
                "TORTURE_FINISHED durationMs=" + (System.currentTimeMillis() - this.startedAtMillis)
                    + " sync=" + this.syncIterations.get()
                    + " async=" + this.asyncIterations.get()
                    + " entityEvents=" + this.entitySpawnEvents.get()
                    + " creatureEvents=" + this.creatureSpawnEvents.get()
                    + " projectileEvents=" + this.projectileLaunchEvents.get()
                    + " chunkLoad=" + this.chunkLoadEvents.get()
                    + " chunkUnload=" + this.chunkUnloadEvents.get()
            );
        }
        this.startedAtMillis = 0L;
        this.deadlineMillis = 0L;
    }

    private void cancel(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private void resetCounters() {
        this.entitySpawnEvents.set(0L);
        this.creatureSpawnEvents.set(0L);
        this.projectileLaunchEvents.set(0L);
        this.chunkLoadEvents.set(0L);
        this.chunkUnloadEvents.set(0L);
        this.syncIterations.set(0L);
        this.asyncIterations.set(0L);
    }

    private void sendStatus(CommandSender sender) {
        boolean running = this.syncTask != null && !this.syncTask.isCancelled();
        sender.sendMessage("CardboardTorture running=" + running);
        sender.sendMessage(
            "sync=" + this.syncIterations.get()
                + " async=" + this.asyncIterations.get()
                + " trackedEntities=" + this.spawned.size()
        );
        sender.sendMessage(
            "events entity=" + this.entitySpawnEvents.get()
                + " creature=" + this.creatureSpawnEvents.get()
                + " projectile=" + this.projectileLaunchEvents.get()
                + " chunkLoad=" + this.chunkLoadEvents.get()
                + " chunkUnload=" + this.chunkUnloadEvents.get()
        );
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        this.entitySpawnEvents.incrementAndGet();
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        this.creatureSpawnEvents.incrementAndGet();
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        this.projectileLaunchEvents.incrementAndGet();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        this.chunkLoadEvents.incrementAndGet();
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        this.chunkUnloadEvents.incrementAndGet();
    }
}
