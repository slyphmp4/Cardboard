package org.cardboardpowered.mixin.network.protocol.game;

import com.mojang.brigadier.arguments.ArgumentType;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.cardboardpowered.impl.command.ClientCommandArgumentSerialization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientboundCommandsPacket.class)
public abstract class ClientboundCommandsPacketMixin {
    @Redirect(method = "createEntry", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/commands/synchronization/ArgumentTypeInfos;unpack(Lcom/mojang/brigadier/arguments/ArgumentType;)Lnet/minecraft/commands/synchronization/ArgumentTypeInfo$Template;"))
    private static ArgumentTypeInfo.Template<?> cardboard$serializePluginArgument(ArgumentType<?> type) {
        return ClientCommandArgumentSerialization.forClient(type);
    }
}
