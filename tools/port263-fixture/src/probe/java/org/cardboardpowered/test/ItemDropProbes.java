package org.cardboardpowered.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class ItemDropProbes {
    static void verify(World world, Plugin plugin, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("drop-natural-callback-before-spawn-event", () -> {
            try (var scene = new Scene(world); var observer = new Observer(plugin)) {
                var callback = new AtomicInteger();
                var eventSawChanges = new AtomicBoolean();
                observer.action = event -> eventSawChanges.set(callback.get() == 1
                        && event.getEntity().getItemStack().getType() == Material.EMERALD
                        && event.getEntity().getItemStack().getAmount() == 3
                        && event.getEntity().getPickupDelay() == 37);
                var original = new ItemStack(Material.DIAMOND, 2);
                var copy = original.clone();
                var item = scene.keep(world.dropItemNaturally(scene.location, original, value -> {
                    scene.keep(value);
                    callback.incrementAndGet();
                    require(!value.isValid() && world.getEntity(value.getUniqueId()) == null, "Callback ran after item was added");
                    value.setItemStack(new ItemStack(Material.EMERALD, 3));
                    value.setPickupDelay(37);
                }));
                require(item != null && item.isValid(), "Natural callback drop returned no live item");
                require(callback.get() == 1 && observer.seen.size() == 1 && eventSawChanges.get(), "Spawn event did not see exactly one completed callback");
                require(item.getItemStack().getType() == Material.EMERALD && item.getPickupDelay() == 37, "Callback changes were lost");
                require(original.equals(copy), "Dropping mutated the caller's stack");
            }
        });
        check.accept("drop-cancellation-all-overloads", () -> {
            for (int mode = 0; mode < 4; mode++) {
                try (var scene = new Scene(world); var observer = new Observer(plugin)) {
                    observer.action = event -> event.setCancelled(true);
                    var callbacks = new AtomicInteger();
                    Consumer<Item> action = value -> { scene.keep(value); callbacks.incrementAndGet(); };
                    var stack = new ItemStack(Material.DIAMOND);
                    var item = scene.keep(switch (mode) {
                        case 0 -> world.dropItem(scene.location, stack);
                        case 1 -> world.dropItem(scene.location, stack, action);
                        case 2 -> world.dropItemNaturally(scene.location, stack);
                        default -> world.dropItemNaturally(scene.location, stack, action);
                    });
                    require(item != null && !item.isValid() && world.getEntity(item.getUniqueId()) == null, "Cancelled overload left a spawned item, mode=" + mode);
                    require(observer.seen.size() == 1 && observer.seen.getFirst().equals(item), "Missing or duplicate ItemSpawnEvent, mode=" + mode);
                    require(callbacks.get() == (mode % 2), "Wrong callback count on cancellation, mode=" + mode);
                }
            }
        });
        check.accept("drop-natural-centered-jitter-and-input-copy", () -> {
            try (var scene = new Scene(world)) {
                var point = scene.location.clone();
                var stack = new ItemStack(Material.DIAMOND, 2);
                var copy = stack.clone();
                for (int i = 0; i < 16; i++) {
                    var item = scene.keep(i % 2 == 0 ? world.dropItemNaturally(scene.location, stack)
                            : world.dropItemNaturally(scene.location, stack, null));
                    require(item != null, "Natural overload returned null");
                    var location = item.getLocation();
                    double dx = location.getX() - point.getX(), dz = location.getZ() - point.getZ();
                    double centeredY = location.getY() - point.getY() + item.getHeight() / 2;
                    require(inJitter(dx) && inJitter(dz) && inJitter(centeredY), "Natural drop jitter disagrees with Paper's centered offsets");
                    require(scene.location.equals(point) && stack.equals(copy), "Natural drop mutated input values");
                }
            }
        });
        check.accept("drop-exact-location-defaults-and-single-event", () -> {
            for (int mode = 0; mode < 2; mode++) {
                try (var scene = new Scene(world); var observer = new Observer(plugin)) {
                    var stack = new ItemStack(Material.DIAMOND, 2);
                    var item = scene.keep(mode == 0 ? world.dropItem(scene.location, stack)
                            : world.dropItem(scene.location, stack, null));
                    require(item != null && item.isValid(), "Exact drop returned no live item");
                    require(item.getLocation().toVector().equals(scene.location.toVector()), "Exact drop moved its coordinates");
                    require(item.getPickupDelay() == 10 && item.getItemStack().equals(stack), "Default pickup delay/stack changed");
                    require(observer.seen.size() == 1 && observer.seen.getFirst().equals(item), "Exact overload omitted or duplicated spawn event, mode=" + mode);
                }
            }
        });
        check.accept("drop-callback-failure-and-null-validation", () -> {
            try (var scene = new Scene(world); var observer = new Observer(plugin)) {
                var marker = new IllegalStateException("callback marker");
                boolean thrown = false;
                try {
                    scene.keep(world.dropItemNaturally(scene.location, new ItemStack(Material.DIAMOND), item -> {
                        scene.keep(item);
                        throw marker;
                    }));
                } catch (RuntimeException failure) {
                    require(failure == marker, "Callback exception was replaced");
                    thrown = true;
                }
                require(thrown && scene.items.size() == 1, "Callback failure was swallowed");
                var failed = scene.items.getFirst();
                require(!failed.isValid() && world.getEntity(failed.getUniqueId()) == null && observer.seen.isEmpty(), "Failed callback left a spawned item/event");
                expectIllegal(() -> scene.keep(world.dropItem(null, new ItemStack(Material.DIAMOND))));
                expectIllegal(() -> scene.keep(world.dropItem(scene.location, null)));
                expectIllegal(() -> scene.keep(world.dropItemNaturally(null, new ItemStack(Material.DIAMOND))));
                expectIllegal(() -> scene.keep(world.dropItemNaturally(scene.location, null)));
                expectIllegal(() -> scene.keep(world.dropItemNaturally(null, new ItemStack(Material.DIAMOND), null)));
                expectIllegal(() -> scene.keep(world.dropItemNaturally(scene.location, null, null)));
            }
        });
    }

    private static boolean inJitter(double value) { return value >= -0.250000001 && value <= 0.250000001; }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void expectIllegal(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Null drop argument was accepted");
    }

    public static final class Observer implements Listener, AutoCloseable {
        final List<Item> seen = new ArrayList<>();
        Consumer<ItemSpawnEvent> action = event -> {};

        Observer(Plugin plugin) { Bukkit.getPluginManager().registerEvents(this, plugin); }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onSpawn(ItemSpawnEvent event) {
            this.seen.add(event.getEntity());
            this.action.accept(event);
        }

        @Override public void close() { HandlerList.unregisterAll(this); }
    }

    private static final class Scene implements AutoCloseable {
        final Location location;
        final List<Item> items = new ArrayList<>();

        Scene(World world) {
            var spawn = world.getSpawnLocation();
            location = new Location(world, ((spawn.getBlockX() >> 4) << 4) + 8.5,
                    spawn.getBlockY() + 6.25, ((spawn.getBlockZ() >> 4) << 4) + 8.5);
        }

        Item keep(Item item) {
            if (item != null && !items.contains(item)) items.add(item);
            return item;
        }

        @Override public void close() { items.forEach(Item::remove); }
    }
}
