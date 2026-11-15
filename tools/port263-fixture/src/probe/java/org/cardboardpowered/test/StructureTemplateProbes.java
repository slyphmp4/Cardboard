package org.cardboardpowered.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.ArmorStand;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockTransformer;
import org.bukkit.util.BlockVector;
import org.bukkit.util.EntityTransformer;

/** Public template contracts, identical on the official Paper control. */
final class StructureTemplateProbes {
    private static final NamespacedKey DATA = new NamespacedKey("cardboard_template_probe", "data");
    private static final NamespacedKey TILE = new NamespacedKey("cardboard_template_probe", "tile");
    private static final String ENTITY_TAG = "cardboard-template-probe";

    static void verify(World world, Plugin plugin, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("template-manager-native-cache-and-snapshots", () -> {
            var manager = manager();
            var key = key();
            try {
                var first = manager.createStructure();
                require(first.getSize().equals(new BlockVector()) && first.getPaletteCount() == 0 && first.getEntityCount() == 0, "New template is not empty");
                first.getPersistentDataContainer().set(DATA, PersistentDataType.INTEGER, 17);
                require(manager.registerStructure(key, first) == null, "New key replaced a structure");
                var snapshot = manager.getStructures();
                require(snapshot.containsKey(key) && value(manager().getStructure(key)) == 17, "Manager getters do not share their cache");
                unsupported(snapshot::clear);
                var second = manager.createStructure();
                second.getPersistentDataContainer().set(DATA, PersistentDataType.INTEGER, 29);
                require(value(manager.registerStructure(key, second)) == 17, "Register did not return the previous native template");
                require(value(snapshot.get(key)) == 17 && value(manager.getStructure(key)) == 29, "Cache map is not a shallow snapshot");
                require(value(manager.unregisterStructure(key)) == 29 && manager.getStructure(key) == null, "Unregister did not remove/return the template");
                require(snapshot.containsKey(key), "Unregister changed an earlier map snapshot");
                require(manager.loadStructure(key, false) == null && !manager.getStructures().containsKey(key), "Missing load appeared as a structure");
            } finally { manager.deleteStructure(key); }
        });
        check.accept("template-fill-palettes-tiles-and-copy-isolation", () -> {
            var manager = manager();
            try (var scene = new Scene(world)) {
                var template = scene.capture(false);
                require(template.getSize().equals(new BlockVector(3, 2, 2)) && template.getPaletteCount() == 1, "Captured size/palettes are wrong");
                var palette = template.getPalettes().getFirst();
                require(palette.getBlockCount() == 11 && palette.getBlocks().size() == 11, "STRUCTURE_VOID was not excluded");
                var chest = palette.getBlocks().stream().filter(Chest.class::isInstance).map(Chest.class::cast).findFirst().orElseThrow();
                require(!chest.isPlaced() && chest.getPersistentDataContainer().get(TILE, PersistentDataType.INTEGER) == 73, "Tile snapshot lost PDC or is placed");
                chest.getPersistentDataContainer().set(TILE, PersistentDataType.INTEGER, 999);
                var again = (Chest) palette.getBlocks().stream().filter(Chest.class::isInstance).findFirst().orElseThrow();
                require(again.getPersistentDataContainer().get(TILE, PersistentDataType.INTEGER) == 73, "Palette exposes mutable template tile NBT");
                template.getPersistentDataContainer().set(DATA, PersistentDataType.INTEGER, 41);
                var copy = manager.copy(template);
                template.getPersistentDataContainer().set(DATA, PersistentDataType.INTEGER, 55);
                scene.at(0, 0, 0).getBlock().setType(Material.DIAMOND_BLOCK, false);
                template.fill(scene.origin, new BlockVector(1, 1, 1), false);
                require(value(copy) == 41 && copy.getSize().equals(new BlockVector(3, 2, 2)) && copy.getPalettes().getFirst().getBlockCount() == 11, "copy aliases the original template");
                var corners = manager.createStructure();
                corners.fill(scene.at(3, 2, 2), scene.origin, false);
                require(corners.getSize().equals(new BlockVector(3, 2, 2)), "Corner fill changed the public bounds convention");
            }
        });
        check.accept("template-stream-and-file-pdc-roundtrip", () -> {
            var manager = manager();
            Files.createDirectories(plugin.getDataFolder().toPath());
            var file = Files.createTempFile(plugin.getDataFolder().toPath(), "template-probe-", ".nbt");
            try (var scene = new Scene(world)) {
                var template = scene.capture(false);
                template.getPersistentDataContainer().set(DATA, PersistentDataType.INTEGER, 1729);
                var nested = template.getPersistentDataContainer().getAdapterContext().newPersistentDataContainer();
                nested.set(TILE, PersistentDataType.LONG_ARRAY, new long[]{-1, 42, Long.MAX_VALUE});
                template.getPersistentDataContainer().set(TILE, PersistentDataType.TAG_CONTAINER, nested);
                var bytes = new ByteArrayOutputStream();
                manager.saveStructure(bytes, template);
                var restored = manager.loadStructure(new ByteArrayInputStream(bytes.toByteArray()));
                require(value(restored) == 1729 && restored.getSize().equals(template.getSize()), "Stream roundtrip lost template data");
                var numbers = restored.getPersistentDataContainer().get(TILE, PersistentDataType.TAG_CONTAINER).get(TILE, PersistentDataType.LONG_ARRAY);
                require(java.util.Arrays.equals(numbers, new long[]{-1, 42, Long.MAX_VALUE}), "Nested PDC lost native NBT types");
                manager.saveStructure(file.toFile(), restored);
                require(value(manager.loadStructure(file.toFile())) == 1729, "File roundtrip lost PDC");
                restored.getPersistentDataContainer().remove(DATA);
                require(value(template) == 1729, "Decoded PDC aliases original");
            } finally { Files.deleteIfExists(file); }
        });
        check.accept("template-keyed-load-save-and-delete-contracts", () -> {
            var manager = manager();
            var key = key();
            try (var scene = new Scene(world)) {
                var template = scene.capture(false);
                template.getPersistentDataContainer().set(DATA, PersistentDataType.INTEGER, 97);
                manager.saveStructure(key, template);
                require(manager.getStructure(key) == null && Files.isRegularFile(manager.getStructureFile(key).toPath()), "Explicit save implicitly registered or did not write");
                require(value(manager.loadStructure(key, false)) == 97 && manager.getStructure(key) == null, "Non-registering load modified native cache");
                var loaded = manager.loadStructure(key);
                require(value(loaded) == 97 && value(manager.getStructure(key)) == 97, "Default load did not register");
                loaded.getPersistentDataContainer().set(DATA, PersistentDataType.INTEGER, 101);
                manager.saveStructure(key);
                manager.unregisterStructure(key);
                require(value(manager.loadStructure(key)) == 101, "Registered save did not use the live native template");
                manager.deleteStructure(key, false);
                require(value(manager.getStructure(key)) == 101 && !manager.getStructureFile(key).exists(), "delete(false) changed registration");
                manager.deleteStructure(key);
                require(manager.getStructure(key) == null, "Default delete did not unregister");
                var builtin = new NamespacedKey("minecraft", "village/plains/houses/plains_small_house_1");
                var previous = manager.getStructure(builtin);
                var resource = manager.loadStructure(builtin, false);
                require(resource != null && resource.getPaletteCount() > 0 && resource.getSize().getBlockX() > 0, "Resource-backed template could not be loaded");
                require((previous == null) == (manager.getStructure(builtin) == null), "Resource load(false) changed registration");
            } finally { manager.deleteStructure(key); }
        });
        check.accept("template-native-placement-rotation-mirror-and-integrity", () -> {
            manager();
            try (var scene = new Scene(world)) {
                var template = scene.capture(false);
                var target = scene.target;
                template.place(target, false, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17));
                require(target.getBlock().getType() == Material.STONE && target.clone().add(1, 0, 0).getBlock().getType() == Material.GOLD_BLOCK, "Plain native placement lost block layout");
                var chest = (Chest) target.clone().add(2, 0, 0).getBlock().getState();
                require(chest.getPersistentDataContainer().get(TILE, PersistentDataType.INTEGER) == 73, "Placement lost tile PDC");
                scene.clearTarget();
                template.place(world, target.toVector().toBlockVector(), false, StructureRotation.CLOCKWISE_90, Mirror.NONE, -1, 1, new Random(17));
                require(target.clone().add(0, 0, 1).getBlock().getType() == Material.GOLD_BLOCK, "Rotation did not change relative block positions");
                scene.clearTarget();
                template.place(target, false, StructureRotation.NONE, Mirror.FRONT_BACK, 0, 1, new Random(17));
                require(target.clone().add(-1, 0, 0).getBlock().getType() == Material.GOLD_BLOCK, "Mirror did not change relative block positions");
                scene.clearTarget();
                template.place(target, false, StructureRotation.NONE, Mirror.NONE, 0, 0, new Random(17));
                require(target.getBlock().getType().isAir() && target.clone().add(1, 0, 0).getBlock().getType().isAir(), "Integrity zero placed blocks");
                invalid(() -> template.place(target, false, StructureRotation.NONE, Mirror.NONE, 1, 1, new Random(17)));
            }
        });
        check.accept("template-captured-entities-and-placement-flags", () -> {
            manager();
            try (var scene = new Scene(world)) {
                var original = scene.entity();
                var template = scene.capture(true);
                require(template.getEntityCount() == 1 && template.getEntities().size() == 1, "Entity capture is missing");
                var dummy = template.getEntities().getFirst();
                require(!dummy.isValid() && dummy.getType() == original.getType(), "Captured entity was inserted into the world");
                original.remove();
                template.place(scene.target, false, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17));
                require(scene.entitiesAtTarget().isEmpty(), "includeEntities=false spawned captured entities");
                template.place(scene.target, true, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17));
                var spawned = scene.entitiesAtTarget();
                require(spawned.size() == 1 && spawned.getFirst().getScoreboardTags().contains(ENTITY_TAG), "Native placement did not restore captured entity data");
                require(scene.capture(false).getEntityCount() == 0, "Entity-free fill retained older entities");
            }
        });
        check.accept("template-block-and-entity-transformer-contracts", () -> {
            manager();
            try (var scene = new Scene(world)) {
                scene.entity();
                var template = scene.capture(true);
                var blocks = new AtomicInteger();
                var entities = new AtomicInteger();
                BlockTransformer transformer = (region, x, y, z, current, state) -> {
                    require(region.isInRegion(x, y, z) && region.getWorld().equals(world), "Transformer has wrong region");
                    require(region.getFluidData(x, y, z).getLevel() == 0, "Transformer region cannot read native fluid state");
                    // Native placement temporarily clears a tile with BARRIER before its transformer.
                    require(state.getWorld().getType() == (current instanceof Chest ? Material.BARRIER : Material.AIR), "Transformer world snapshot differs from native placement order");
                    require(state.getOriginal().getType() == current.getType(), "Transformer original is not the template state");
                    if (current instanceof Chest chest) {
                        require(chest.getPersistentDataContainer().get(TILE, PersistentDataType.INTEGER) == 73, "Transformer did not receive template tile NBT");
                        chest.getPersistentDataContainer().set(TILE, PersistentDataType.INTEGER, 131);
                    } else if (current.getType() == Material.GOLD_BLOCK) current.setType(Material.DIAMOND_BLOCK);
                    blocks.incrementAndGet();
                    return current;
                };
                EntityTransformer first = (region, x, y, z, entity, allowed) -> { require(allowed, "Initial entity flag is false"); entities.incrementAndGet(); return false; };
                EntityTransformer second = (region, x, y, z, entity, allowed) -> { require(!allowed, "Entity veto did not reach next transformer"); return allowed; };
                template.place(scene.target, true, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17), List.of(transformer), List.of(first, second));
                require(blocks.get() == 11 && entities.get() == 1 && scene.entitiesAtTarget().isEmpty(), "Transformers skipped blocks/entities or ignored veto");
                require(scene.target.clone().add(1, 0, 0).getBlock().getType() == Material.DIAMOND_BLOCK, "Block transformer edits were ignored");
                require(((Chest) scene.target.clone().add(2, 0, 0).getBlock().getState()).getPersistentDataContainer().get(TILE, PersistentDataType.INTEGER) == 131, "Transformer tile edits were overwritten");
                var original = (Chest) template.getPalettes().getFirst().getBlocks().stream().filter(Chest.class::isInstance).findFirst().orElseThrow();
                require(original.getPersistentDataContainer().get(TILE, PersistentDataType.INTEGER) == 73, "Placement mutated template tile NBT");
                scene.clearTarget();
                template.place(scene.target, true, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17), List.of(), List.of((region, x, y, z, entity, allowed) -> { entity.setCustomName("transformed"); return true; }));
                require(scene.entitiesAtTarget().size() == 1 && "transformed".equals(scene.entitiesAtTarget().getFirst().getCustomName()), "Entity transformer changes were ignored");
            }
        });
        check.accept("template-validation-and-transformer-failure-cleanup", () -> {
            var manager = manager();
            invalid(() -> manager.getStructure(null));
            invalid(() -> manager.createStructure().fill(null, new BlockVector(1, 1, 1), false));
            invalid(() -> manager.copy(null));
            invalid(() -> manager.getStructureFile(new NamespacedKey("cardboard_template_probe", "bad//path")));
            try (var scene = new Scene(world)) {
                var template = scene.capture(false);
                invalid(() -> template.fill(scene.origin, new BlockVector(0, 1, 1), false));
                invalid(() -> template.place(scene.target, false, StructureRotation.NONE, Mirror.NONE, 0, Float.NaN, new Random()));
                var marker = new IllegalStateException("Template transformer marker");
                try {
                    template.place(scene.target, false, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(), List.of((region, x, y, z, current, state) -> { throw marker; }), List.of());
                    throw new AssertionError("Transformer exception was swallowed");
                } catch (RuntimeException failure) { require(failure == marker, "Transformer exception was replaced"); }
                scene.clearTarget();
                template.place(scene.target, false, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random());
                require(scene.target.getBlock().getType() == Material.STONE, "Failed transformer leaked into next placement");
            }
        });
    }

    static void verifySpawnEvents(World world, Plugin plugin, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("template-entity-spawn-events-and-cancellation", () -> {
            manager();
            try (var scene = new Scene(world)) {
                scene.entity();
                var template = scene.capture(true);
                try (var events = new SpawnEvents(scene, plugin)) {
                    template.getEntities();
                    require(events.calls == 0, "Reading entity snapshots emitted CreatureSpawnEvent");
                    template.place(scene.target, false, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17));
                    require(events.calls == 0 && scene.entitiesAtTarget().isEmpty(), "Entity-free placement emitted spawn events");
                    template.place(scene.target, true, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17));
                    require(events.calls == 1 && scene.entitiesAtTarget().size() == 1, "Native template addition bypassed or duplicated CreatureSpawnEvent");
                    require(events.reason == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT, "Template spawn has a different reason than native addition");
                    scene.entitiesAtTarget().forEach(org.bukkit.entity.Entity::remove);
                    events.cancel = true;
                    template.place(scene.target, true, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17));
                    require(events.calls == 2 && scene.entitiesAtTarget().isEmpty(), "Cancelled template spawn still reached the world");
                    events.cancel = false;
                    events.order.clear();
                    template.place(scene.target, true, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17), List.of(), List.of((region, x, y, z, entity, allowed) -> {
                        events.order.add("transform");
                        entity.setCustomName("template-transformed-before-spawn");
                        return true;
                    }));
                    require(events.calls == 3 && events.order.equals(List.of("transform", "spawn")) && events.sawTransformedName,
                            "Spawn event ran before entity transformation or missed transformed data");
                    scene.entitiesAtTarget().forEach(org.bukkit.entity.Entity::remove);
                    template.place(scene.target, true, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17), List.of(), List.of((region, x, y, z, entity, allowed) -> false));
                    require(events.calls == 3 && scene.entitiesAtTarget().isEmpty(), "Transformer veto emitted a spawn event or spawned the entity");
                }
            }
        });
        check.accept("template-region-spawn-queue-and-failure", () -> {
            manager();
            try (var scene = new Scene(world)) {
                var template = scene.capture(false);
                try (var events = new SpawnEvents(scene, plugin)) {
                    var first = new java.util.concurrent.atomic.AtomicBoolean(true);
                    BlockTransformer enqueue = (region, x, y, z, current, state) -> {
                        if (first.getAndSet(false)) region.spawn(scene.target.clone().add(3.25, .25, .25), ArmorStand.class, entity -> {
                            entity.setGravity(false);
                            entity.addScoreboardTag(ENTITY_TAG);
                        });
                        return current;
                    };
                    template.place(scene.target, false, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17), List.of(enqueue), List.of());
                    require(events.calls == 1 && events.reason == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT
                            && scene.entitiesAtTarget().size() == 1, "Callback region spawn bypassed its native addition event");
                    scene.entitiesAtTarget().forEach(org.bukkit.entity.Entity::remove);
                    events.cancel = true;
                    first.set(true);
                    template.place(scene.target, false, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17), List.of(enqueue), List.of());
                    require(events.calls == 2 && scene.entitiesAtTarget().isEmpty(), "Cancelled callback-region spawn reached the world");
                    events.cancel = false;
                    first.set(true);
                    var marker = new IllegalStateException("Queued region spawn failure marker");
                    try {
                        template.place(scene.target, false, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17), List.of((region, x, y, z, current, state) -> {
                            enqueue.transform(region, x, y, z, current, state);
                            throw marker;
                        }), List.of());
                        throw new AssertionError("Queued region callback failure was swallowed");
                    } catch (RuntimeException failure) { require(failure == marker, "Queued region callback failure was replaced"); }
                    require(events.calls == 2 && scene.entitiesAtTarget().isEmpty(), "Failed template placement committed queued region entities");
                    first.set(true);
                    template.place(scene.target, false, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17), List.of(enqueue), List.of());
                    require(events.calls == 3 && scene.entitiesAtTarget().size() == 1, "Failed region queue leaked into a later placement");
                }
            }
        });
    }

    private static final class SpawnEvents implements org.bukkit.event.Listener, AutoCloseable {
        private final Scene scene;
        int calls;
        boolean cancel, sawTransformedName;
        final List<String> order = new ArrayList<>();
        org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason;
        SpawnEvents(Scene scene, Plugin plugin) {
            this.scene = scene;
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        }
        @org.bukkit.event.EventHandler
        public void spawn(org.bukkit.event.entity.CreatureSpawnEvent event) {
            if (!event.getEntity().getWorld().equals(this.scene.world) || !event.getEntity().getScoreboardTags().contains(ENTITY_TAG)
                    || event.getLocation().distanceSquared(this.scene.target) > 16) return;
            calls++;
            this.reason = event.getSpawnReason();
            this.order.add("spawn");
            this.sawTransformedName = "template-transformed-before-spawn".equals(event.getEntity().getCustomName());
            if (cancel) event.setCancelled(true);
        }
        @Override public void close() { org.bukkit.event.HandlerList.unregisterAll(this); }
    }

    /** Independent native cache/save/load oracle; only the Fabric matrix has this helper. */
    static void verifyNative(World world, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("template-native-cache-and-old-nbt-reload", () -> {
            var manager = manager();
            var key = key();
            try (var scene = new Scene(world)) {
                var template = scene.capture(false);
                var pdc = template.getPersistentDataContainer();
                pdc.set(DATA, PersistentDataType.INTEGER, 173);
                manager.registerStructure(key, template);
                var nativeWorld = world.getClass().getMethod("getHandle").invoke(world);
                var nativeTemplate = template.getClass().getMethod("getHandle").invoke(template);
                var levelClass = Class.forName("net.minecraft.server.level.ServerLevel");
                var templateClass = Class.forName("net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate");
                var fixture = Class.forName("org.cardboardpowered.test.Port263Fixture");
                require((Boolean) fixture.getMethod("hasNativeTemplate", levelClass, String.class, templateClass)
                        .invoke(null, nativeWorld, key.toString(), nativeTemplate), "Bukkit registration is invisible to Minecraft's actual cache");
                fixture.getMethod("reloadTemplateWithoutPdc", levelClass, templateClass).invoke(null, nativeWorld, nativeTemplate);
                require(template.getPersistentDataContainer() == pdc && pdc.isEmpty(), "Loading old NBT retained stale PDC or replaced held container");
                require(template.getSize().equals(new BlockVector(3, 2, 2)), "Native old-template reload lost block data");
                var nested = scene.target.clone().add(0, 0, -3);
                var nativePlace = fixture.getMethod("placeNativeTemplate", levelClass, templateClass, int.class, int.class, int.class);
                var callbacks = new AtomicInteger();
                var first = new java.util.concurrent.atomic.AtomicBoolean(true);
                template.place(scene.target, false, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17), List.of((region, x, y, z, current, state) -> {
                    callbacks.incrementAndGet();
                    if (first.getAndSet(false)) {
                        try { nativePlace.invoke(null, nativeWorld, nativeTemplate, nested.getBlockX(), nested.getBlockY(), nested.getBlockZ()); }
                        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                    }
                    if (current.getType() == Material.GOLD_BLOCK) current.setType(Material.DIAMOND_BLOCK);
                    return current;
                }), List.of());
                require(callbacks.get() == 11 && nested.clone().add(1, 0, 0).getBlock().getType() == Material.GOLD_BLOCK
                        && scene.target.clone().add(1, 0, 0).getBlock().getType() == Material.DIAMOND_BLOCK, "Nested vanilla placement inherited outer API transformers");
            } finally { manager.deleteStructure(key); }
        });
    }

    static void persistence(World world, boolean verify) throws Exception {
        var manager = manager();
        var key = new NamespacedKey("cardboard_template_probe", "process_restart");
        if (!verify) {
            require(!manager.getStructureFile(key).exists(), "Restart template already exists");
            try (var scene = new Scene(world)) {
                var template = scene.capture(false);
                template.getPersistentDataContainer().set(DATA, PersistentDataType.INTEGER, 2631729);
                manager.saveStructure(key, template);
                require(manager.getStructure(key) == null, "Persistence setup registered the template");
            }
        } else {
            require(manager.getStructure(key) == null, "Fresh server unexpectedly cached restart template");
            try (var scene = new Scene(world)) {
                var template = manager.loadStructure(key, false);
                require(value(template) == 2631729, "Template PDC did not survive full process restart");
                template.place(scene.target, false, StructureRotation.NONE, Mirror.NONE, 0, 1, new Random(17));
                require(scene.target.getBlock().getType() == Material.STONE && ((Chest) scene.target.clone().add(2, 0, 0).getBlock().getState()).getPersistentDataContainer().get(TILE, PersistentDataType.INTEGER) == 73, "Saved template blocks/tile data did not survive restart");
            } finally { manager.deleteStructure(key); }
        }
    }

    private static StructureManager manager() { var manager = Bukkit.getStructureManager(); require(manager != null, "Server.getStructureManager returned null"); return manager; }
    private static NamespacedKey key() { return new NamespacedKey("cardboard_template_probe", "case_" + UUID.randomUUID()); }
    private static int value(Structure template) { require(template != null, "Missing template"); return template.getPersistentDataContainer().getOrDefault(DATA, PersistentDataType.INTEGER, -1); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void invalid(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected IllegalArgumentException"); }
    private static void unsupported(Runnable action) { try { action.run(); } catch (UnsupportedOperationException expected) { return; } throw new AssertionError("Expected immutable map"); }

    private static final class Scene implements AutoCloseable {
        final World world;
        final Location origin, target;
        Scene(World world) {
            this.world = world;
            var spawn = world.getSpawnLocation();
            origin = new Location(world, ((spawn.getBlockX() >> 4) << 4) + 8, world.getMaxHeight() - 112, ((spawn.getBlockZ() >> 4) << 4) + 8);
            target = origin.clone().add(16, 0, 0);
            for (int dx = -4; dx <= 20; dx++) for (int dz = -4; dz <= 4; dz++)
                require(world.getHighestBlockYAt(origin.getBlockX() + dx, origin.getBlockZ() + dz, HeightMap.WORLD_SURFACE) < origin.getBlockY() - 2, "Template scene overlaps terrain");
            at(0, 0, 0).getBlock().setType(Material.STONE, false);
            at(1, 0, 0).getBlock().setType(Material.GOLD_BLOCK, false);
            at(2, 0, 0).getBlock().setType(Material.CHEST, false);
            var chest = (Chest) at(2, 0, 0).getBlock().getState();
            chest.getPersistentDataContainer().set(TILE, PersistentDataType.INTEGER, 73);
            chest.update(true, false);
            at(0, 1, 1).getBlock().setType(Material.STRUCTURE_VOID, false);
        }
        Location at(int x, int y, int z) { return origin.clone().add(x, y, z); }
        Structure capture(boolean entities) { var template = manager().createStructure(); template.fill(origin, new BlockVector(3, 2, 2), entities); return template; }
        ArmorStand entity() { var entity = world.spawn(origin.clone().add(.5, .2, .5), ArmorStand.class); entity.setGravity(false); entity.addScoreboardTag(ENTITY_TAG); return entity; }
        List<org.bukkit.entity.Entity> entitiesAtTarget() { return new ArrayList<>(world.getNearbyEntities(target.clone().add(1, 1, 1), 4, 4, 4).stream().filter(e -> e.getScoreboardTags().contains(ENTITY_TAG)).toList()); }
        void clearTarget() { for (int x = -4; x <= 4; x++) for (int y = 0; y < 3; y++) for (int z = -4; z <= 4; z++) target.clone().add(x, y, z).getBlock().setType(Material.AIR, false); }
        @Override public void close() {
            world.getNearbyEntities(origin.clone().add(8, 1, 0), 16, 5, 8).stream().filter(e -> e.getScoreboardTags().contains(ENTITY_TAG)).forEach(org.bukkit.entity.Entity::remove);
            clearTarget();
            for (int x = 0; x < 3; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) at(x, y, z).getBlock().setType(Material.AIR, false);
        }
    }
}
