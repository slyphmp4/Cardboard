package org.cardboardpowered.ci;

import io.papermc.paper.world.chunk.ChunkGenerationState;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Cushion;
import org.bukkit.entity.boat.PoplarBoat;
import org.bukkit.entity.boat.PoplarChestBoat;
import org.bukkit.inventory.BrewingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.plugin.java.JavaPlugin;

/** Runs against a disposable server; failures must fail the surrounding smoke. */
public final class Port263Probe extends JavaPlugin {
    @Override
    public void onEnable() {
        getServer().getScheduler().runTask(this, this::verify);
    }

    private void verify() {
        try {
            require(Bukkit.getMinecraftVersion().equals("26.3"), "Wrong Minecraft version");
            require(Bukkit.getBukkitVersion().startsWith("26.3"), "Wrong API version");
            World world = Bukkit.getWorlds().getFirst();
            var location = world.getSpawnLocation().add(0, 3, 0);
            Cushion cushion = world.spawn(location, Cushion.class);
            try {
                cushion.setColor(DyeColor.PURPLE);
                require(cushion.getColor() == DyeColor.PURPLE, "Cushion colour did not round-trip");
            } finally {
                cushion.remove();
            }
            PoplarBoat boat = world.spawn(location, PoplarBoat.class);
            try {
                require(boat.getBoatMaterial() == Material.POPLAR_BOAT, "Wrong poplar boat material");
            } finally {
                boat.remove();
            }
            PoplarChestBoat chestBoat = world.spawn(location, PoplarChestBoat.class);
            try {
                require(chestBoat.getBoatMaterial() == Material.POPLAR_CHEST_BOAT, "Wrong poplar chest boat material");
            } finally {
                chestBoat.remove();
            }

            NamespacedKey key = new NamespacedKey(this, "brewing");
            var recipe = new BrewingRecipe(key, new ItemStack(Material.POTION),
                    new RecipeChoice.MaterialChoice(Material.GLASS_BOTTLE),
                    new RecipeChoice.MaterialChoice(Material.DIAMOND));
            try {
                require(Bukkit.addRecipes(List.of(recipe)), "Brewing recipe was not added");
                require(Bukkit.hasRecipe(key), "Brewing recipe is missing");
                require(Bukkit.getRecipe(key) instanceof BrewingRecipe, "Brewing recipe conversion failed");
                require(Bukkit.removeRecipes(List.of(key)), "Brewing recipe was not removed");
                require(!Bukkit.hasRecipe(key), "Removed brewing recipe remains visible");
            } finally {
                Bukkit.removeRecipe(key);
            }

            // Both the legacy and new 26.3 overloads must reach packet construction.
            world.spawnParticle(Particle.FLAME, location, 1);
            world.spawnParticle(Particle.FLAME, List.of(), null, location.x(), location.y(), location.z(),
                    1, 0, 0, 0, 0, 0, 0, null, false, Particle.RandomizationType.ALTERNATIVE);
            location.getChunk();
            require(world.getChunkGenerationState(location.getBlockX() >> 4, location.getBlockZ() >> 4)
                    == ChunkGenerationState.GENERATED, "Loaded chunk was not fully generated");
            int distantX = 1_000_000;
            int distantZ = 1_000_000;
            AtomicBoolean completed = new AtomicBoolean();
            world.getChunkGenerationStateAsync(distantX, distantZ).whenComplete((state, error) ->
                    getServer().getScheduler().runTask(this, () -> {
                try {
                    if (error != null) throw new AssertionError("Async chunk query failed", error);
                    require(state == ChunkGenerationState.NOT_GENERATED, "Distant chunk unexpectedly exists");
                    require(!world.isChunkLoaded(distantX, distantZ), "Query loaded a chunk");
                    getLogger().info("PORT_263_PROBE_OK");
                } catch (Throwable failure) {
                    fail(failure);
                } finally {
                    completed.set(true);
                }
            }));
            getServer().getScheduler().runTaskLater(this, () -> {
                if (!completed.get()) fail(new AssertionError("Async chunk query did not complete"));
            }, 200L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void fail(Throwable failure) {
        getLogger().log(java.util.logging.Level.SEVERE, "PORT_263_PROBE_FAIL", failure);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
