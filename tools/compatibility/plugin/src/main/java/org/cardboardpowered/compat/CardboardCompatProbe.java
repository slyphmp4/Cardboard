package org.cardboardpowered.compat;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Small deterministic runtime probe used by Point 4 of the Cardboard 26.2
 * compatibility matrix. The probe intentionally uses ordinary public Bukkit /
 * Paper API only, so the same jar can later be run against a reference Paper
 * server when a Cardboard result needs comparison.
 */
public final class CardboardCompatProbe extends JavaPlugin implements Listener {

    private final Map<String, ProbeResult> results = new LinkedHashMap<>();

    private boolean running;
    private long entitySpawnEvents;
    private long creatureSpawnEvents;
    private long projectileLaunchEvents;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("CardboardCompatProbe ready. Use /cardboardcompat run.");
    }

    @Override
    public boolean onCommand(
        CommandSender sender,
        Command command,
        String label,
        String[] args
    ) {
        String subcommand = args.length == 0 ? "status" : args[0].toLowerCase();

        return switch (subcommand) {
            case "run" -> {
                startProbe(sender);
                yield true;
            }
            case "status" -> {
                printSummary(sender);
                yield true;
            }
            case "summary" -> {
                printSummaryLine(sender);
                yield true;
            }
            case "failures" -> {
                printFailures(sender);
                yield true;
            }
            case "wave2b" -> {
                printWave2B(sender);
                yield true;
            }
            case "reset" -> {
                if (running) {
                    sender.sendMessage("CardboardCompatProbe is currently running.");
                } else {
                    results.clear();
                    sender.sendMessage("CardboardCompatProbe results cleared.");
                }
                yield true;
            }
            default -> false;
        };
    }

    private void startProbe(CommandSender sender) {
        if (running) {
            sender.sendMessage("CardboardCompatProbe is already running.");
            return;
        }

        running = true;
        results.clear();
        sender.sendMessage("CardboardCompatProbe started.");

        Bukkit.getScheduler().runTask(this, () -> runSynchronousChecks(sender));
    }

    private void runSynchronousChecks(CommandSender sender) {
        check(
            "server.bukkit-instance",
            () -> Bukkit.getServer() != null,
            "Bukkit.getServer() is available"
        );
        check(
            "server.primary-thread",
            Bukkit::isPrimaryThread,
            "probe callback executed on the primary thread"
        );
        check(
            "server.version",
            () -> Bukkit.getVersion() != null && !Bukkit.getVersion().isBlank(),
            Bukkit.getVersion()
        );
        check(
            "plugin.manager",
            () -> Bukkit.getPluginManager().getPlugin(getName()) == this,
            "PluginManager resolves CardboardCompatProbe"
        );
        check(
            "services.manager",
            () -> Bukkit.getServicesManager() != null,
            "ServicesManager is available"
        );

        if (sender instanceof RemoteConsoleCommandSender) {
            pass("command.sender.rcon", sender.getClass().getName());
        } else {
            skip(
                "command.sender.rcon",
                "run /cardboardcompat run over RCON for the strict RCON sender check"
            );
        }

        check(
            "permissions.admin-node",
            () -> sender.hasPermission("cardboard.compat.admin"),
            "command sender has cardboard.compat.admin"
        );

        checkRealPlugin("PlaceholderAPI");
        checkRealPlugin("UltraPermissions");
        checkRealPlugin("CoreProtect");

        Wave2CompatChecks.run(this, sender, this::pass, this::fail, this::skip);

        runWorldChecks();
        runItemAndInventoryChecks();
        runPersistenceCheck();
        runEntityAndEventChecks();

        try {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                boolean stayedOffPrimaryThread = !Bukkit.isPrimaryThread();
                Bukkit.getScheduler().runTask(this, () -> {
                    if (stayedOffPrimaryThread) {
                        pass(
                            "scheduler.async",
                            "async callback executed off the primary thread"
                        );
                    } else {
                        fail(
                            "scheduler.async",
                            "async callback unexpectedly executed on the primary thread"
                        );
                    }

                    if (Bukkit.isPrimaryThread()) {
                        pass(
                            "scheduler.async-return",
                            "async result returned to the primary thread"
                        );
                    } else {
                        fail(
                            "scheduler.async-return",
                            "completion callback did not return to the primary thread"
                        );
                    }

                    startWave2B(sender);
                });
            });
        } catch (Throwable throwable) {
            fail("scheduler.async", describe(throwable));
            skip(
                "scheduler.async-return",
                "legacy async scheduler failed before the return callback"
            );
            startWave2B(sender);
        }
    }

    private void startWave2B(CommandSender sender) {
        try {
            Wave2BCompatChecks.start(
                this,
                this::pass,
                this::fail,
                this::skip,
                () -> {
                    if (Bukkit.isPrimaryThread()) {
                        finishProbe(sender);
                    } else {
                        Bukkit.getScheduler().runTask(
                            this,
                            () -> finishProbe(sender)
                        );
                    }
                }
            );
        } catch (Throwable throwable) {
            fail("wave2b.bootstrap", describe(throwable));
            finishProbe(sender);
        }
    }

    private void runWorldChecks() {
        if (Bukkit.getWorlds().isEmpty()) {
            fail("world.registry", "Bukkit.getWorlds() returned no worlds");
            skip("world.block-read", "no world available");
            return;
        }

        World world = Bukkit.getWorlds().get(0);
        check(
            "world.registry",
            () -> Bukkit.getWorld(world.getUID()) == world
                && Bukkit.getWorld(world.getName()) == world,
            world.getName()
        );

        Location spawn = world.getSpawnLocation();
        check(
            "world.block-read",
            () -> world.getBlockAt(spawn).getType() != null,
            "read block at world spawn"
        );

        check(
            "world.chunk-access",
            () -> world.getChunkAt(spawn).isLoaded(),
            "spawn chunk is accessible and loaded"
        );
    }

    private void runItemAndInventoryChecks() {
        check(
            "inventory.basic",
            () -> {
                Inventory inventory = Bukkit.createInventory(null, 9);
                ItemStack stack = new ItemStack(Material.STONE, 3);
                inventory.setItem(0, stack);
                ItemStack stored = inventory.getItem(0);
                return stored != null
                    && stored.getType() == Material.STONE
                    && stored.getAmount() == 3;
            },
            "create/set/get inventory item"
        );

        check(
            "itemmeta.pdc",
            () -> {
                ItemStack stack = new ItemStack(Material.PAPER);
                ItemMeta meta = stack.getItemMeta();
                if (meta == null) {
                    return false;
                }

                NamespacedKey key = new NamespacedKey(this, "probe");
                meta.getPersistentDataContainer().set(
                    key,
                    PersistentDataType.STRING,
                    "cardboard"
                );
                stack.setItemMeta(meta);

                ItemMeta roundTrip = stack.getItemMeta();
                if (roundTrip == null) {
                    return false;
                }

                String value = roundTrip.getPersistentDataContainer().get(
                    key,
                    PersistentDataType.STRING
                );
                return "cardboard".equals(value);
            },
            "ItemMeta PersistentDataContainer round-trip"
        );
    }

    private void runPersistenceCheck() {
        check(
            "plugin.config-save-reload",
            () -> {
                String token = UUID.randomUUID().toString();
                getConfig().set("probe.last-run-token", token);
                saveConfig();
                reloadConfig();
                return token.equals(getConfig().getString("probe.last-run-token"));
            },
            "plugin-owned config survives saveConfig/reloadConfig"
        );
    }

    private void runEntityAndEventChecks() {
        if (Bukkit.getWorlds().isEmpty()) {
            skip("entity.spawn-remove", "no world available");
            skip("event.entity-spawn", "no world available");
            skip("event.creature-spawn", "no world available");
            skip("event.projectile-launch", "no world available");
            return;
        }

        World world = Bukkit.getWorlds().get(0);
        Location location = world.getSpawnLocation().clone().add(0.5, 1.0, 0.5);
        world.getChunkAt(location);

        long entityBefore = entitySpawnEvents;
        Entity armorStand = null;
        try {
            armorStand = world.spawnEntity(location, EntityType.ARMOR_STAND);
            if (armorStand != null && armorStand.isValid()) {
                pass("entity.spawn-remove", "spawned temporary ARMOR_STAND");
            } else {
                fail("entity.spawn-remove", "ARMOR_STAND was not valid after spawn");
            }
            if (entitySpawnEvents > entityBefore) {
                pass("event.entity-spawn", "EntitySpawnEvent observed synchronously");
            } else {
                fail("event.entity-spawn", "EntitySpawnEvent was not observed");
            }
        } catch (Throwable throwable) {
            fail("entity.spawn-remove", describe(throwable));
            fail("event.entity-spawn", "entity spawn failed before event validation");
        } finally {
            if (armorStand != null) {
                armorStand.remove();
            }
        }

        long creatureBefore = creatureSpawnEvents;
        Entity zombie = null;
        try {
            zombie = world.spawnEntity(location, EntityType.ZOMBIE);
            if (creatureSpawnEvents > creatureBefore) {
                pass("event.creature-spawn", "CreatureSpawnEvent observed synchronously");
            } else {
                fail("event.creature-spawn", "CreatureSpawnEvent was not observed");
            }
        } catch (Throwable throwable) {
            fail("event.creature-spawn", describe(throwable));
        } finally {
            if (zombie != null) {
                zombie.remove();
            }
        }

        long projectileBefore = projectileLaunchEvents;
        Entity snowball = null;
        try {
            snowball = world.spawnEntity(location, EntityType.SNOWBALL);
            if (projectileLaunchEvents > projectileBefore) {
                pass(
                    "event.projectile-launch",
                    "ProjectileLaunchEvent observed synchronously"
                );
            } else {
                fail(
                    "event.projectile-launch",
                    "ProjectileLaunchEvent was not observed"
                );
            }
        } catch (Throwable throwable) {
            fail("event.projectile-launch", describe(throwable));
        } finally {
            if (snowball != null) {
                snowball.remove();
            }
        }
    }

    private void checkRealPlugin(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        String id = "real-plugin." + name.toLowerCase();
        if (plugin == null) {
            skip(id, name + " is not installed in this environment");
        } else if (plugin.isEnabled()) {
            pass(id, name + " " + plugin.getPluginMeta().getVersion());
        } else {
            fail(id, name + " is installed but disabled");
        }
    }

    private void check(String id, CheckedBoolean check, String successDetail) {
        try {
            if (check.getAsBoolean()) {
                pass(id, successDetail);
            } else {
                fail(id, "check returned false");
            }
        } catch (Throwable throwable) {
            fail(id, describe(throwable));
        }
    }

    private void pass(String id, String detail) {
        results.put(id, new ProbeResult(Status.PASS, detail));
    }

    private void fail(String id, String detail) {
        results.put(id, new ProbeResult(Status.FAIL, detail));
    }

    private void skip(String id, String detail) {
        results.put(id, new ProbeResult(Status.SKIP, detail));
    }

    private void finishProbe(CommandSender sender) {
        running = false;
        printSummary(sender);
    }

    private String summaryLine() {
        EnumMap<Status, Integer> counts = new EnumMap<>(Status.class);

        for (Status status : Status.values()) {
            counts.put(status, 0);
        }

        for (ProbeResult result : results.values()) {
            counts.compute(
                result.status(),
                (ignored, count) -> count == null ? 1 : count + 1
            );
        }

        return "CardboardCompatProbe running=" + running
            + " total=" + results.size()
            + " pass=" + counts.get(Status.PASS)
            + " fail=" + counts.get(Status.FAIL)
            + " skip=" + counts.get(Status.SKIP)
            + " unsupported=" + counts.get(Status.UNSUPPORTED)
            + " paperDifference=" + counts.get(Status.PAPER_DIFFERENCE);
    }

    private void printSummaryLine(CommandSender sender) {
        sender.sendMessage(summaryLine());
    }

    private void printSummary(CommandSender sender) {
        sender.sendMessage(summaryLine());

        for (Map.Entry<String, ProbeResult> entry : results.entrySet()) {
            sender.sendMessage(formatResult(entry));
        }
    }

    private void printFailures(CommandSender sender) {
        sender.sendMessage(summaryLine());

        boolean found = false;

        for (Map.Entry<String, ProbeResult> entry : results.entrySet()) {
            if (entry.getValue().status() != Status.PASS) {
                sender.sendMessage(formatResult(entry));
                found = true;
            }
        }

        if (!found) {
            sender.sendMessage("No non-PASS results.");
        }
    }

    private void printWave2B(CommandSender sender) {
        sender.sendMessage(summaryLine());

        for (Map.Entry<String, ProbeResult> entry : results.entrySet()) {
            String id = entry.getKey();

            if (
                id.startsWith("paper.scheduler.")
                    || id.startsWith("chunk.")
                    || id.startsWith("wave2b.")
            ) {
                sender.sendMessage(formatResult(entry));
            }
        }
    }

    private static String formatResult(
        Map.Entry<String, ProbeResult> entry
    ) {
        ProbeResult result = entry.getValue();

        return "[" + result.status() + "] "
            + entry.getKey()
            + " - "
            + result.detail();
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        entitySpawnEvents++;
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        creatureSpawnEvents++;
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        projectileLaunchEvents++;
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getName()
            + (message == null || message.isBlank() ? "" : ": " + message);
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean getAsBoolean() throws Exception;
    }

    private enum Status {
        PASS,
        FAIL,
        SKIP,
        UNSUPPORTED,
        PAPER_DIFFERENCE
    }

    private record ProbeResult(Status status, String detail) {
    }
}
