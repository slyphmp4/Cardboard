package org.cardboardpowered.mixin.server.network;

import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.adventure.providers.ClickCallbackProviderImpl;
import io.papermc.paper.connection.PlayerCommonConnection;
import io.papermc.paper.event.player.CardboardPlayerCustomClickEvent;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.cardboardpowered.bridge.server.level.ServerPlayerBridge;
import org.cardboardpowered.bridge.server.network.ServerConfigurationPacketListenerImplBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dispatch genuine client custom-click packets to the registered Paper/Adventure callbacks.
 * Vanilla's handler runs first, including its normal thread transfer; registration queues
 * are drained on the native server tick in both game and configuration phases.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin_ClickCallbacks {
    @Inject(method = "handleCustomClickAction", at = @At("TAIL"))
    private void cardboard$runClickCallbacks(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        final Audience audience;
        final PlayerCommonConnection connection;
        if ((Object) this instanceof ServerGamePacketListenerImpl game && game.player != null) {
            Player player = (Player) ((ServerPlayerBridge) game.player).getBukkitEntity();
            audience = player;
            connection = ((CraftPlayer) player).getConnection();
        } else if ((Object) this instanceof ServerConfigurationPacketListenerImpl config) {
            var api = ((ServerConfigurationPacketListenerImplBridge) config).cardboard$configurationConnection();
            audience = api.getAudience();
            connection = api;
        } else {
            return;
        }
        // Report all custom actions, including unknown keys and absent payloads, to Paper plugins.
        Bukkit.getPluginManager().callEvent(new CardboardPlayerCustomClickEvent(
                PaperAdventure.asAdventure(packet.id()), connection,
                packet.payload().orElse(null)));
        ClickCallbackProviderImpl.ADVENTURE_CLICK_MANAGER.tryRunCallback(audience, packet.id(), packet.payload());
        ClickCallbackProviderImpl.DIALOG_CLICK_MANAGER.tryRunCallback(audience, packet.id(), packet.payload());
    }
}
