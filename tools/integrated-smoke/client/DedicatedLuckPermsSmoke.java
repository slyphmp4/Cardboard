package org.cardboardpowered.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.bukkit.Bukkit;

/** LuckPerms-Fabric declares environment=server; exercise it on its supported dedicated platform. */
public final class DedicatedLuckPermsSmoke implements ModInitializer {
    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("cardboard.smoke.dedicated")) return;
        Path exchange = Path.of(System.getProperty("cardboard.smoke.exchange"));
        AtomicBoolean verified = new AtomicBoolean();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("luckperms")) {
                    throw new AssertionError("Real LuckPerms-Fabric was not loaded");
                }
                Files.createDirectories(exchange);
                Files.writeString(exchange.resolve("dedicated-ready"), "25579");
            } catch (Throwable failure) {
                fail(failure);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                if (!verified.get() && Files.exists(exchange.resolve("dedicated-joined"))) {
                    UUID id = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID("SmokeGuest");
                    var player = Bukkit.getPlayer(id);
                    if (player == null || player.isOp()) throw new AssertionError("Expected a real non-op guest");
                    var plugin = Bukkit.getPluginManager().getPlugin("IntegratedSmoke");
                    plugin.getClass().getMethod("verifyLogin", UUID.class).invoke(plugin, id);
                    LuckPermsSmoke.verify(player);
                    Files.writeString(exchange.resolve("dedicated-verified"), "PASS");
                    verified.set(true);
                }
                if (verified.get() && Files.exists(exchange.resolve("dedicated-left")) && Bukkit.getOnlinePlayers().isEmpty()) {
                    Files.writeString(exchange.resolve("dedicated.success"), "PASS");
                    System.out.println("DEDICATED_LUCKPERMS_PASS");
                    server.halt(false);
                }
            } catch (Throwable failure) {
                fail(failure);
            }
        });
    }

    private static void fail(Throwable failure) {
        failure.printStackTrace();
        System.err.println("DEDICATED_LUCKPERMS_FAILED");
        System.exit(1);
    }
}
