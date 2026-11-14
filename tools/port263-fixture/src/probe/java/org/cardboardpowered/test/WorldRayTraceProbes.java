package org.cardboardpowered.test;

import io.papermc.paper.math.Position;
import io.papermc.paper.raytracing.BlockCollisionMode;
import io.papermc.paper.raytracing.RayTraceTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/** Black-box API probes; no dependency on Cardboard internals. */
final class WorldRayTraceProbes {
    interface Checked { void run() throws Exception; }

    static void verify(World world, Plugin plugin, BiConsumer<String, Checked> check) {
        check.accept("ray-legacy-location-blocks", () -> {
            try (var scene = new Scene(world, plugin)) {
                var first = scene.block(4, Material.STONE);
                scene.block(8, Material.DIRT);
                var start = scene.start();
                var direction = new Vector(3, 0, 0);
                var original = start.clone();
                var hit = world.rayTraceBlocks(start, direction, 12);
                require(hit != null && first.equals(hit.getHitBlock()) && hit.getHitBlockFace() == BlockFace.WEST, "Legacy block ray missed its nearest block/face");
                require(Math.abs(hit.getHitPosition().getX() - first.getX()) < 0.00001, "Wrong block hit position");
                require(start.equals(original) && direction.equals(new Vector(3, 0, 0)), "Ray trace mutated caller inputs");
                require(world.rayTraceBlocks(start, direction, 2) == null, "Block beyond maximum distance was hit");
                var reverse = new Location(world, scene.x + 12.5, scene.y + 0.75, scene.z + 0.5);
                require(world.rayTraceBlocks(reverse, new Vector(-1, 0, 0), 12).getHitBlock().getType() == Material.DIRT, "Reverse ray selected wrong block");
            }
        });
        check.accept("ray-position-block-filters-and-fluids", () -> {
            try (var scene = new Scene(world, plugin)) {
                var first = scene.block(4, Material.STONE);
                var second = scene.block(8, Material.DIRT);
                var start = Position.fine(scene.start());
                var direction = new Vector(1, 0, 0);
                var filtered = world.rayTraceBlocks(start, direction, 12, FluidCollisionMode.NEVER, false, b -> b.getType() != Material.STONE);
                require(filtered != null && second.equals(filtered.getHitBlock()), "Block filter stopped at excluded foreground block");
                require(world.rayTraceBlocks(start, direction, 12, FluidCollisionMode.NEVER, false, b -> false) == null, "Excluded blocks still collided");
                var water = scene.block(3, Material.WATER);
                require(first.equals(world.rayTraceBlocks(start, direction, 12, FluidCollisionMode.NEVER, false, null).getHitBlock()), "NEVER fluid mode collided with water");
                require(water.equals(world.rayTraceBlocks(start, direction, 12, FluidCollisionMode.ALWAYS, false, null).getHitBlock()), "ALWAYS fluid mode missed water");
                require(water.equals(world.rayTraceBlocks(start, direction, 12, FluidCollisionMode.SOURCE_ONLY, false, null).getHitBlock()), "SOURCE_ONLY missed source water");
                water.setBlockData(Bukkit.createBlockData("minecraft:water[level=1]"), false);
                require(first.equals(world.rayTraceBlocks(start, direction, 12, FluidCollisionMode.SOURCE_ONLY, false, null).getHitBlock()), "SOURCE_ONLY hit flowing water");
                require(first.equals(world.rayTraceBlocks(start, direction, 12, FluidCollisionMode.ALWAYS, false, b -> b.getType() != Material.WATER).getHitBlock()), "Block filter did not exclude its fluid shape");
            }
        });
        check.accept("ray-entities-combined-nearest-and-radius", () -> {
            try (var scene = new Scene(world, plugin)) {
                var first = scene.block(4, Material.STONE);
                scene.block(8, Material.DIRT);
                var stand = scene.stand(6.5);
                var start = Position.fine(scene.start());
                var direction = new Vector(1, 0, 0);
                var entityHit = world.rayTraceEntities(start, direction, 12, 0, e -> e.equals(stand));
                require(entityHit != null && stand.equals(entityHit.getHitEntity()), "Position entity trace missed its target");
                require(world.rayTraceEntities(start, direction, 12, 0, e -> false) == null, "Entity filter was ignored");
                require(first.equals(world.rayTrace(start, direction, 12, FluidCollisionMode.NEVER, false, 0, e -> e.equals(stand), null).getHitBlock()), "Combined trace hit entity through a foreground block");
                require(stand.equals(world.rayTrace(start, direction, 12, FluidCollisionMode.NEVER, false, 0, e -> e.equals(stand), b -> b.getType() != Material.STONE).getHitEntity()), "Combined filtered trace selected the farther block");
                var offset = start.offset(0, 0, 0.6);
                require(world.rayTraceEntities(offset, direction, 12, 0, e -> e.equals(stand)) == null, "Zero-radius entity ray hit outside its box");
                require(stand.equals(world.rayTraceEntities(offset, direction, 12, 0.5, e -> e.equals(stand)).getHitEntity()), "Entity ray size did not expand collision box");
            }
        });
        check.accept("ray-builder-targets-modes-and-input-copies", () -> {
            try (var scene = new Scene(world, plugin)) {
                var stone = scene.block(4, Material.STONE);
                var dirt = scene.block(8, Material.DIRT);
                var stand = scene.stand(6.5);
                var start = scene.start();
                var direction = new Vector(1, 0, 0);
                var hit = world.rayTrace(b -> {
                    b.start(start).direction(direction).maxDistance(12).targets(RayTraceTarget.BLOCK);
                    start.setX(start.getX() + 100);
                    direction.setX(-1);
                });
                require(hit != null && stone.equals(hit.getHitBlock()), "Builder retained mutable caller inputs");
                require(stand.equals(world.rayTrace(b -> b.start(scene.start()).direction(new Vector(1, 0, 0)).maxDistance(12)
                        .targets(RayTraceTarget.ENTITY).entityFilter(e -> e.equals(stand))).getHitEntity()), "Entity-only builder was blocked by terrain");
                require(dirt.equals(world.rayTrace(b -> b.start(scene.start()).direction(new Vector(1, 0, 0)).maxDistance(12)
                        .targets(RayTraceTarget.BLOCK).blockFilter(block -> block.getType() != Material.STONE)).getHitBlock()), "Builder block filter was ignored");
                require(stand.equals(world.rayTrace(b -> b.start(scene.start()).direction(new Vector(1, 0, 0)).maxDistance(12)
                        .targets(RayTraceTarget.BLOCK, RayTraceTarget.ENTITY).entityFilter(e -> e.equals(stand))
                        .blockFilter(block -> block.getType() != Material.STONE)).getHitEntity()), "Builder combined targets selected wrong hit");
                scene.block(3, Material.COBWEB);
                require(world.rayTrace(b -> b.start(scene.start()).direction(new Vector(1, 0, 0)).maxDistance(12)
                        .targets(RayTraceTarget.BLOCK).ignorePassableBlocks(true)).getHitBlock().equals(stone), "Collider mode hit passable cobweb");
                require(world.rayTrace(b -> b.start(scene.start()).direction(new Vector(1, 0, 0)).maxDistance(12)
                        .targets(RayTraceTarget.BLOCK).blockCollisionMode(BlockCollisionMode.OUTLINE)).getHitBlock().getType() == Material.COBWEB, "Outline mode missed cobweb shape");
                scene.block(3, Material.AIR);
                require(world.rayTrace(b -> b.start(scene.start()).direction(new Vector(1, 0, 0)).maxDistance(12)
                        .targets(RayTraceTarget.BLOCK).blockCollisionMode(BlockCollisionMode.VISUAL)).getHitBlock().equals(stone), "Visual collision mode missed solid block");
            }
        });
        check.accept("line-of-sight-blocked-clear-world-and-range", () -> {
            try (var scene = new Scene(world, plugin)) {
                var start = scene.start();
                var end = start.clone().add(10, 0, 0);
                require(world.lineOfSightExists(start, end), "Clear line of sight reported blocked");
                scene.block(4, Material.STONE);
                require(!world.lineOfSightExists(start, end), "Solid wall did not block line of sight");
                scene.block(4, Material.WATER);
                require(world.lineOfSightExists(start, end), "Water blocked line of sight");
                require(world.lineOfSightExists(start, start), "Same-position line of sight failed");
                require(!world.lineOfSightExists(start, start.clone().add(129, 0, 0)), "Line of sight exceeded Paper's 128-block limit");
                var other = Bukkit.getWorlds().stream().filter(w -> !w.equals(world)).findFirst().orElseThrow();
                require(!world.lineOfSightExists(start, new Location(other, end.getX(), end.getY(), end.getZ())), "Line of sight crossed worlds");
            }
        });
        check.accept("ray-validation-and-empty-result-contracts", () -> {
            try (var scene = new Scene(world, plugin)) {
                var start = Position.fine(scene.start());
                var direction = new Vector(1, 0, 0);
                expectIllegal(() -> world.rayTraceBlocks(start, new Vector(), 12, FluidCollisionMode.NEVER, false, null));
                expectIllegal(() -> world.rayTraceBlocks(Position.fine(Double.NaN, 0, 0), direction, 12, FluidCollisionMode.NEVER, false, null));
                var other = Bukkit.getWorlds().stream().filter(w -> !w.equals(world)).findFirst().orElseThrow();
                expectIllegal(() -> world.rayTraceBlocks((Position) new Location(other, 0, 0, 0), direction, 12, FluidCollisionMode.NEVER, false, null));
                require(world.rayTraceBlocks(start, direction, -1, FluidCollisionMode.NEVER, false, null) == null, "Negative ray distance must return no hit");
                expectIllegal(() -> world.rayTrace(b -> {}));
                expectIllegal(() -> world.rayTrace(b -> b.start(scene.start()).direction(direction).targets(RayTraceTarget.BLOCK)));
                expectIllegal(() -> world.rayTrace(b -> b.start(scene.start()).direction(direction).maxDistance(12)));
                expectIllegal(() -> world.rayTrace(b -> b.maxDistance(-1)));
                expectIllegal(() -> world.rayTrace(b -> b.raySize(-1)));
            }
        });
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void expectIllegal(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid ray trace argument was accepted");
    }

    private static final class Scene implements AutoCloseable {
        final World world;
        final Plugin plugin;
        final int x, y, z, chunkX, chunkZ;
        final boolean addedTicket;
        final Map<Block, BlockData> previous = new LinkedHashMap<>();
        final java.util.List<Entity> entities = new ArrayList<>();

        Scene(World world, Plugin plugin) {
            this.world = world;
            this.plugin = plugin;
            chunkX = world.getSpawnLocation().getBlockX() >> 4;
            chunkZ = world.getSpawnLocation().getBlockZ() >> 4;
            x = chunkX << 4;
            z = (chunkZ << 4) + 8;
            y = world.getSpawnLocation().getBlockY() + 8;
            addedTicket = world.addPluginChunkTicket(chunkX, chunkZ, plugin);
            for (int dx = 1; dx <= 13; dx++) for (int dy = 0; dy < 3; dy++) {
                var block = world.getBlockAt(x + dx, y + dy, z);
                previous.put(block, block.getBlockData());
                block.setType(Material.AIR, false);
            }
        }

        Location start() { return new Location(world, x + 1.25, y + 0.75, z + 0.5); }

        Block block(int offset, Material type) {
            var block = world.getBlockAt(x + offset, y, z);
            block.setType(type, false);
            return block;
        }

        ArmorStand stand(double offset) {
            var stand = world.spawn(new Location(world, x + offset, y, z + 0.5), ArmorStand.class, s -> s.setGravity(false));
            entities.add(stand);
            return stand;
        }

        @Override
        public void close() {
            entities.forEach(Entity::remove);
            previous.forEach((block, data) -> block.setBlockData(data, false));
            if (addedTicket) world.removePluginChunkTicket(chunkX, chunkZ, plugin);
        }
    }
}
