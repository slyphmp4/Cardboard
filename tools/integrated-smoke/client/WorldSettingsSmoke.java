package org.cardboardpowered.smoke;

import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.World;
import org.cardboardpowered.bridge.world.level.LevelBridge;

/** The connected owner and a fresh TCP guest must observe the configured world radius. */
final class WorldSettingsSmoke {
    static final int RADIUS = 4;
    private static volatile World world;
    private static volatile MinecraftServer server;
    private static volatile int previous;
    private static volatile boolean retainForGuest, validated;
    private static volatile long deadline;
    private static CompletableFuture<Void> cleanup;

    static void begin(ServerPlayer owner, boolean lan) {
        require(world == null, "Previous world radius probe was not cleaned up");
        world = ((LevelBridge) owner.level()).cardboard$getWorld();
        server = owner.level().getServer();
        previous = world.getViewDistance();
        retainForGuest = lan;
        validated = false;
        cleanup = null;
        deadline = System.nanoTime() + 25_000_000_000L;
        try {
            world.setViewDistance(RADIUS);
            require(world.getViewDistance() == RADIUS, "World view distance setter is missing");
        } catch (Throwable failure) {
            world.setViewDistance(previous);
            reset();
            throw failure;
        }
    }

    static boolean finishClient(Minecraft client) throws ReflectiveOperationException {
        require(System.nanoTime() < deadline, "Owner did not receive the configured world chunk-radius packet");
        if (!validated) {
            if (clientRadius(client) != RADIUS) return false;
            validated = true;
            System.out.println("WORLD_VIEW_DISTANCE_OWNER_PACKET_PASS");
        }
        if (retainForGuest) return true;
        if (cleanup == null) {
            var selected = world;
            int restore = previous;
            cleanup = CompletableFuture.runAsync(() -> selected.setViewDistance(restore), server);
        }
        if (!cleanup.isDone()) return false;
        cleanup.join();
        reset();
        return true;
    }

    static void finishGuest() {
        require(world != null && validated && retainForGuest && world.getViewDistance() == RADIUS,
                "Guest joined without a retained world view-distance override");
        world.setViewDistance(previous);
        System.out.println("WORLD_VIEW_DISTANCE_GUEST_PACKET_PASS");
        reset();
    }

    static int clientRadius(Minecraft client) throws ReflectiveOperationException {
        var connection = client.getConnection();
        if (connection == null) return -1;
        try {
            return ((Number) connection.getClass().getMethod("getServerChunkRadius").invoke(connection)).intValue();
        } catch (NoSuchMethodException absentGetter) {
            for (Class<?> type = connection.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    var field = type.getDeclaredField("serverChunkRadius");
                    field.setAccessible(true);
                    return field.getInt(connection);
                } catch (NoSuchFieldException nextClass) { }
            }
            throw new IllegalStateException("Client radius field is missing: " + java.util.Arrays.toString(connection.getClass().getDeclaredFields()));
        }
    }

    private static void reset() {
        world = null;
        server = null;
        retainForGuest = false;
        validated = false;
        cleanup = null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
