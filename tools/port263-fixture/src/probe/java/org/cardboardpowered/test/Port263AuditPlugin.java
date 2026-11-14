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
            check("item-only-namespace-isolation", () -> {
                var key = new NamespacedKey("minecraft_fixture", "dirt");
                var type = Registry.ITEM.get(key);
                require(type != null, "Mod item-only type is missing");
                var stack = type.createItemStack();
                var material = stack.getType();
                require(material != Material.DIRT && material.getKey().equals(key), "Mod dirt aliases vanilla dirt");
                require(material.asItemType() == type && material.asBlockType() == null && material.isItem() && !material.isBlock(),
                        "Item-only Material was treated as a block");
                require(material.getMaxStackSize() == 16 && material.asItemType().getMaxStackSize() == 16,
                        "Mod stack-size properties were replaced with vanilla defaults");
                require(material.getMaxDurability() == 0, "Item without MAX_DAMAGE must have zero durability");
                var modded = (Map<?, ?>) Class.forName("org.cardboardpowered.impl.util.CardboardMagicNumbers")
                        .getMethod("getModdedMaterials").invoke(null);
                require(modded.get("MINECRAFT_FIXTURE_DIRT") == material, "Mod namespace was excluded from material enumeration");
                require(ItemStack.deserializeBytes(stack.serializeAsBytes()).getType() == material,
                        "Item-only namespace changed in byte round-trip");
            });
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
        if (fixtureInstalled) {
            verifyWorldQueries(world);
            verifyDragon(this::verifyRecipes);
        } else {
            verifyRecipes();
        }
    }

    private Object nativeWorld(World world) throws Exception {
        return world.getClass().getMethod("getHandle").invoke(world);
    }

    private void verifyWorldQueries(World world) {
        check("structure-locate-overloads", () -> {
            var fixture = Class.forName("org.cardboardpowered.test.Port263Fixture");
            var level = Class.forName("net.minecraft.server.level.ServerLevel");
            var origin = world.getSpawnLocation();
            var expected = (int[]) fixture.getMethod("nearestVillage", level, int.class, int.class, int.class)
                    .invoke(null, nativeWorld(world), origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
            var result = world.locateNearestStructure(origin, org.bukkit.generator.structure.Structure.VILLAGE_PLAINS, 32, false);
            require(result != null, "Structure API did not locate native village");
            require(result.getStructure().equals(org.bukkit.generator.structure.Structure.VILLAGE_PLAINS), "Wrong structure variant");
            require(result.getLocation().getBlockX() == expected[0] && result.getLocation().getBlockZ() == expected[2], "Native and Bukkit locations disagree");
            var byType = world.locateNearestStructure(origin, org.bukkit.generator.structure.StructureType.JIGSAW, 32, false);
            require(byType != null && byType.getLocation().equals(result.getLocation()), "Structure type overload disagrees");
            require(result.getLocation().equals(world.locateNearestStructure(origin, org.bukkit.StructureType.VILLAGE, 32, false)), "Legacy village mapping disagrees");
            require(world.locateNearestStructure(origin, org.bukkit.StructureType.MINESHAFT, 0, false) == null, "Flat fixture unexpectedly has mineshafts");
            var changed = result.getLocation(); changed.setX(0);
            require(result.getLocation().getBlockX() == expected[0], "Search result location aliases mutable caller state");
            expectIllegal(() -> world.locateNearestStructure(origin, org.bukkit.generator.structure.Structure.VILLAGE_PLAINS, -1, false));
        });
        check("structure-presence-key", () -> {
            var origin = world.getSpawnLocation();
            var fixture = Class.forName("org.cardboardpowered.test.Port263Fixture");
            var expected = (boolean) fixture.getMethod("hasVillageAt", Class.forName("net.minecraft.server.level.ServerLevel"), int.class, int.class, int.class)
                    .invoke(null, nativeWorld(world), origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
            require(world.hasStructureAt(origin, org.bukkit.generator.structure.Structure.VILLAGE_PLAINS) == expected, "Structure presence differs from native manager");
        });
        check("raid-enumeration-id-radius", () -> {
            var fixture = Class.forName("org.cardboardpowered.test.Port263Fixture");
            var install = fixture.getMethod("installRaidProbe", Class.forName("net.minecraft.server.level.ServerLevel"), int.class, int.class, int.class, boolean.class);
            var origin = world.getSpawnLocation();
            try (var ignored = (AutoCloseable) install.invoke(null, nativeWorld(world), origin.getBlockX(), origin.getBlockY(), origin.getBlockZ(), false)) {
                var near = world.getRaid(941263);
                require(near != null && near.getId() == 941263, "Native raid ID lookup is missing");
                require(world.getRaids().contains(near), "Native raid is absent from enumeration");
                require(near.equals(world.locateNearestRaid(origin, 6)), "Nearest active raid differs");
                require(world.locateNearestRaid(origin, 3) == null, "Out-of-radius raid was returned");
            }
            try (var ignored = (AutoCloseable) install.invoke(null, nativeWorld(world), origin.getBlockX(), origin.getBlockY(), origin.getBlockZ(), true)) {
                var far = world.locateNearestRaid(origin, 60_000);
                require(far != null && far.getId() == 941264, "Squared raid radius overflowed");
            }
            require(world.getRaid(941263) == null && world.getRaid(941264) == null, "Raid fixture leaked state");
            expectIllegal(() -> world.locateNearestRaid(origin, -1));
        });
    }

    private void verifyDragon(Runnable continuation) {
        World end = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.THE_END).findFirst().orElseThrow();
        Object[] state;
        int[] position = null;
        Class<?> fixture;
        Class<?> level;
        Object nativeEnd;
        try {
            require(end.getEnderDragonBattle() != null, "End world exposes no native dragon battle");
            fixture = Class.forName("org.cardboardpowered.test.Port263Fixture");
            level = Class.forName("net.minecraft.server.level.ServerLevel");
            nativeEnd = nativeWorld(end);
            position = (int[]) fixture.getMethod("healingCrystalPosition", level).invoke(null, nativeEnd);
            end.addPluginChunkTicket(position[0] >> 4, position[2] >> 4, this);
            state = (Object[]) fixture.getMethod("prepareDragonProbe", level).invoke(null, nativeEnd);
        } catch (Throwable failure) {
            if (position != null) end.removePluginChunkTicket(position[0] >> 4, position[2] >> 4, this);
            check("dragon-battle-native-state", () -> { throw new AssertionError("Dragon probe setup", failure); });
            continuation.run();
            return;
        }
        final int[] anchor = position;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            check("dragon-battle-native-state", () -> {
                try {
                    var battle = end.getEnderDragonBattle();
                    require(battle.getBossBar() != null, "Native dragon boss bar is missing");
                    boolean killed = battle.hasBeenPreviouslyKilled();
                    battle.setPreviouslyKilled(!killed);
                    require(battle.hasBeenPreviouslyKilled() != killed, "Dragon history setter did nothing");
                    battle.setPreviouslyKilled(killed);
                    require(battle.getRespawnPhase() == org.bukkit.boss.DragonBattle.RespawnPhase.NONE, "Unexpected initial respawn phase");
                    require(!battle.setRespawnPhase(org.bukkit.boss.DragonBattle.RespawnPhase.START), "Phase setter succeeded without active respawn");
                    expectIllegal(() -> battle.setRespawnPhase(org.bukkit.boss.DragonBattle.RespawnPhase.NONE));
                    if (battle.getEndPortalLocation() == null) require(battle.generateEndPortal(false), "Portal generation did nothing");
                    require(battle.getEndPortalLocation() != null && !battle.generateEndPortal(false), "Portal getter/idempotence is broken");
                    var crystal = (org.bukkit.entity.EnderCrystal) Bukkit.getEntity((java.util.UUID) state[1]);
                    require(crystal != null && crystal.isValid(), "Probe crystal did not enter entity tracking");
                    var healers = battle.getHealingCrystals();
                    require(healers.contains(crystal), "Live spike crystal is absent from healing snapshot");
                    try { healers.clear(); throw new AssertionError("Mutable healing snapshot"); } catch (UnsupportedOperationException expected) { }
                    battle.resetCrystals();
                    require(crystal.getBeamTarget() == null && !crystal.isInvulnerable(), "Crystal reset did not reach native entity");
                    int gateways = battle.getGatewayCount();
                    require(gateways >= 0 && gateways <= 20, "Invalid gateway count");
                    require(battle.spawnNewGateway() == (gateways < 20), "Native gateway spawn result differs");
                    require(battle.getGatewayCount() == Math.min(20, gateways + 1), "Gateway count did not advance");
                    var customGateway = new org.bukkit.Location(end, 24, 80, 24);
                    customGateway.getBlock().setType(Material.AIR, false);
                    battle.spawnNewGateway(customGateway);
                    require(customGateway.getBlock().getType() == Material.END_GATEWAY, "Custom-position gateway was not placed");
                    fixture.getMethod("readyDragonRespawn", level).invoke(null, nativeEnd);
                    require(battle.initiateRespawn(List.of(crystal)), "Crystal respawn was not initiated");
                    var respawn = battle.getRespawnCrystals();
                    require(respawn.contains(crystal), "EntityReference crystal did not resolve");
                    try { respawn.clear(); throw new AssertionError("Mutable respawn snapshot"); } catch (UnsupportedOperationException expected) { }
                    crystal.remove();
                    require(!battle.getRespawnCrystals().contains(crystal), "Removed crystal remained in live lookup");
                    fixture.getMethod("readyDragonRespawn", level).invoke(null, nativeEnd);
                    require(battle.initiateRespawn((java.util.Collection<org.bukkit.entity.EnderCrystal>) null), "Empty-crystal respawn was not initiated");
                    require(battle.getRespawnCrystals().isEmpty(), "Empty-crystal respawn invented crystals");
                    require(battle.setRespawnPhase(org.bukkit.boss.DragonBattle.RespawnPhase.PREPARING_TO_SUMMON_PILLARS)
                            && battle.getRespawnPhase() == org.bukkit.boss.DragonBattle.RespawnPhase.PREPARING_TO_SUMMON_PILLARS, "Native respawn phase did not change");
                } finally {
                    try { fixture.getMethod("cleanupDragonProbe", level, Object[].class).invoke(null, nativeEnd, state); }
                    finally { end.removePluginChunkTicket(anchor[0] >> 4, anchor[2] >> 4, this); }
                }
            });
            continuation.run();
        }, 20);
    }

    private static void expectIllegal(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid API argument was accepted");
    }

    private void verifyRecipes() {
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
