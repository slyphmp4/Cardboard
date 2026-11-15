package org.cardboardpowered.mixin.server.network;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.cardboardpowered.bridge.server.level.PlayerPersonalStateBridge;
import org.spongepowered.asm.mixin.Mixin;

/** Includes initial world info, periodic clock/weather updates and explicit callback sends. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPlayerStateMixin {
    @WrapMethod(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V")
    private void cardboard$personalize(Packet<?> packet, ChannelFutureListener listener, Operation<Void> original) {
        if (packet != null && (Object) this instanceof ServerGamePacketListenerImpl game && game.player != null) {
            var settings = (PlayerPersonalStateBridge) game.player;
            Packet<?> outgoing = settings.cardboard$personalizePacket(packet);
            if (outgoing == null) {
                if (listener == null) return;
                // Preserve a write callback without sending the world's overridden weather.
                outgoing = settings.cardboard$weatherSnapshotPacket();
            }
            original.call(outgoing, listener);
        } else original.call(packet, listener);
    }
}
