package org.cardboardpowered.mixin.server.network;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.cardboardpowered.bridge.network.ConnectionBridge;
import org.cardboardpowered.bridge.server.network.ClientBrandBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.2: capture the client brand from the vanilla minecraft:brand payload,
// which is what Paper exposes as PlayerCommonConnection#getClientBrandName.
@Mixin(ServerCommonPacketListenerImpl.class)
public class ServerCommonPacketListenerImplMixin_Brand implements ClientBrandBridge {

    @Shadow
    protected Connection connection;

    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void cardboard$captureBrand(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (packet.payload() instanceof BrandPayload brandPayload) {
            ((ConnectionBridge) this.connection).cardboard$setClientBrand(brandPayload.brand());
        }
    }

    // Some modded BlockEntity implementations legitimately do not provide a
    // client update packet. CraftBukkit compatibility paths may still attempt
    // to forward BlockEntity#getUpdatePacket(), so guard the connection boundary
    // instead of letting ServerCommonPacketListenerImpl dereference null.
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void cardboard$ignoreNullPacket(Packet<?> packet, CallbackInfo ci) {
        if (packet == null) {
            ci.cancel();
        }
    }

    @Override
    public String cardboard_getClientBrand() {
        return ((ConnectionBridge) this.connection).cardboard$getClientBrand();
    }

    @Override
    public void cardboard_setClientBrand(String brand) {
        ((ConnectionBridge) this.connection).cardboard$setClientBrand(brand);
    }
}
