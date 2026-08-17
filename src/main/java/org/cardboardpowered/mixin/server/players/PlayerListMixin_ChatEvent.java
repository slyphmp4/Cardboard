package org.cardboardpowered.mixin.server.players;

import org.cardboardpowered.bridge.server.MinecraftServerBridge;
import org.cardboardpowered.bridge.server.level.ServerPlayerBridge;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.craftbukkit.util.Waitable;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChatEvent;

import org.cardboardpowered.impl.util.LazyPlayerSet;
import org.cardboardpowered.impl.util.WaitableImpl;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

@Mixin(PlayerList.class)
public class PlayerListMixin_ChatEvent {

    @Shadow
    public List<ServerPlayer> players;

    @Shadow
    @Final
    private MinecraftServer server;

    public CraftPlayer getPlayer_0(ServerPlayer player) {
        return (CraftPlayer) ((ServerPlayerBridge) (Object) player).getBukkitEntity();
    }

    @Inject(
            method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onSendChatMessage(
            PlayerChatMessage message,
            ServerPlayer sender,
            ChatType.Bound params,
            CallbackInfo ci
    ) {
        // Intentionally left empty.
    }

    /**
     * @author cardboard
     * @reason Alternative chat events
     */
    @Overwrite
    public void broadcastChatMessage(
            PlayerChatMessage message,
            Predicate<ServerPlayer> shouldSendFiltered,
            ServerPlayer sender,
            ChatType.Bound params
    ) {
        boolean trusted = this.verifyChatTrusted(message);

        this.server.logChatMessage(
                message.decoratedContent(),
                params,
                trusted ? null : "Not Secure"
        );

        /*
         * Only genuine player chat should enter Bukkit's player-chat event
         * pipeline.
         *
         * Commands such as /say, /me, /msg, command blocks and broadcasts
         * either have no sending player or use another ChatType. Those must
         * retain vanilla delivery.
         */
        if (sender == null || !params.chatType().is(ChatType.CHAT)) {
            OutgoingChatMessage vanillaMessage =
                    OutgoingChatMessage.create(message);

            boolean fullyFiltered = message.isFullyFiltered();
            boolean notifySender = false;

            for (ServerPlayer recipient : this.players) {
                boolean filtered = shouldSendFiltered.test(recipient);

                recipient.sendChatMessage(
                        vanillaMessage,
                        filtered,
                        params
                );

                if (sender == recipient) {
                    continue;
                }

                notifySender |= fullyFiltered && filtered;
            }

            if (notifySender && sender != null) {
                sender.sendSystemMessage(PlayerList.CHAT_FILTERED_FULL);
            }

            return;
        }

        String messageText = message.decoratedContent().getString();

        // TODO: allow actual async handling
        boolean async = false;

        Player player = getPlayer_0(sender);

        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(
                async,
                player,
                messageText,
                new LazyPlayerSet(CraftServer.server)
        );

        Bukkit.getServer()
                .getPluginManager()
                .callEvent(event);

        /*
         * Legacy PlayerChatEvent support.
         *
         * Some older Bukkit/Spigot plugins still listen for it, so keep
         * compatibility when listeners are registered.
         */
        if (PlayerChatEvent.getHandlerList()
                .getRegisteredListeners().length != 0) {

            final PlayerChatEvent queueEvent = new PlayerChatEvent(
                    player,
                    event.getMessage(),
                    event.getFormat(),
                    event.getRecipients()
            );

            queueEvent.setCancelled(event.isCancelled());

            Waitable<?> waitable = new WaitableImpl(() -> {
                Bukkit.getPluginManager().callEvent(queueEvent);

                if (queueEvent.isCancelled()) {
                    return;
                }

                String formattedMessage = String.format(
                        queueEvent.getFormat(),
                        queueEvent.getPlayer().getDisplayName(),
                        queueEvent.getMessage()
                );

                if (queueEvent.getRecipients() instanceof LazyPlayerSet lazyRecipients
                        && lazyRecipients.isLazy()) {

                    for (ServerPlayer recipient :
                            CraftServer.server.getPlayerList().getPlayers()) {

                        for (Component component :
                                CraftChatMessage.fromString(formattedMessage)) {

                            recipient.sendSystemMessage(component, false);
                        }
                    }

                } else {
                    for (Player recipient : queueEvent.getRecipients()) {
                        recipient.sendMessage(formattedMessage);
                    }
                }
            });

            if (async) {
                ((MinecraftServerBridge) CraftServer.server)
                        .getProcessQueue()
                        .add(waitable);
            } else {
                waitable.run();
            }

            try {
                waitable.get();

            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();

            } catch (ExecutionException exception) {
                throw new RuntimeException(
                        "Exception processing chat event",
                        exception.getCause()
                );
            }

            return;
        }

        if (event.isCancelled()) {
            return;
        }

        String formattedMessage = String.format(
                event.getFormat(),
                event.getPlayer().getDisplayName(),
                event.getMessage()
        );

        if (event.getRecipients() instanceof LazyPlayerSet lazyRecipients
                && lazyRecipients.isLazy()) {

            for (ServerPlayer recipient :
                    this.server.getPlayerList().players) {

                for (Component component :
                        CraftChatMessage.fromString(formattedMessage)) {

                    recipient.sendSystemMessage(component);
                }
            }

        } else {
            for (Player recipient : event.getRecipients()) {
                recipient.sendMessage(formattedMessage);
            }
        }
    }

    @Shadow
    private boolean verifyChatTrusted(PlayerChatMessage message) {
        return true;
    }
}