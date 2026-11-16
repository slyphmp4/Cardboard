package org.cardboardpowered.mixin.server.level;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.command.CommandSender;
import org.cardboardpowered.bridge.commands.CommandSourceBridge;
import org.cardboardpowered.bridge.server.level.ServerPlayerBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net/minecraft/server/level/ServerPlayer$3")
public class ServerPlayerEntityCommandSenderMixin implements CommandSourceBridge {
    @Shadow @Final private ServerPlayer this$0;

    @Override public CommandSender getBukkitSender(CommandSourceStack source) {
        return ((ServerPlayerBridge) this.this$0).getBukkitEntity();
    }
}
