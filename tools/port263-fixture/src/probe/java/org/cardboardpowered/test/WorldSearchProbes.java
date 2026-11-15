package org.cardboardpowered.test;

import io.papermc.paper.entity.poi.PoiSearchResult;
import io.papermc.paper.entity.poi.PoiType;
import io.papermc.paper.entity.poi.PoiTypes;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.plugin.Plugin;

/** Shared public search contracts, to be run on fresh flat worlds. */
final class WorldSearchProbes {
    static void verify(World world, Plugin plugin, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("legacy-biome-search-overloads", () -> {
            var origin = world.getSpawnLocation().add(-.45, .37, -.65);
            var before = origin.clone();
            var modern = world.locateNearestBiome(origin, 64, 8, 8, Biome.PLAINS);
            require(modern != null, "Flat-world modern plains search returned null");
            require(same(world.locateNearestBiome(origin, Biome.PLAINS, 64), modern.getLocation()), "Legacy default biome search differs from its public default");
            var stepped = world.locateNearestBiome(origin, 64, 4, 4, Biome.PLAINS);
            require(stepped != null && same(world.locateNearestBiome(origin, Biome.PLAINS, 64, 4), stepped.getLocation()),
                    "Legacy step was not applied to both search intervals");
            require(world.locateNearestBiome(origin, Biome.THE_VOID, 16) == null
                    && world.locateNearestBiome(origin, Biome.THE_VOID, 16, 4) == null, "Missing flat-world biome did not return null");
            require(origin.equals(before), "Biome search mutated its caller location");
        });
        check.accept("poi-nearest-type-radius-and-occupancy", () -> {
            try (var scene = new Scene(world)) {
                var before = scene.origin.clone();
                require(PoiTypes.FARMER.hasOccupants() && PoiTypes.FARMER.is(Material.COMPOSTER.createBlockData()), "Farmer POI registry mapping is incorrect");
                require(same(world.locateNearestPoi(scene.origin, PoiTypes.FARMER, 6), scene.point(1)), "Default nearest farmer search failed");
                require(same(world.locateNearestPoi(scene.origin, PoiTypes.FARMER, 6, PoiType.Occupancy.ANY), scene.point(1)), "Nearest typed POI was not selected");
                require(same(world.locateNearestPoi(scene.origin, PoiTypes.FARMER, 6, PoiType.Occupancy.HAS_SPACE), scene.point(1)), "Unclaimed workstation had no space");
                require(world.locateNearestPoi(scene.origin, PoiTypes.FARMER, 6, PoiType.Occupancy.IS_OCCUPIED) == null, "Unclaimed workstation appeared occupied");
                require(world.locateNearestPoi(scene.origin, PoiTypes.ARMORER, 2, PoiType.Occupancy.ANY) == null, "POI search exceeded its radius");
                require(same(world.locateNearestPoi(scene.origin, PoiTypes.ARMORER, 6, PoiType.Occupancy.ANY), scene.point(3)), "POI search ignored the requested type");
                require(scene.origin.equals(before), "POI search mutated its caller location");
            }
        });
        check.accept("poi-list-filter-and-snapshot-isolation", () -> {
            try (var scene = new Scene(world)) {
                Predicate<PoiType> farmers = type -> type.getKey().equals(PoiTypes.FARMER.getKey());
                var results = world.locateAllPoiInRange(scene.origin, farmers, 6, PoiType.Occupancy.ANY);
                require(results != null && results.size() == 2 && positions(results).equals(Set.of(key(scene.point(1)), key(scene.point(5)))), "Filtered POI list has wrong positions");
                require(results.stream().allMatch(r -> r.poiType().getKey().equals(PoiTypes.FARMER.getKey())), "POI result has the wrong type");
                var first = results.getFirst();
                var expected = first.location();
                first.location().add(900, 900, 900);
                require(same(first.location(), expected), "POI result exposes its mutable location");
                try { results.clear(); throw new AssertionError("POI result list is mutable"); }
                catch (UnsupportedOperationException expectedFailure) { }
                require(world.locateAllPoiInRange(scene.origin, farmers, 2).size() == 1, "POI range filtering is incorrect");
                require(world.locateAllPoiInRange(scene.origin, farmers, 6, PoiType.Occupancy.HAS_SPACE).size() == 2, "POI occupancy filter lost available workstations");
                require(world.locateAllPoiInRange(scene.origin, farmers, 6, PoiType.Occupancy.IS_OCCUPIED).isEmpty(), "POI list ignored occupied filtering");
                require(world.locateAllPoiInRange(scene.origin, type -> false, 6, PoiType.Occupancy.ANY).isEmpty(), "False POI predicate did not return an empty list");
            }
        });
        check.accept("poi-search-validation-and-predicate-errors", () -> {
            try (var scene = new Scene(world)) {
                var other = plugin.getServer().getWorlds().stream().filter(w -> !w.equals(world)).findFirst().orElseThrow();
                var foreign = scene.origin.clone(); foreign.setWorld(other);
                invalid(() -> world.locateNearestPoi(null, PoiTypes.FARMER, 6, PoiType.Occupancy.ANY));
                invalid(() -> world.locateNearestPoi(foreign, PoiTypes.FARMER, 6, PoiType.Occupancy.ANY));
                invalid(() -> world.locateNearestPoi(scene.origin, null, 6, PoiType.Occupancy.ANY));
                invalid(() -> world.locateNearestPoi(scene.origin, PoiTypes.FARMER, 0, PoiType.Occupancy.ANY));
                invalid(() -> world.locateNearestPoi(scene.origin, PoiTypes.FARMER, -1, PoiType.Occupancy.ANY));
                invalid(() -> world.locateNearestPoi(scene.origin, PoiTypes.FARMER, 6, null));
                invalid(() -> world.locateAllPoiInRange(null, type -> true, 6, PoiType.Occupancy.ANY));
                invalid(() -> world.locateAllPoiInRange(foreign, type -> true, 6, PoiType.Occupancy.ANY));
                invalid(() -> world.locateAllPoiInRange(scene.origin, null, 6, PoiType.Occupancy.ANY));
                invalid(() -> world.locateAllPoiInRange(scene.origin, type -> true, 0, PoiType.Occupancy.ANY));
                invalid(() -> world.locateAllPoiInRange(scene.origin, type -> true, -1, PoiType.Occupancy.ANY));
                invalid(() -> world.locateAllPoiInRange(scene.origin, type -> true, 6, null));
                var marker = new IllegalStateException("POI predicate marker");
                try {
                    world.locateAllPoiInRange(scene.origin, type -> { throw marker; }, 6, PoiType.Occupancy.ANY);
                    throw new AssertionError("POI predicate exception was swallowed");
                } catch (RuntimeException failure) { require(failure == marker, "POI predicate exception was replaced"); }
                require(same(world.locateNearestPoi(scene.origin, PoiTypes.FARMER, 6), scene.point(1)), "Failed predicate damaged the native POI index");
            }
        });
    }

    private static boolean same(Location first, Location second) {
        return first != null && second != null && first.getWorld().equals(second.getWorld())
                && first.getX() == second.getX() && first.getY() == second.getY() && first.getZ() == second.getZ();
    }
    private static String key(Location p) { return p.getBlockX() + "," + p.getBlockY() + "," + p.getBlockZ(); }
    private static Set<String> positions(List<PoiSearchResult> results) {
        return results.stream().map(r -> key(r.location())).collect(Collectors.toSet());
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void invalid(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static final class Scene implements AutoCloseable {
        final World world;
        final int x, y, z;
        final Location origin;
        Scene(World world) {
            this.world = world;
            var spawn = world.getSpawnLocation();
            x = ((spawn.getBlockX() >> 4) << 4) + 8;
            y = world.getMaxHeight() - 96;
            z = ((spawn.getBlockZ() >> 4) << 4) + 8;
            origin = new Location(world, x + .25, y + .35, z + .45);
            for (int dx : new int[]{1, 3, 5})
                require(world.getHighestBlockYAt(x + dx, z, HeightMap.WORLD_SURFACE) < y - 2, "POI scene overlaps terrain");
            point(1).getBlock().setType(Material.COMPOSTER, true);
            point(3).getBlock().setType(Material.BLAST_FURNACE, true);
            point(5).getBlock().setType(Material.COMPOSTER, true);
        }
        Location point(int dx) { return new Location(world, x + dx, y, z); }
        @Override public void close() {
            for (int dx : new int[]{1, 3, 5}) point(dx).getBlock().setType(Material.AIR, true);
        }
    }
}
