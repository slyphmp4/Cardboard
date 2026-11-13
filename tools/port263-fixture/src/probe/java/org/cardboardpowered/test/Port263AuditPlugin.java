package org.cardboardpowered.test;

import com.google.gson.GsonBuilder;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.SignText;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.java.JavaPlugin;

/** Public API probes; the mod-specific cases use reflection so Paper can run the same JAR. */
public final class Port263AuditPlugin extends JavaPlugin implements org.bukkit.event.Listener {
    private final Map<String, String> results = new LinkedHashMap<>();
    private boolean running;
    private World anchorWorld;
    private int anchorX, anchorZ;
    private int reloadEvents;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @org.bukkit.event.EventHandler
    public void onResourcesReloaded(io.papermc.paper.event.server.ServerResourcesReloadedEvent event) {
        if (event.getCause() == io.papermc.paper.event.server.ServerResourcesReloadedEvent.Cause.PLUGIN) reloadEvents++;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equals("run")) {
            if (running) throw new IllegalStateException("Audit is already running");
            running = true;
            results.clear();
            Bukkit.getScheduler().runTask(this, this::verify);
        } else if (args.length == 1 && (args[0].equals("persist") || args[0].equals("verify-persist"))) {
            if (running) throw new IllegalStateException("Audit is already running");
            running = true;
            results.clear();
            boolean verify = args[0].equals("verify-persist");
            Bukkit.getScheduler().runTask(this, () -> atLoadedSpawn(() -> {
                check(verify ? "persisted-mod-content" : "prepare-persisted-mod-content", () -> persistence(verify));
                finish();
            }));
        }
        sender.sendMessage("PORT263_AUDIT running=" + running + " results=" + results);
        return true;
    }

    private void persistence(boolean verify) throws Exception {
        var world = Bukkit.getWorlds().getFirst();
        var key = new NamespacedKey("cardboard_port263_fixture", "probe");
        var location = world.getSpawnLocation().add(5, 2, 5);
        var block = location.getBlock();
        getDataFolder().mkdirs();
        var state = getDataFolder().toPath().resolve("persist.json");
        if (!verify) {
            var previous = block.getBlockData().getAsString();
            block.setBlockData(Registry.BLOCK.get(key).createBlockData(), false);
            var entity = world.spawnEntity(location.clone().add(0, 2, 0), Registry.ENTITY_TYPE.get(key), false);
            entity.setPersistent(true);
            ((org.bukkit.entity.LivingEntity) entity).setRemoveWhenFarAway(false);
            var fixture = Class.forName("org.cardboardpowered.test.Port263Fixture");
            var craft = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
            var stack = (ItemStack) craft.getMethod("asBukkitCopy", Class.forName("net.minecraft.world.item.ItemStack"))
                    .invoke(null, fixture.getMethod("createMarkedStack").invoke(null));
            Files.write(getDataFolder().toPath().resolve("persist-item.nbt"), stack.serializeAsBytes());
            Files.writeString(state, new GsonBuilder().create().toJson(Map.of("uuid", entity.getUniqueId().toString(), "previous", previous)));
            world.save();
        } else {
            var values = new com.google.gson.Gson().fromJson(Files.readString(state), com.google.gson.JsonObject.class);
            require(block.getType().getKey().equals(key), "Saved mod block did not survive restart");
            var entity = Bukkit.getEntity(java.util.UUID.fromString(values.get("uuid").getAsString()));
            require(entity != null && entity.isValid() && entity.getType().getKey().equals(key), "Saved mod entity did not survive restart");
            var item = ItemStack.deserializeBytes(Files.readAllBytes(getDataFolder().toPath().resolve("persist-item.nbt")));
            var craft = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
            var restored = craft.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
            require(Class.forName("org.cardboardpowered.test.Port263Fixture")
                    .getMethod("readMarker", Class.forName("net.minecraft.world.item.ItemStack")).invoke(null, restored).equals(1729),
                    "Saved custom component did not survive restart");
            entity.remove();
            block.setBlockData(Bukkit.createBlockData(values.get("previous").getAsString()), false);
            world.save();
        }
    }

    private void verify() {
        World world = Bukkit.getWorlds().getFirst();
        atLoadedSpawn(() -> verifyLoaded(world));
    }

    private void atLoadedSpawn(Runnable action) {
        World world = Bukkit.getWorlds().getFirst();
        anchorWorld = world;
        var anchor = world.getSpawnLocation();
        anchorX = anchor.getBlockX() >> 4;
        anchorZ = anchor.getBlockZ() >> 4;
        world.addPluginChunkTicket(anchorX, anchorZ, this);
        // Entity tracking begins on a later tick after terrain reaches FULL.
        // Paper 249 also returns an initially invalid entity in an unloaded chunk.
        Bukkit.getScheduler().runTaskLater(this, action, 20);
    }

    private void verifyLoaded(World world) {
        check("absent-chunk-query", () -> {
            require(!world.isChunkGenerated(1_000_001, 1_000_001), "Absent chunk reported generated");
            require(!world.loadChunk(1_000_001, 1_000_001, false), "Missing chunk loaded without generation");
            require(!world.isChunkLoaded(1_000_001, 1_000_001), "Failed load left a loaded chunk");
        });
        check("sign-builder-value-isolation", () -> {
            var builder = SignText.signText().line(0, Component.text("first"));
            var first = builder.build();
            var item = new ItemStack(Material.OAK_SIGN);
            item.setData(DataComponentTypes.SIGN_TEXT_FRONT, first);
            builder.line(0, Component.text("second"));
            require(first.lines().getFirst().equals(Component.text("first")), "Reusing builder mutated its built value");
            require(item.getData(DataComponentTypes.SIGN_TEXT_FRONT).lines().getFirst().equals(Component.text("first")),
                    "Reusing builder changed an existing item component");
        });
        check("sign-to-builder-isolation", () -> {
            var original = SignText.signText(List.of(Component.text("original")));
            var builder = original.toBuilder();
            var copy = builder.build();
            builder.line(0, Component.text("later"));
            require(copy.lines().getFirst().equals(Component.text("original")), "Builder alias changed a previous copy");
            require(original.lines().getFirst().equals(Component.text("original")), "Builder alias changed source");
        });
        check("fuel-and-compost-values", () -> {
            require(org.bukkit.inventory.ItemType.COAL.isFuel() && org.bukkit.inventory.ItemType.COAL.getBurnDuration() == 1600,
                    "Coal burn duration changed");
            require(org.bukkit.inventory.ItemType.BREAD.isCompostable()
                    && Math.abs(org.bukkit.inventory.ItemType.BREAD.getCompostChance() - 0.85F) < 0.001F,
                    "Bread compost probability changed");
        });
        check("public-api-provider-factories", () -> {
            require(io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay.hidden() != null, "Hidden display factory missing");
            require(io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay.reset() != null, "Default display factory missing");
            require(io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay.override(Component.text("override")) != null,
                    "Display text factory missing");
            require(io.papermc.paper.datacomponent.item.blocksattacks.DamageReduction.damageReduction().build() != null,
                    "Damage reduction factory missing");
            require(io.papermc.paper.datacomponent.item.blocksattacks.ItemDamageFunction.itemDamageFunction().build() != null,
                    "Item damage factory missing");
            require(io.papermc.paper.datacomponent.item.consumable.ConsumeEffect.clearAllStatusEffects() != null,
                    "Consume effect factory missing");
            require(io.papermc.paper.datacomponent.item.Consumable.consumable().build() != null, "Consumable factory missing");
            require(io.papermc.paper.block.bed.BedEnterProblem.NOT_SAFE.errorMessage() != null, "Bed problem factory missing");
            require(org.bukkit.entity.EntityType.PIG.requiredFeatures().contains(org.bukkit.FeatureFlag.VANILLA),
                    "Feature flag provider missing");
        });
        NamespacedKey modKey = new NamespacedKey("cardboard_port263_fixture", "probe");
        boolean fixtureInstalled;
        try {
            Class.forName("org.cardboardpowered.test.Port263Fixture");
            fixtureInstalled = true;
        } catch (ClassNotFoundException absent) {
            fixtureInstalled = false;
        }
        if (fixtureInstalled) {
            check("custom-entity-spawn-wrapper", () -> {
                var type = Registry.ENTITY_TYPE.get(modKey);
                require(type != null, "Registered mod entity is absent from Bukkit registry");
                require(Registry.ENTITY_TYPE.stream().anyMatch(value -> value == type)
                        && Registry.ENTITY_TYPE.keyStream().anyMatch(modKey::equals), "Mod entity registry iteration is inconsistent");
                var entity = world.spawnEntity(world.getSpawnLocation().add(2, 2, 2), type);
                try {
                    require(entity.getType() == type, "Custom entity wrapper type=" + entity.getType() + ", expected=" + type);
                    require(entity.isValid(), "Spawned custom entity is not valid; dead=" + entity.isDead());
                    require(Bukkit.getEntity(entity.getUniqueId()) == entity, "Custom entity Bukkit lookup is inconsistent");
                } finally {
                    entity.remove();
                }
            });
            check("custom-block-item-registry", () -> {
                var block = Registry.BLOCK.get(modKey);
                var item = Registry.ITEM.get(modKey);
                require(block != null && item != null, "Mod block or item is absent from Bukkit registry");
                var stack = item.createItemStack();
                require(stack.getType().getKey().equals(modKey), "Mod item became a vanilla material");
                require(Registry.MATERIAL.get(modKey) == stack.getType()
                        && stack.getType().asItemType() == item && stack.getType().asBlockType() == block,
                        "Mod Material and ItemType/BlockType lookup disagree");
                var target = world.getSpawnLocation().add(3, 2, 3).getBlock();
                var previous = target.getBlockData();
                try {
                    target.setBlockData(block.createBlockData(), false);
                    require(target.getBlockData().getMaterial().getKey().equals(modKey), "Mod block lost its material");
                } finally {
                    target.setBlockData(previous, false);
                }
            });
            check("custom-component-meta-and-bytes", () -> {
                var fixture = Class.forName("org.cardboardpowered.test.Port263Fixture");
                var nativeStackClass = Class.forName("net.minecraft.world.item.ItemStack");
                var craft = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
                var nativeStack = fixture.getMethod("createMarkedStack").invoke(null);
                var stack = (ItemStack) craft.getMethod("asBukkitCopy", nativeStackClass).invoke(null, nativeStack);
                stack.editMeta(meta -> meta.displayName(Component.text("API edit")));
                stack = ItemStack.deserializeBytes(stack.serializeAsBytes());
                var restored = craft.getMethod("asNMSCopy", ItemStack.class).invoke(null, stack);
                require(fixture.getMethod("readMarker", nativeStackClass).invoke(null, restored).equals(1729),
                        "Unknown mod component was lost during metadata/byte round-trip");
            });
        } else {
            results.put("custom-mod", "SKIP: independent Fabric fixture not installed");
        }
        var original = Bukkit.getRecipe(NamespacedKey.minecraft("oak_planks"));
        var customKey = new NamespacedKey(this, "reset_probe");
        check("recipe-reset-start", () -> {
            require(original != null, "Vanilla reference recipe is missing");
            Bukkit.removeRecipe(NamespacedKey.minecraft("oak_planks"));
            var recipe = new ShapelessRecipe(customKey, new ItemStack(Material.DIAMOND)).addIngredient(Material.DIRT);
            require(Bukkit.addRecipe(recipe), "Could not add probe recipe");
            reloadEvents = 0;
            Bukkit.resetRecipes();
        });
        waitForReset(original, customKey, 0);
    }

    private void waitForReset(org.bukkit.inventory.Recipe original, NamespacedKey customKey, int attempt) {
        if (Bukkit.getRecipe(NamespacedKey.minecraft("oak_planks")) == null || Bukkit.getRecipe(customKey) != null || reloadEvents == 0) {
            if (attempt < 100) {
                Bukkit.getScheduler().runTaskLater(this, () -> waitForReset(original, customKey, attempt + 1), 2);
                return;
            }
        }
        check("recipe-reset-restores-defaults", () -> {
            require(Bukkit.getRecipe(NamespacedKey.minecraft("oak_planks")) != null, "resetRecipes did not restore vanilla recipe");
            require(Bukkit.getRecipe(customKey) == null, "resetRecipes retained the plugin recipe");
            require(reloadEvents == 1, "Expected one PLUGIN resource reload event, got " + reloadEvents);
        });
        // Leave the disposable server useful even when testing the old defect.
        if (original != null && Bukkit.getRecipe(NamespacedKey.minecraft("oak_planks")) == null) Bukkit.addRecipe(original);
        Bukkit.removeRecipe(customKey);
        CompletableFuture.supplyAsync(() -> worldQuery()).whenComplete((value, failure) ->
                Bukkit.getScheduler().runTask(this, () -> {
            check("async-legacy-chunk-query", () -> {
                if (failure != null) throw new AssertionError("Async query failed", failure);
                require(!value, "Async query reported missing terrain as generated");
            });
            finish();
        }));
    }

    private boolean worldQuery() {
        return Bukkit.getWorlds().getFirst().isChunkGenerated(1_000_001, 1_000_001);
    }

    private void check(String name, Checked task) {
        try {
            task.run();
            results.put(name, "PASS");
            getLogger().info("PORT263_AUDIT_PASS " + name);
        } catch (Throwable failure) {
            results.put(name, "FAIL: " + failure);
            getLogger().log(java.util.logging.Level.SEVERE, "PORT263_AUDIT_FAIL " + name, failure);
        }
    }

    private void finish() {
        running = false;
        if (anchorWorld != null) {
            anchorWorld.removePluginChunkTicket(anchorX, anchorZ, this);
            anchorWorld = null;
        }
        try {
            getDataFolder().mkdirs();
            Files.writeString(getDataFolder().toPath().resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(results));
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
        getLogger().info("PORT263_AUDIT_FINISHED " + results);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    interface Checked { void run() throws Exception; }
}
