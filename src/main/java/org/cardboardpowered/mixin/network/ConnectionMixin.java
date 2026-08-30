package org.cardboardpowered.mixin.network;

import java.net.SocketAddress;
import java.util.UUID;

import com.mojang.authlib.properties.Property;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.PacketFlow;
import org.cardboardpowered.bridge.network.ConnectionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Connection.class)
public class ConnectionMixin implements ConnectionBridge {

    @Shadow
    private volatile PacketListener packetListener;

    public UUID spoofedUUID;
    public Property[] spoofedProfile;
    public boolean preparing = true;

    @Redirect(
            method = "exceptionCaught",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/Connection;getSending()Lnet/minecraft/network/protocol/PacketFlow;"
            )
    )
    private PacketFlow cardboard$avoidUnsupportedDisconnectPacket(Connection connection) {
        PacketFlow sending = connection.getSending();
        PacketListener listener = this.packetListener;

        if (sending == PacketFlow.CLIENTBOUND && listener != null) {
            ConnectionProtocol protocol = listener.protocol();
            if (protocol == ConnectionProtocol.STATUS || protocol == ConnectionProtocol.HANDSHAKING) {
                // MC-271325: these protocol states do not have a clientbound disconnect packet.
                // Returning SERVERBOUND makes vanilla take the direct-disconnect path instead.
                return PacketFlow.SERVERBOUND;
            }
        }

        return sending;
    }

    @Override
    public SocketAddress getRawAddress() {
        return ((Connection)(Object)this).channel.remoteAddress();
    }

    @Override
    public UUID getSpoofedUUID() {
        return spoofedUUID;
    }

    @Override
    public void setSpoofedUUID(UUID uuid) {
        this.spoofedUUID = uuid;
    }

    @Override
    public Property[] getSpoofedProfile() {
        return spoofedProfile;
    }

    @Override
    public void setSpoofedProfile(Property[] profile) {
        this.spoofedProfile = profile;
    }

}
