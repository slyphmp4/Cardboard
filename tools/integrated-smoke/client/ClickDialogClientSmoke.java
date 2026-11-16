package org.cardboardpowered.smoke;

import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Exercise actual client -> server custom-click packets, not only callback-manager methods. */
final class ClickDialogClientSmoke {
    private static volatile State active;

    static void begin(MinecraftServer server, UUID owner) {
        require(active == null, "Old world retained a click-callback smoke scope");
        var player = Bukkit.getPlayer(owner);
        require(player != null, "Missing owner for click callback smoke");
        var state = new State(owner, server.getTickCount());
        var options = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(1)).build();
        var click = ClickEvent.callback(state::onChat, options);
        require(click.action() == ClickEvent.Action.CUSTOM && click.payload() instanceof ClickEvent.Payload.Custom,
                "ClickCallback SPI did not produce a native custom action");
        var payload = (ClickEvent.Payload.Custom) click.payload();
        state.chatKey = PaperAdventure.asVanilla(payload.key());
        state.chatPayload = decode(payload.nbt().string());
        var dialog = DialogAction.customClick((response, audience) -> {
            require(response.payload() != null, "Dialog response payload was discarded");
            state.onDialog(audience);
        }, options);
        state.dialogKey = PaperAdventure.asVanilla(dialog.id());
        state.dialogPayload = decode(dialog.additions().string());
        var plugin = Bukkit.getPluginManager().getPlugin("IntegratedSmoke");
        require(plugin != null, "Missing plugin for native click event verification");
        Bukkit.getPluginManager().registerEvents(state, plugin);
        player.sendMessage(Component.text("Cardboard click callback smoke").clickEvent(click));
        active = state;
    }

    static boolean finish(Minecraft client) {
        State state = active;
        if (state == null) return true;
        if (client.player == null || client.getSingleplayerServer() == null) return false;
        if (!state.sent) {
            if (client.getSingleplayerServer().getTickCount() < state.createdTick + 3) return false;
            var network = client.player.connection;
            // A foreign action must not consume either callback; duplicate native packets must not replay them.
            network.send(new ServerboundCustomClickActionPacket(Identifier.parse("cardboard:unknown_callback"), Optional.of(state.chatPayload)));
            network.send(new ServerboundCustomClickActionPacket(state.chatKey, Optional.of(state.chatPayload)));
            network.send(new ServerboundCustomClickActionPacket(state.chatKey, Optional.of(state.chatPayload)));
            network.send(new ServerboundCustomClickActionPacket(state.dialogKey, Optional.of(state.dialogPayload)));
            network.send(new ServerboundCustomClickActionPacket(state.dialogKey, Optional.of(state.dialogPayload)));
            network.send(new ServerboundCustomClickActionPacket(Identifier.parse("cardboard:unknown_callback"), Optional.empty()));
            network.send(new ServerboundCustomClickActionPacket(Identifier.parse("cardboard:unknown_callback"), Optional.of(decode("'scalar payload'"))));
            network.send(new ServerboundCustomClickActionPacket(Identifier.parse("cardboard:unknown_callback"), Optional.of(decode("[1,2,3]"))));
            network.send(new ServerboundCustomClickActionPacket(state.chatKey, Optional.of(decode("{id:'bad'}"))));
            network.send(new ServerboundCustomClickActionPacket(state.dialogKey, Optional.of(decode("{id:'bad'}"))));
            state.sent = true;
            state.sentAt = System.nanoTime();
            return false;
        }
        if (state.eventError.get() != null) throw new AssertionError("PlayerCustomClickEvent payload invalid", state.eventError.get());
        if (state.chatCalls.get() != 1 || state.dialogCalls.get() != 1 || state.eventCalls.get() != 10) {
            require(System.nanoTime() - state.sentAt < 10_000_000_000L,
                    "Client packets did not execute click/dialog callbacks/events correctly: chat="
                            + state.chatCalls + " dialog=" + state.dialogCalls + " events=" + state.eventCalls);
            return false;
        }
        // Give replays a few more server ticks to arrive before declaring success.
        if (++state.verifiedTicks < 4) return false;
        require(state.chatCalls.get() == 1 && state.dialogCalls.get() == 1 && state.eventCalls.get() == 10,
                "Callback was replayed or custom-click events were lost");
        client.getSingleplayerServer().execute(() -> org.bukkit.event.HandlerList.unregisterAll(state));
        active = null;
        System.out.println("CLICK_DIALOG_CLIENT_PACKETS_PASS owner=" + state.owner);
        return true;
    }

    private static Tag decode(String snbt) {
        try { return PaperAdventure.NBT_CODEC.decode(snbt); }
        catch (Exception failure) { throw new IllegalStateException("Callback payload is not valid SNBT", failure); }
    }

    private static void require(boolean good, String message) {
        if (!good) throw new AssertionError(message);
    }

    private static final class State implements Listener {
        final UUID owner;
        final int createdTick;
        final AtomicInteger chatCalls = new AtomicInteger();
        final AtomicInteger dialogCalls = new AtomicInteger();
        final AtomicInteger eventCalls = new AtomicInteger();
        final AtomicReference<Throwable> eventError = new AtomicReference<>();
        Identifier chatKey, dialogKey;
        Tag chatPayload, dialogPayload;
        boolean sent;
        long sentAt;
        int verifiedTicks;

        State(UUID owner, int createdTick) { this.owner = owner; this.createdTick = createdTick; }

        @EventHandler
        public void onNativeClick(PlayerCustomClickEvent event) {
            try {
                var id = event.getIdentifier().asString();
                if (!(id.equals("cardboard:unknown_callback")
                        || id.equals(PaperAdventure.asAdventure(chatKey).asString())
                        || id.equals(PaperAdventure.asAdventure(dialogKey).asString()))) return;
                require(Bukkit.isPrimaryThread(), "Custom-click event ran on the network thread");
                require(event.getCommonConnection() instanceof io.papermc.paper.connection.PlayerGameConnection connection
                        && connection.getPlayer().getUniqueId().equals(owner), "Custom-click event lost actor connection");
                int index = eventCalls.get();
                if (index < 5 || index >= 8) {
                    require(event.getTag() != null && event.getDialogResponseView() != null,
                            "Custom-click event lost compound payload");
                    if (index >= 8) require("bad".equals(event.getDialogResponseView().getText("id")), "Malformed id changed in event");
                } else {
                    require(event.getDialogResponseView() == null, "Non-compound payload became a dialog response");
                    if (index == 5) require(event.getTag() == null, "Absent payload became an NBT tag");
                    else require(event.getTag() != null && decode(event.getTag().string()).equals(
                            decode(index == 6 ? "'scalar payload'" : "[1,2,3]")), "Non-compound payload changed");
                }
                eventCalls.incrementAndGet();
            } catch (Throwable failure) {
                eventError.compareAndSet(null, failure);
            }
        }

        void onChat(Audience audience) {
            verifyAudience(audience);
            require(eventCalls.get() == 2, "Chat callback ran before its custom-click event");
            chatCalls.incrementAndGet();
        }
        void onDialog(Audience audience) {
            verifyAudience(audience);
            require(eventCalls.get() == 4, "Dialog callback ran before its custom-click event");
            dialogCalls.incrementAndGet();
        }
        void verifyAudience(Audience audience) {
            require(Bukkit.isPrimaryThread(), "Callback ran on the network thread");
            require(audience instanceof Player player && player.getUniqueId().equals(owner),
                    "Wrong click-callback audience: " + audience);
        }
    }
}
