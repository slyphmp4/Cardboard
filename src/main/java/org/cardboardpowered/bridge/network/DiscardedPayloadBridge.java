package org.cardboardpowered.bridge.network;

import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;

/** Preserve unknown payload bytes for the Bukkit configuration messenger. */
public interface DiscardedPayloadBridge {
    byte[] cardboard$data();
    void cardboard$data(byte[] data);

    static DiscardedPayload create(Identifier id, byte[] data) {
        var payload = new DiscardedPayload(id);
        ((DiscardedPayloadBridge) (Object) payload).cardboard$data(data.clone());
        return payload;
    }
}
