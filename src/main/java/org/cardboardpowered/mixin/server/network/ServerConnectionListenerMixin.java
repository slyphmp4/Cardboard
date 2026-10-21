package org.cardboardpowered.mixin.server.network;

import java.net.InetAddress;
import java.util.List;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.cardboardpowered.bridge.server.network.ServerConnectionListenerBridge;

import io.netty.channel.ChannelFuture;

@Mixin(ServerConnectionListener.class)
public class ServerConnectionListenerMixin implements ServerConnectionListenerBridge {

    @Shadow
    @Final
    public List<ChannelFuture> channels;

    @Unique
    private boolean cardboard$acceptingConnections;

    @Override
    public void acceptConnections() {
        synchronized (channels) {
            cardboard$acceptingConnections = true;
            for (ChannelFuture future : channels)
                future.channel().config().setAutoRead(true);
        }
    }

    @Inject(at = @At("TAIL"), method = "startTcpServerListener")
    public void cardboard$applyAcceptingState(InetAddress ina, int i, CallbackInfo ci) {
        synchronized (channels) {
            for (ChannelFuture future : channels)
                // Dedicated listeners bind before plugins finish loading; LAN listeners bind afterwards.
                future.channel().config().setAutoRead(cardboard$acceptingConnections);
        }
    }

}
