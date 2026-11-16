package org.cardboardpowered.mixin.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.cardboardpowered.bridge.network.DiscardedPayloadBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DiscardedPayload.class)
public abstract class DiscardedPayloadMixin implements DiscardedPayloadBridge {
    @Unique private byte[] cardboard$data = new byte[0];
    @Override public byte[] cardboard$data() { return this.cardboard$data; }
    @Override public void cardboard$data(byte[] data) { this.cardboard$data = data; }

    @Inject(method = "codec", at = @At("HEAD"), cancellable = true)
    private static <B extends FriendlyByteBuf> void cardboard$preserveBytes(Identifier id, int maxSize,
            CallbackInfoReturnable<StreamCodec<B, DiscardedPayload>> cir) {
        cir.setReturnValue(StreamCodec.of((buffer, payload) -> {
            byte[] data = ((DiscardedPayloadBridge) (Object) payload).cardboard$data();
            if (data.length > maxSize) throw new IllegalArgumentException("Payload exceeds " + maxSize + " bytes");
            buffer.writeBytes(data);
        }, buffer -> {
            int size = buffer.readableBytes();
            if (size > maxSize) throw new IllegalArgumentException("Payload exceeds " + maxSize + " bytes");
            byte[] data = new byte[size];
            buffer.readBytes(data);
            return DiscardedPayloadBridge.create(id, data);
        }));
    }
}
