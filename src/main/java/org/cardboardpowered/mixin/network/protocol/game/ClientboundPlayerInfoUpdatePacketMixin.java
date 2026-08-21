package org.cardboardpowered.mixin.network.protocol.game;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marker mixin for Paper compatibility transformations applied to the player
 * info update packet by {@code PaperPlayerInfoUpdatePacketProcessor}.
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public abstract class ClientboundPlayerInfoUpdatePacketMixin {
}
