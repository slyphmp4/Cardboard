package org.cardboardpowered.test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Shared public settings contracts with native behavior oracles on disposable worlds. */
final class WorldSettingsProbes {
    static void verify(World world, Plugin plugin, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("world-view-distance-aliases-validation-and-isolation", () -> {
            var other = Bukkit.getWorlds().stream().filter(w -> !w.equals(world)).findFirst().orElseThrow();
            int previous = world.getViewDistance(), otherDistance = other.getViewDistance(), serverDistance = Bukkit.getViewDistance();
            try {
                world.setViewDistance(4);
                require(world.getViewDistance() == 4 && world.getNoTickViewDistance() == 4, "View distance/legacy getter did not follow setter");
                require(other.getViewDistance() == otherDistance && Bukkit.getViewDistance() == serverDistance, "Per-world view distance changed another world or the server default");
                world.setNoTickViewDistance(5);
                require(world.getViewDistance() == 5 && world.getNoTickViewDistance() == 5, "Deprecated no-tick setter did not delegate to view distance");
                invalid(() -> world.setViewDistance(1));
                invalid(() -> world.setViewDistance(33));
                invalid(() -> world.setNoTickViewDistance(1));
                require(world.getViewDistance() == 5, "Invalid view distance changed the effective setting");
                world.setViewDistance(2);
                require(world.getViewDistance() == 2, "Minimum view distance was rejected");
            } finally { world.setViewDistance(previous); }
        });
        check.accept("world-void-settings-roundtrip-and-isolation", () -> {
            var other = Bukkit.getWorlds().stream().filter(w -> !w.equals(world)).findFirst().orElseThrow();
            try (var original = new VoidSettings(world); var otherState = new VoidSettings(other)) {
                world.setVoidDamageEnabled(true);
                world.setVoidDamageAmount(3.5f);
                world.setVoidDamageMinBuildHeightOffset(-16.25);
                require(world.isVoidDamageEnabled() && world.getVoidDamageAmount() == 3.5f
                        && world.getVoidDamageMinBuildHeightOffset() == -16.25, "Void setters/getters do not roundtrip");
                require(otherState.matches(), "Void settings changed another world");
                world.setVoidDamageEnabled(false);
                require(!world.isVoidDamageEnabled() && world.getVoidDamageAmount() == 3.5f
                        && world.getVoidDamageMinBuildHeightOffset() == -16.25, "Disabling void damage discarded its settings");
            }
        });
        check.accept("living-damage-cooldown-native-getter-setter", () -> {
            var pig = world.spawn(air(world), Pig.class);
            pig.setGravity(false);
            pig.setAI(false);
            try {
                var handle = pig.getClass().getMethod("getHandle").invoke(pig);
                var cooldown = Class.forName("net.minecraft.world.entity.LivingEntity").getDeclaredField("damageCooldownTime");
                cooldown.setAccessible(true);
                for (int ticks : new int[] {0, 1, 19, 20, -1}) {
                    pig.setNoDamageTicks(ticks);
                    require(cooldown.getInt(handle) == ticks && pig.getNoDamageTicks() == ticks,
                            "No-damage ticks did not update the native damage cooldown: " + ticks);
                }
                cooldown.setInt(handle, 7);
                require(pig.getNoDamageTicks() == 7, "No-damage getter read a different native timer");
                pig.setNoDamageTicks(0);
            } finally { pig.remove(); }
        });
        check.accept("world-void-native-damage-threshold-and-cancellation", () -> {
            try (var original = new VoidSettings(world)) {
                var pig = world.spawn(air(world), Pig.class);
                pig.setGravity(false);
                pig.setAI(false);
                try (var events = new DamageEvents(pig, plugin)) {
                    double health = pig.getHealth();
                    world.setVoidDamageEnabled(false);
                    below(pig, world.getMinHeight() - 80);
                    require(events.observed.isEmpty() && pig.getHealth() == health && pig.isValid(), "Disabled void damage still hurt the native entity");
                    world.setVoidDamageEnabled(true);
                    world.setVoidDamageAmount(3f);
                    world.setVoidDamageMinBuildHeightOffset(-16.25);
                    pig.setNoDamageTicks(0);
                    below(pig, world.getMinHeight() - 16);
                    require(events.observed.isEmpty() && pig.getHealth() == health, "Native void check ignored the configured height threshold");
                    pig.setNoDamageTicks(0);
                    below(pig, world.getMinHeight() - 17);
                    require(events.observed.size() == 1 && events.observed.getFirst().cause() == EntityDamageEvent.DamageCause.VOID
                            && events.observed.getFirst().amount() == 3 && Math.abs(pig.getHealth() - (health - 3)) < 1e-6, "Native void damage did not use configured amount or VOID event");
                    health = pig.getHealth();
                    events.cancel = true;
                    pig.setNoDamageTicks(0);
                    below(pig, world.getMinHeight() - 17);
                    require(events.observed.size() == 2 && pig.getHealth() == health, "Cancelled native void damage changed health");
                    events.cancel = false;
                    events.replacement = 1.5;
                    pig.setNoDamageTicks(0);
                    below(pig, world.getMinHeight() - 17);
                    require(events.observed.size() == 3 && Math.abs(pig.getHealth() - (health - 1.5)) < 1e-6, "Void event damage edits were ignored: events=" + events.observed + ", health=" + pig.getHealth() + ", expected=" + (health - 1.5) + ", cooldown=" + pig.getNoDamageTicks());
                } finally { pig.remove(); }
            }
        });
        check.accept("world-void-disabled-and-enabled-nonliving-removal", () -> {
            try (var original = new VoidSettings(world)) {
                var item = world.dropItem(air(world), new ItemStack(Material.STONE));
                try {
                    require(item.isValid(), "Could not create nonliving void fixture");
                    world.setVoidDamageEnabled(false);
                    below(item, world.getMinHeight() - 80);
                    require(item.isValid(), "Disabled void check discarded a nonliving entity");
                    world.setVoidDamageEnabled(true);
                    world.setVoidDamageMinBuildHeightOffset(-16.25);
                    below(item, world.getMinHeight() - 17);
                    require(!item.isValid(), "Enabled native void check did not remove nonliving entity");
                } finally { item.remove(); }
            }
        });
    }

    static void verifyNative(World world, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("world-view-distance-native-tracker-oracle", () -> {
            int previous = world.getViewDistance();
            try {
                world.setViewDistance(6);
                var level = world.getClass().getMethod("getHandle").invoke(world);
                var cache = level.getClass().getMethod("getChunkSource").invoke(level);
                var field = Class.forName("net.minecraft.server.level.ServerChunkCache").getDeclaredField("chunkMap");
                field.setAccessible(true);
                var chunkMap = field.get(cache);
                var distance = Class.forName("net.minecraft.server.level.ChunkMap").getDeclaredField("serverViewDistance");
                distance.setAccessible(true);
                require(distance.getInt(chunkMap) == 6 && world.getViewDistance() == 6, "World setter changed only a wrapper instead of the native chunk tracker");
            } finally { world.setViewDistance(previous); }
        });
    }

    private static Location air(World world) {
        var point = world.getSpawnLocation().add(3.25, 0, 3.25);
        point.setY(world.getMaxHeight() - 80);
        require(world.getHighestBlockYAt(point.getBlockX(), point.getBlockZ(), HeightMap.WORLD_SURFACE) < point.getBlockY() - 4, "Void fixture overlaps terrain");
        return point;
    }

    /** Direct native positioning/check avoids coupling this oracle to unrelated teleport behavior. */
    private static void below(Entity entity, double y) throws Exception {
        var nativeEntity = entity.getClass().getMethod("getHandle").invoke(entity);
        var location = entity.getLocation();
        nativeEntity.getClass().getMethod("setPos", double.class, double.class, double.class)
                .invoke(nativeEntity, location.getX(), y, location.getZ());
        nativeEntity.getClass().getMethod("checkBelowWorld").invoke(nativeEntity);
    }

    private record Observation(EntityDamageEvent.DamageCause cause, double amount) { }

    private static final class DamageEvents implements Listener, AutoCloseable {
        final UUID entity;
        final List<Observation> observed = new ArrayList<>();
        boolean cancel;
        Double replacement;
        DamageEvents(Entity entity, Plugin plugin) {
            this.entity = entity.getUniqueId();
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        }
        @EventHandler
        public void damage(EntityDamageEvent event) {
            if (!this.entity.equals(event.getEntity().getUniqueId())) return;
            this.observed.add(new Observation(event.getCause(), event.getDamage()));
            if (this.cancel) event.setCancelled(true);
            else if (this.replacement != null) event.setDamage(this.replacement);
        }
        @Override public void close() { HandlerList.unregisterAll(this); }
    }

    private static final class VoidSettings implements AutoCloseable {
        final World world;
        final boolean enabled;
        final float amount;
        final double offset;
        VoidSettings(World world) {
            this.world = world;
            enabled = world.isVoidDamageEnabled();
            amount = world.getVoidDamageAmount();
            offset = world.getVoidDamageMinBuildHeightOffset();
        }
        boolean matches() {
            return this.world.isVoidDamageEnabled() == enabled && this.world.getVoidDamageAmount() == amount
                    && this.world.getVoidDamageMinBuildHeightOffset() == offset;
        }
        @Override public void close() {
            this.world.setVoidDamageEnabled(enabled);
            this.world.setVoidDamageAmount(amount);
            this.world.setVoidDamageMinBuildHeightOffset(offset);
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void invalid(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected IllegalArgumentException"); }
}
