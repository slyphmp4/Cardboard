package org.cardboardpowered.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** A second real Minecraft process, with no local Bukkit server. */
final class IntegratedLanGuest implements ClientModInitializer {
    private final Path exchange = Path.of(System.getProperty("cardboard.smoke.exchange"));
    private final long deadline = System.nanoTime() + 420_000_000_000L;
    private int world = 1;
    private int stage;

    @Override
    public void onInitializeClient() {
        System.out.println("INTEGRATED_LAN_GUEST_START");
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        try {
            require(System.nanoTime() < deadline, "Guest timeout at world " + world + " stage " + stage);
            if (stage == 0 && client.isGameLoadFinished() && client.gui.overlay() == null
                    && Files.exists(exchange.resolve("ready-" + world))) {
                String address = "127.0.0.1:" + Files.readString(exchange.resolve("ready-" + world)).trim();
                ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                        new ServerData("Cardboard LAN smoke", address, ServerData.Type.LAN), false, null);
                stage = 1;
            } else if (stage == 1 && client.player != null && client.level != null) {
                require(client.getSingleplayerServer() == null && org.bukkit.Bukkit.getServer() == null,
                        "Guest unexpectedly has a local Bukkit server");
                CardboardIntegratedSmoke.exerciseClientCopies(client);
                Files.writeString(exchange.resolve("joined-" + world), "PASS");
                stage = 2;
            } else if (stage == 2 && Files.exists(exchange.resolve("verified-" + world))) {
                client.disconnect(new TitleScreen(), false);
                Files.writeString(exchange.resolve("left-" + world), "PASS");
                System.out.println("INTEGRATED_LAN_GUEST_WORLD_PASS " + world);
                if (world++ == 2) {
                    Files.writeString(exchange.resolve("guest.success"), "PASS");
                    System.out.println("INTEGRATED_LAN_GUEST_PASS");
                    stage = 3;
                    client.stop();
                } else stage = 0;
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.err.println("INTEGRATED_LAN_GUEST_FAILED world=" + world + " stage=" + stage);
            System.exit(1);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
