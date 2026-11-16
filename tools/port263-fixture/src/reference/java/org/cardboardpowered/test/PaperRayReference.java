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
                    WorldSearchProbes.verify(world, this, check);
                    StructureTemplateProbes.verify(world, this, check);
                    StructureTemplateProbes.verifySpawnEvents(world, this, check);
                    RaidPdcProbes.verify(world, check);
                    WorldSettingsProbes.verify(world, this, check);
                    WorldEffectsProbes.verify(world, check);
                    PlayerStateProbes.verify(world, check);
                    ServerAliasProbes.verify(check);
                    BrigadierArgumentProbes.verify(world, check);
                    DialogCallbackProbes.verify(check);
                    BrigadierArgumentProbes.verifyMessages(world, this, check,
                            () -> DialogCallbackProbes.verifyTick(this, check, () -> finish(world, x, z, results)));
                } catch (Exception failure) {
                    world.removePluginChunkTicket(x, z, this);
                    Bukkit.shutdown();
                    throw new IllegalStateException("Unable to finish reference probes", failure);
                }
            }, 20);
        }, 20);
    }
    private void finish(org.bukkit.World world, int x, int z, java.util.Map<String, String> results) {
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(getDataFolder().toPath().resolve("results.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(results));
            getLogger().info("PAPER_RAY_REFERENCE_RESULTS " + results);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to write reference results", failure);
        } finally {
            world.removePluginChunkTicket(x, z, this);
            Bukkit.shutdown();
        }
    }

}

