package org.cardboardpowered.test;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Only the disposable reference runner opts into this shutdown-after-test plugin. */
public final class PaperRayReference extends JavaPlugin {
    @Override
    public void onEnable() {
        if (!Boolean.getBoolean("cardboard.paperRayReference")) {
            getLogger().warning("Reference runner flag is absent; disabling test plugin");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            var world = Bukkit.getWorlds().getFirst();
            var spawn = world.getSpawnLocation();
            int x = spawn.getBlockX() >> 4, z = spawn.getBlockZ() >> 4;
            world.addPluginChunkTicket(x, z, this);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                var results = new LinkedHashMap<String, String>();
                try {
                    java.util.function.BiConsumer<String, WorldRayTraceProbes.Checked> check = (name, action) -> {
                        try { action.run(); results.put(name, "PASS"); }
                        catch (Throwable failure) {
                            results.put(name, "FAIL: " + failure);
                            failure.printStackTrace();
                        }
                    };
                    WorldRayTraceProbes.verify(world, this, check);
                    ItemDropProbes.verify(world, this, check);
                    TreeProbes.verify(world, this, check);
                    ExplosionProbes.verify(world, this, check);
                    Files.createDirectories(getDataFolder().toPath());
                    Files.writeString(getDataFolder().toPath().resolve("results.json"),
                            new GsonBuilder().setPrettyPrinting().create().toJson(results));
                    getLogger().info("PAPER_RAY_REFERENCE_RESULTS " + results);
                } catch (Exception failure) {
                    throw new IllegalStateException("Unable to finish reference probes", failure);
                } finally {
                    world.removePluginChunkTicket(x, z, this);
                    Bukkit.shutdown();
                }
            }, 20);
        }, 20);
    }
}
