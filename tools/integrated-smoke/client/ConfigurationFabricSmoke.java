package org.cardboardpowered.smoke;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.MinecraftServer;

/** A real Fabric early task must finish before Paper's configuration/dialog flow. */
final class ConfigurationFabricSmoke {
    private static final Map<ServerConfigurationPacketListenerImpl, Integer> calls = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<MinecraftServer, Map<UUID, Integer>> joins = Collections.synchronizedMap(new WeakHashMap<>());

    static void install() {
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) ->
                joins.computeIfAbsent(server, ignored -> new ConcurrentHashMap<>()).merge(listener.player.getUUID(), 1, Integer::sum));
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register((listener, server) -> {
            require(server.isSameThread(), "Fabric early configuration ran on Netty");
            ((FabricServerConfigurationPacketListenerImpl) listener).addTask(new ConfigurationTask() {
                private final Type type = new Type("cardboard_smoke_early_configuration");
                private int ticks;
                @Override public Type type() { return this.type; }
                @Override public void start(Consumer<Packet<?>> sender) {}
                @Override public boolean tick() {
                    if (++this.ticks == 3) ((FabricServerConfigurationPacketListenerImpl) listener).completeTask(this.type);
                    return false;
                }
            });
        });
        ServerConfigurationConnectionEvents.CONFIGURE.register((listener, server) -> {
            require(server.isSameThread(), "Fabric configure event ran on Netty");
            require(calls.merge(listener, 1, Integer::sum) == 1, "Fabric configure event was replayed by thread migration");
            System.out.println("CONFIGURATION_FABRIC_PHASE_PASS " + listener.getOwner().id());
        });
    }

    static int joinCount(MinecraftServer server, UUID player) {
        Map<UUID, Integer> counts = joins.get(server);
        return counts == null ? 0 : counts.getOrDefault(player, 0);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
