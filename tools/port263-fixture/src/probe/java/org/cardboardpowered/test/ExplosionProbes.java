package org.cardboardpowered.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.ExplosionResult;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Pig;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.plugin.Plugin;

/** Public, synchronous blast checks for disposable worlds only. */
final class ExplosionProbes {
    static void verify(World world, Plugin plugin, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("explosion-block-cancellation-and-overload-results", () -> {
            try (var scene = new Scene(world); var hooks = new Hooks(scene, plugin)) {
                scene.patch(Material.DIRT, 2);
                var before = scene.blocks();
                hooks.block = event -> event.setCancelled(true);
                Location p = scene.location;
                List<BooleanSupplier> calls = List.of(
                        () -> world.createExplosion(p.getX(), p.getY(), p.getZ(), 2f),
                        () -> world.createExplosion(p.getX(), p.getY(), p.getZ(), 2f, false),
                        () -> world.createExplosion(p.getX(), p.getY(), p.getZ(), 2f, false, true),
                        () -> world.createExplosion(p.getX(), p.getY(), p.getZ(), 2f, false, true, null),
                        () -> world.createExplosion(p, 2f),
                        () -> world.createExplosion(p, 2f, false),
                        () -> world.createExplosion(p, 2f, false, true),
                        () -> world.createExplosion(p, 2f, false, true, null));
                for (var call : calls) require(!call.getAsBoolean(), "Cancelled explosion returned true");
                require(hooks.blockCalls == calls.size() && hooks.entityCalls == 0, "Wrong block/source event count");
                require(scene.blocks().equals(before) && hooks.itemCalls == 0, "Cancelled blast changed blocks or spawned drops");
            }
        });
        check.accept("explosion-entity-source-and-mutable-block-list", () -> {
            try (var rule = new Rule(world, GameRules.MOB_GRIEFING, true);
                 var scene = new Scene(world); var hooks = new Hooks(scene, plugin)) {
                scene.patch(Material.DIRT, 2);
                var before = scene.blocks();
                var source = scene.pig(0, 0);
                Block selected = world.getBlockAt(scene.x + 1, scene.y, scene.z);
                Entity[] observed = new Entity[1];
                Location[] observedLocation = new Location[1];
                hooks.entity = event -> {
                    observed[0] = event.getEntity();
                    observedLocation[0] = event.getLocation().clone();
                    event.setYield(0);
                    event.blockList().clear();
                    event.blockList().add(selected);
                };
                require(world.createExplosion(scene.location, 2f, false, true, source), "Accepted source blast returned false");
                require(hooks.entityCalls == 1 && hooks.blockCalls == 0 && observed[0] == source, "Source event attribution/count is incorrect");
                require(observedLocation[0].getWorld().equals(world) && observedLocation[0].distanceSquared(scene.location) < 1e-12,
                        "Source event lost blast location");
                before.remove(scene.key(selected));
                require(selected.getType().isAir() && scene.blocks().equals(before), "Explosion ignored edited block list");
                require(hooks.itemCalls == 0 && scene.items().isEmpty(), "Zero-yield selected block spawned drops");
            }
        });
        check.accept("explosion-block-interactions-and-source-null-drop-decay", () -> {
            try (var griefing = new Rule(world, GameRules.MOB_GRIEFING, false);
                 var decay = new Rule(world, GameRules.BLOCK_EXPLOSION_DROP_DECAY, true);
                 var scene = new Scene(world); var hooks = new Hooks(scene, plugin)) {
                scene.patch(Material.DIRT, 2);
                var before = scene.blocks();
                require(world.createExplosion(scene.location, 2f, false, false), "Non-destructive blast returned false");
                require(hooks.blockCalls == 0 && hooks.entityCalls == 0 && scene.blocks().equals(before),
                        "breakBlocks=false changed terrain or emitted a block explosion event");
                var source = scene.pig(6, 0);
                require(world.createExplosion(scene.location, 2f, false, true, source), "Mob-rule-protected blast returned false");
                require(hooks.entityCalls == 0 && scene.blocks().equals(before), "Source blast ignored mobGriefing=false");
                float[] yield = {-1};
                ExplosionResult[] result = new ExplosionResult[1];
                hooks.block = event -> {
                    yield[0] = event.getYield();
                    result[0] = event.getExplosionResult();
                    event.setCancelled(true);
                };
                require(!world.createExplosion(scene.location, 2f, false, true), "Cancelled source-null blast returned true");
                require(hooks.blockCalls == 1 && yield[0] == 1f && result[0] == ExplosionResult.DESTROY,
                        "Source-null API blast inherited mob protection or block drop decay");
                require(scene.blocks().equals(before), "Source-null event cancellation changed terrain");
                var tntSource = world.spawn(scene.location, TNTPrimed.class);
                scene.entities.add(tntSource);
                tntSource.setFuseTicks(10000);
                hooks.entity = event -> event.setYield(0);
                require(world.createExplosion(scene.location, 2f, false, true, tntSource), "Accepted TNT-source blast returned false");
                require(hooks.entityCalls == 1 && !scene.blocks().equals(before), "TNT-source blast incorrectly inherited mobGriefing=false");
            }
        });
        check.accept("explosion-source-first-overloads-and-damage-exclusion", () -> {
            for (int mode = 0; mode < 3; mode++) {
                try (var scene = new Scene(world)) {
                    var source = scene.pig(0.4, 0);
                    var victim = scene.pig(-0.4, 0);
                    double sourceHealth = source.getHealth(), victimHealth = victim.getHealth();
                    boolean result = mode == 0
                            ? world.createExplosion(source, scene.location, .75f, false, false)
                            : world.createExplosion(source, scene.location, .75f, false, false, mode == 1);
                    require(result, "Source-first overload returned false without cancellation");
                    require(victim.getHealth() < victimHealth, "Source-first overload did not apply native blast damage");
                    require(mode == 2 ? source.getHealth() < sourceHealth : source.getHealth() == sourceHealth,
                            "excludeSourceFromDamage contract was ignored: mode=" + mode);
                }
            }
        });
        check.accept("explosion-yield-and-ignite-cancellation", () -> {
            try (var scene = new Scene(world); var hooks = new Hooks(scene, plugin)) {
                hooks.block = event -> event.setYield(0);
                hooks.ignite = event -> event.setCancelled(true);
                // Keep support below every candidate and give blast rays an air
                // origin: a solid wooden origin can leave only one fire roll.
                for (int dx = -Scene.RADIUS; dx <= Scene.RADIUS; dx++)
                    for (int dz = -Scene.RADIUS; dz <= Scene.RADIUS; dz++)
                        world.getBlockAt(scene.x + dx, scene.y - 1, scene.z + dz).setType(Material.BEDROCK, false);
                for (int attempt = 0; attempt < 8 && hooks.igniteCalls == 0; attempt++) {
                    scene.patch(Material.OAK_PLANKS, 3);
                    scene.location.getBlock().setType(Material.AIR, false);
                    require(world.createExplosion(scene.location, 3f, true, true), "Accepted fire blast returned false");
                }
                require(hooks.blockCalls > 0 && hooks.igniteCalls > 0,
                        "Fire blast did not exercise ignition callbacks: blockEvents=" + hooks.blockCalls
                        + ", igniteEvents=" + hooks.igniteCalls + ", fireBlocks=" + scene.count(Material.FIRE));
                require(scene.count(Material.FIRE) == 0 && scene.count(Material.SOUL_FIRE) == 0, "Cancelled ignition left fire");
                require(hooks.itemCalls == 0 && scene.items().isEmpty(), "Zero yield produced item drops");
            }
        });
        check.accept("explosion-tnt-chain-prime-cancellation", () -> {
            try (var enabled = new Rule(world, GameRules.TNT_EXPLODES, true);
                 var scene = new Scene(world); var hooks = new Hooks(scene, plugin)) {
                scene.patch(Material.DIRT, 2);
                var tnt = scene.location.getBlock();
                tnt.setType(Material.TNT, false);
                TNTPrimeEvent[] observed = new TNTPrimeEvent[1];
                hooks.block = event -> event.setYield(0);
                hooks.prime = event -> { observed[0] = event; event.setCancelled(true); };
                require(world.createExplosion(scene.location, 2f, false, true), "Accepted chain blast returned false");
                require(hooks.primeCalls == 1 && observed[0].getCause() == TNTPrimeEvent.PrimeCause.EXPLOSION
                        && observed[0].getPrimingEntity() == null && observed[0].getPrimingBlock().equals(tnt),
                        "Chain priming event attribution/count is incorrect");
                require(tnt.getType() == Material.TNT && world.getEntitiesByClass(TNTPrimed.class).stream().noneMatch(e -> scene.near(e.getLocation())),
                        "Cancelled chain priming removed TNT or spawned a primed entity");
            }
        });
        check.accept("explosion-nested-cancellation-and-location-contract", () -> {
            for (boolean cancelOuter : new boolean[]{false, true}) {
                try (var scene = new Scene(world); var hooks = new Hooks(scene, plugin)) {
                    scene.patch(Material.DIRT, 4);
                    var outer = scene.location.clone().add(-3, 0, 0);
                    var inner = scene.location.clone().add(3, 0, 0);
                    var outerCopy = outer.clone();
                    boolean[] innerResult = {false}, ranInner = {false};
                    hooks.block = event -> {
                        event.setYield(0);
                        if (event.getBlock().getX() == outer.getBlockX()) {
                            event.setCancelled(cancelOuter);
                            ranInner[0] = true;
                            innerResult[0] = world.createExplosion(inner, 1.5f, false, true);
                        } else {
                            event.setCancelled(!cancelOuter);
                        }
                    };
                    boolean result = world.createExplosion(outer, 1.5f, false, true);
                    require(ranInner[0] && hooks.blockCalls == 2 && result == !cancelOuter && innerResult[0] == cancelOuter,
                            "Nested blast overwrote its parent's cancellation state");
                    require(outer.equals(outerCopy), "Explosion changed the caller's Location");
                    expectIllegalArgument(() -> world.createExplosion((Location) null, 1f, false, true));
                    var foreign = scene.location.clone();
                    foreign.setWorld(null);
                    expectIllegalArgument(() -> world.createExplosion(foreign, 1f, false, true));
                }
            }
        });
    }

    static void verifyNative(World world, Plugin plugin, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("explosion-native-entrypoint-cancellation", () -> {
            try (var scene = new Scene(world); var hooks = new Hooks(scene, plugin)) {
                scene.patch(Material.DIRT, 2);
                var before = scene.blocks();
                hooks.block = event -> event.setCancelled(true);
                var fixture = Class.forName("org.cardboardpowered.test.Port263Fixture");
                var level = world.getClass().getMethod("getHandle").invoke(world);
                fixture.getMethod("nativeExplosion", Class.forName("net.minecraft.server.level.ServerLevel"),
                        double.class, double.class, double.class, float.class)
                        .invoke(null, level, scene.location.getX(), scene.location.getY(), scene.location.getZ(), 2f);
                require(hooks.blockCalls == 1 && scene.blocks().equals(before), "Native explosion bypassed Bukkit cancellation");
            }
        });
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void expectIllegalArgument(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static final class Rule implements AutoCloseable {
        final World world;
        final GameRule<Boolean> rule;
        final Boolean previous;
        Rule(World world, GameRule<Boolean> rule, boolean value) {
            this.world = world;
            this.rule = rule;
            previous = world.getGameRuleValue(rule);
            require(previous != null && world.setGameRule(rule, value), "Unable to prepare game rule " + rule.getName());
        }
        @Override public void close() { world.setGameRule(rule, previous); }
    }

    private static final class Hooks implements Listener, AutoCloseable {
        final Scene scene;
        int blockCalls, entityCalls, igniteCalls, itemCalls, primeCalls;
        Consumer<BlockExplodeEvent> block = event -> {};
        Consumer<EntityExplodeEvent> entity = event -> {};
        Consumer<BlockIgniteEvent> ignite = event -> {};
        Consumer<TNTPrimeEvent> prime = event -> {};
        Hooks(Scene scene, Plugin plugin) {
            this.scene = scene;
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        }
        @EventHandler public void onBlock(BlockExplodeEvent event) {
            if (scene.near(event.getBlock().getLocation())) { blockCalls++; block.accept(event); }
        }
        @EventHandler public void onEntity(EntityExplodeEvent event) {
            if (scene.near(event.getLocation())) { entityCalls++; entity.accept(event); }
        }
        @EventHandler public void onIgnite(BlockIgniteEvent event) {
            if (scene.near(event.getBlock().getLocation())) { igniteCalls++; ignite.accept(event); }
        }
        @EventHandler public void onPrime(TNTPrimeEvent event) {
            if (scene.near(event.getBlock().getLocation())) { primeCalls++; prime.accept(event); }
        }
        @EventHandler public void onItem(ItemSpawnEvent event) {
            if (scene.near(event.getLocation())) itemCalls++;
        }
        @Override public void close() { HandlerList.unregisterAll(this); }
    }

    private static final class Scene implements AutoCloseable {
        static final int RADIUS = 8, HEIGHT = 16;
        final World world;
        final int x, y, z;
        final Location location;
        final List<Entity> entities = new ArrayList<>();
        Scene(World world) {
            this.world = world;
            var spawn = world.getSpawnLocation();
            x = ((spawn.getBlockX() >> 4) << 4) + 8;
            z = ((spawn.getBlockZ() >> 4) << 4) + 8;
            y = world.getMaxHeight() - 64;
            location = new Location(world, x + .5, y + .5, z + .5);
            for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++)
                require(world.getHighestBlockYAt(x + dx, z + dz, HeightMap.WORLD_SURFACE) < y - 3,
                        "Explosion scene overlaps existing terrain");
            for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++)
                world.getBlockAt(x + dx, y - 1, z + dz).setType(Material.STONE, false);
        }
        void patch(Material type, int radius) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++)
                world.getBlockAt(x + dx, y, z + dz).setType(type, false);
        }
        Pig pig(double dx, double dz) {
            var pig = world.spawn(location.clone().add(dx, 0, dz), Pig.class);
            entities.add(pig);
            return pig;
        }
        boolean near(Location p) {
            return p.getWorld().equals(world) && Math.abs(p.getX() - location.getX()) <= RADIUS + 1
                    && Math.abs(p.getZ() - location.getZ()) <= RADIUS + 1 && Math.abs(p.getY() - location.getY()) <= HEIGHT;
        }
        String key(Block block) { return (block.getX() - x) + "," + (block.getY() - y) + "," + (block.getZ() - z); }
        Map<String, Material> blocks() {
            Map<String, Material> values = new LinkedHashMap<>();
            for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++)
                for (int dy = -2; dy < HEIGHT; dy++) {
                    var block = world.getBlockAt(x + dx, y + dy, z + dz);
                    if (!block.getType().isAir()) values.put(key(block), block.getType());
                }
            return values;
        }
        long count(Material type) { return blocks().values().stream().filter(value -> value == type).count(); }
        List<Entity> items() { return world.getEntitiesByClass(Item.class).stream().filter(e -> near(e.getLocation())).map(e -> (Entity) e).toList(); }
        @Override public void close() {
            for (var entity : entities) entity.remove();
            for (var item : items()) item.remove();
            for (var tnt : world.getEntitiesByClass(TNTPrimed.class)) if (near(tnt.getLocation())) tnt.remove();
            for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++)
                for (int dy = -2; dy < HEIGHT; dy++) {
                    var block = world.getBlockAt(x + dx, y + dy, z + dz);
                    if (!block.getType().isAir()) block.setType(Material.AIR, false);
                }
        }
    }
}
