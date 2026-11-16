package org.cardboardpowered.mixin.server.network;

import java.nio.charset.StandardCharsets;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.bukkit.Bukkit;
import org.cardboardpowered.bridge.network.DiscardedPayloadBridge;
import org.cardboardpowered.bridge.server.network.ServerConfigurationPacketListenerImplBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin_Configuration {
    @Shadow @Final protected MinecraftServer server;

    @Inject(method = "handleCookieResponse", at = @At("HEAD"), cancellable = true)
    private void cardboard$configurationCookie(ServerboundCookieResponsePacket packet, CallbackInfo ci) {
        if (!((Object) this instanceof ServerConfigurationPacketListenerImpl config)) return;
        PacketUtils.ensureRunningOnSameThread(packet, config, this.server.packetProcessor());
        var api = ((ServerConfigurationPacketListenerImplBridge) config).cardboard$configurationConnection();
        if (api.handleCookieResponse(packet)) ci.cancel();
    }

    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void cardboard$configurationPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (!((Object) this instanceof ServerConfigurationPacketListenerImpl config)
                || !(packet.payload() instanceof DiscardedPayload payload)) return;
        PacketUtils.ensureRunningOnSameThread(packet, config, this.server.packetProcessor());
        var api = ((ServerConfigurationPacketListenerImplBridge) config).cardboard$configurationConnection();
        String id = payload.id().toString();
        byte[] data = ((DiscardedPayloadBridge) (Object) payload).cardboard$data();
        try {
            if (id.equals("minecraft:register") || id.equals("minecraft:unregister")) {
                for (String channel : new String(data, StandardCharsets.US_ASCII).split("\u0000")) {
                    if (channel.isEmpty()) continue;
                    if (id.equals("minecraft:register")) api.addChannel(channel); else api.removeChannel(channel);
                }
            } else Bukkit.getMessenger().dispatchIncomingMessage(api, id, data);
        } catch (Exception invalidPayload) {
            config.disconnect(net.minecraft.network.chat.Component.literal("Invalid custom payload"));
        }
    }
}
