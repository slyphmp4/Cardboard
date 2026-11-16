package org.cardboardpowered.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
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
    private long phaseDeadline;
    private boolean abortedConfiguration;

    @Override
    public void onInitializeClient() {
        System.out.println("INTEGRATED_LAN_GUEST_START");
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        try {
            if (world == 1 && stage == 1 && !abortedConfiguration && client.gui.screen() != null
                    && client.gui.screen().getTitle().getString().equals("p263-config")) {
                abortedConfiguration = true;
                // Minecraft's play-only disconnect helper has no game listener yet.
                // Close the real configuration transport before clearing its screen.
                ClientConfigurationNetworking.getSender().disconnect(net.minecraft.network.chat.Component.literal("Configuration abort probe"));
                client.disconnect(new TitleScreen(), false);
                phaseDeadline = System.nanoTime() + 15_000_000_000L;
                stage = 7;
                return;
            }
            ConfigurationDialogClientSmoke.tick(client);
            require(System.nanoTime() < deadline, "Guest timeout at world " + world + " stage " + stage);
            if (stage == 1 || stage == 2) {
                require(System.nanoTime() < phaseDeadline, "Guest connection phase timed out at world " + world + " stage " + stage
                        + ", playerLoaded=" + (client.player != null) + ", worldLoaded=" + (client.level != null)
                        + ", radius=" + WorldSettingsSmoke.clientRadius(client) + ", expected=" + WorldSettingsSmoke.RADIUS);
            }
            String ready = world == 3 ? "dedicated-ready" : "ready-" + world;
            if (stage == 7) {
                require(System.nanoTime() < phaseDeadline, "Disconnected configuration handler did not stop");
                var id = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID("SmokeGuest");
                if (Files.exists(exchange.resolve("configuration-aborted-" + id))) {
                    stage = 0;
                    System.out.println("CONFIGURATION_GUEST_ABORT_PASS " + id);
                }
                return;
            }
            if (stage == 0 && client.isGameLoadFinished() && client.gui.overlay() == null
                    && Files.exists(exchange.resolve(ready))) {
                String address = "127.0.0.1:" + Files.readString(exchange.resolve(ready)).trim();
                ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                        new ServerData("Cardboard LAN smoke", address, ServerData.Type.LAN), false, null);
                System.out.println("INTEGRATED_LAN_GUEST_CONNECT world=" + world + " address=" + address);
                phaseDeadline = System.nanoTime() + 90_000_000_000L;
                stage = 1;
            } else if (stage == 1 && client.player != null && client.level != null) {
                require(client.getSingleplayerServer() == null && org.bukkit.Bukkit.getServer() == null,
                        "Guest unexpectedly has a local Bukkit server");
                if (world < 3 && WorldSettingsSmoke.clientRadius(client) != WorldSettingsSmoke.RADIUS) return;
                CardboardIntegratedSmoke.exerciseClientCopies(client);
                Files.writeString(exchange.resolve(world == 3 ? "dedicated-joined" : "joined-" + world), "PASS");
                System.out.println("INTEGRATED_LAN_GUEST_JOINED " + world);
                phaseDeadline = System.nanoTime() + 90_000_000_000L;
                stage = 2;
            } else if (stage == 2 && Files.exists(exchange.resolve(world == 3 ? "dedicated-verified" : "verified-" + world))) {
                if (client.player == null || client.level == null) return;
                if (world == 3) System.out.println("CONFIGURATION_GUEST_RETURN_PASS realTCP=1");
                client.disconnect(new TitleScreen(), false);
                Files.writeString(exchange.resolve(world == 3 ? "dedicated-left" : "left-" + world), "PASS");
                System.out.println("INTEGRATED_LAN_GUEST_WORLD_PASS " + world);
                if (world++ == 3) {
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
