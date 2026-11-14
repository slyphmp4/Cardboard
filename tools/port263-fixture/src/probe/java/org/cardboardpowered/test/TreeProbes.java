package org.cardboardpowered.test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.BlockChangeDelegate;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Public API checks shared unchanged by Cardboard and the pinned Paper control. */
final class TreeProbes {
    static void verify(World world, Plugin plugin, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("tree-basic-overloads-and-seeded-random", () -> {
            try (var scene = new Scene(world)) {
                require(world.generateTree(scene.location, TreeType.TREE), "Legacy tree generation returned false");
                require(scene.count("oak_log") > 0 && scene.count("oak_leaves") > 0, "Legacy tree has no oak trunk/leaves");
            }
            Map<String, String> first;
            try (var scene = new Scene(world)) {
                require(world.generateTree(scene.location, new Random(1044), TreeType.BIRCH), "Seeded tree generation returned false");
                require(scene.count("birch_log") > 0, "Seeded tree has wrong trunk");
                first = scene.blocks();
            }
            try (var scene = new Scene(world)) {
                require(world.generateTree(scene.location, new Random(1044), TreeType.BIRCH), "Repeated seeded generation failed");
                require(first.equals(scene.blocks()), "Caller Random did not reproduce tree block data");
            }
        });
        check.accept("tree-consumer-buffered-snapshots-and-edits", () -> {
            try (var scene = new Scene(world)) {
                var logs = new AtomicInteger();
                var point = scene.location.clone();
                boolean result = world.generateTree(scene.location, new Random(1044), TreeType.TREE,
                        (Consumer<BlockState>) state -> {
                            require(state.isPlaced() && state.getWorld().equals(world), "Tree snapshot is not bound to its world");
                            if (Tag.LOGS.isTagged(state.getType())) {
                                require(state.getBlock().getType().isAir(), "Tree trunk was placed before its callback");
                                logs.incrementAndGet();
                                state.setBlockData(Material.GOLD_BLOCK.createBlockData());
                            }
                        });
                require(result && logs.get() > 0, "Consumer never received generated trunk snapshots");
                require(scene.count("gold_block") == logs.get() && scene.count("oak_log") == 0, "Consumer block-data edits were not applied");
                require(scene.count("oak_leaves") > 0 && scene.location.equals(point), "Tree foliage or caller location changed incorrectly");
            }
        });
        check.accept("tree-predicate-veto-and-filtered-placement", () -> {
            try (var scene = new Scene(world)) {
                var callbacks = new AtomicInteger();
                var logs = new AtomicInteger();
                boolean result = world.generateTree(scene.location, new Random(1044), TreeType.TREE,
                        (Predicate<BlockState>) state -> {
                            callbacks.incrementAndGet();
                            if (Tag.LOGS.isTagged(state.getType())) {
                                require(state.getBlock().getType().isAir(), "Veto callback saw already-placed trunk");
                                logs.incrementAndGet();
                            }
                            return false;
                        });
                require(result && callbacks.get() > 0 && logs.get() > 0, "Veto predicate was not called for a generated tree");
                require(scene.blocks().isEmpty(), "Rejected tree left blocks in the world");
            }
            try (var scene = new Scene(world)) {
                boolean result = world.generateTree(scene.location, new Random(1044), TreeType.TREE,
                        (Predicate<BlockState>) state -> Tag.LOGS.isTagged(state.getType()));
                require(result && scene.count("oak_log") > 0 && scene.count("oak_leaves") == 0, "Tree predicate did not select only logs");
            }
        });
        check.accept("tree-legacy-block-change-delegate", () -> {
            try (var scene = new Scene(world)) {
                Map<String, BlockData> changes = new LinkedHashMap<>();
                BlockChangeDelegate delegate = new BlockChangeDelegate() {
                    private String key(int x, int y, int z) { return x + "," + y + "," + z; }
                    @Override public boolean setBlockData(int x, int y, int z, BlockData data) {
                        changes.put(key(x, y, z), data.clone());
                        return true;
                    }
                    @Override public BlockData getBlockData(int x, int y, int z) {
                        return changes.getOrDefault(key(x, y, z), world.getBlockData(x, y, z));
                    }
                    @Override public int getHeight() { return world.getMaxHeight(); }
                    @Override public boolean isEmpty(int x, int y, int z) { return getBlockData(x, y, z).getMaterial().isAir(); }
                };
                require(world.generateTree(scene.location, TreeType.TREE, delegate), "Legacy delegate generation failed");
                require(!changes.isEmpty() && changes.values().stream().anyMatch(data -> Tag.LOGS.isTagged(data.getMaterial())), "Legacy delegate received no trunk proposals");
                require(scene.blocks().isEmpty(), "Delegate-only generation changed the real world");
            }
        });
        check.accept("tree-modern-types-and-tile-snapshot-data", () -> {
            TreeType[] types = {TreeType.RED_POPLAR, TreeType.ORANGE_POPLAR, TreeType.YELLOW_POPLAR, TreeType.PALE_OAK};
            String[] leaves = {"red_poplar_leaves", "orange_poplar_leaves", "yellow_poplar_leaves", "pale_oak_leaves"};
            for (int i = 0; i < types.length; i++) {
                try (var scene = new Scene(world)) {
                    require(world.generateTree(scene.location, new Random(1044), types[i]), "Modern tree generation failed: " + types[i]);
                    String log = i == 3 ? "pale_oak_log" : "poplar_log";
                    require(scene.count(log) > 0 && scene.count(leaves[i]) > 0 && scene.count("oak_log") == 0,
                            "Modern tree mapped to incorrect feature: " + types[i]);
                }
            }
            var marker = new NamespacedKey(plugin, "tree_buffer_probe");
            boolean verifiedHeart = false;
            for (int seed = 0; seed < 32 && !verifiedHeart; seed++) {
                try (var scene = new Scene(world)) {
                    var heart = new AtomicReference<Location>();
                    boolean result = world.generateTree(scene.location, new Random(seed), TreeType.PALE_OAK_CREAKING,
                            (Consumer<BlockState>) state -> {
                                if (state.getType() == Material.CREAKING_HEART) {
                                    require(state instanceof TileState, "Heart snapshot has no tile data");
                                    require(state.getBlock().getType().isAir(), "Heart appeared before callback");
                                    ((TileState) state).getPersistentDataContainer().set(marker, PersistentDataType.STRING, "kept");
                                    heart.set(state.getLocation());
                                }
                            });
                    // The native decorator also needs a suitable trunk position.
                    if (!result || heart.get() == null) continue;
                    var placed = world.getBlockState(heart.get());
                    require(placed instanceof TileState && "kept".equals(((TileState) placed).getPersistentDataContainer().get(marker, PersistentDataType.STRING)),
                            "Generated tile snapshot lost callback persistent data");
                    verifiedHeart = true;
                }
            }
            require(verifiedHeart, "Creaking trees produced no heart snapshot across 32 fixed seeds");
        });
        check.accept("tree-null-callbacks-exceptions-and-blocked-origin", () -> {
            for (int mode = 0; mode < 2; mode++) {
                try (var scene = new Scene(world)) {
                    boolean result = mode == 0
                            ? world.generateTree(scene.location, new Random(1044), TreeType.TREE, (Consumer<BlockState>) null)
                            : world.generateTree(scene.location, new Random(1044), TreeType.TREE, (Predicate<BlockState>) null);
                    require(result && scene.count("oak_log") > 0, "Null callback prevented ordinary tree generation");
                }
            }
            try (var scene = new Scene(world)) {
                var marker = new IllegalStateException("tree callback marker");
                boolean thrown = false;
                try {
                    world.generateTree(scene.location, new Random(1044), TreeType.TREE, (Consumer<BlockState>) state -> { throw marker; });
                } catch (RuntimeException failure) {
                    require(failure == marker, "Tree callback exception was replaced");
                    thrown = true;
                }
                require(thrown && scene.blocks().isEmpty(), "First callback exception left generated blocks");
            }
            try (var scene = new Scene(world)) {
                scene.location.getBlock().setType(Material.BEDROCK, false);
                var before = scene.blocks();
                var calls = new AtomicInteger();
                boolean result = world.generateTree(scene.location, new Random(1044), TreeType.TREE,
                        (Consumer<BlockState>) state -> calls.incrementAndGet());
                require(!result && calls.get() == 0 && scene.blocks().equals(before), "Blocked trunk origin reported a successful tree");
            }
        });
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Scene implements AutoCloseable {
        private static final int RADIUS = 10, HEIGHT = 32;
        final World world;
        final int x, y, z;
        final Location location;

        Scene(World world) {
            this.world = world;
            var spawn = world.getSpawnLocation();
            x = ((spawn.getBlockX() >> 4) << 4) + 8;
            z = ((spawn.getBlockZ() >> 4) << 4) + 8;
            y = world.getMaxHeight() - 64;
            location = new Location(world, x, y, z);
            // This fixture uses a fresh flat world. Guard its air volume before
            // touching anything, so cleanup never removes an existing structure.
            for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++)
                require(world.getHighestBlockYAt(x + dx, z + dz, HeightMap.WORLD_SURFACE) < y - 3, "Tree scene overlaps existing terrain");
            soil(Material.DIRT);
        }

        void soil(Material material) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++)
                world.getBlockAt(x + dx, y - 1, z + dz).setType(material, false);
        }

        Map<String, String> blocks() {
            Map<String, String> values = new LinkedHashMap<>();
            for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++)
                for (int dy = 0; dy < HEIGHT; dy++) {
                    var block = world.getBlockAt(x + dx, y + dy, z + dz);
                    if (!block.getType().isAir()) values.put(dx + "," + dy + "," + dz, block.getBlockData().getAsString());
                }
            return values;
        }

        long count(String path) {
            return blocks().values().stream().filter(value -> value.equals("minecraft:" + path) || value.startsWith("minecraft:" + path + "[")).count();
        }

        @Override public void close() {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++)
                for (int dy = -2; dy < HEIGHT; dy++) {
                    var block = world.getBlockAt(x + dx, y + dy, z + dz);
                    if (!block.getType().isAir()) block.setType(Material.AIR, false);
                }
        }
    }
}
