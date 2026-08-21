package org.cardboardpowered.mixin.server.players;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.cardboardpowered.bridge.server.level.ServerPlayerBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Restores the Paper-style respawn lifecycle needed by Cardboard's internal
 * teleport path.
 *
 * Cardboard's existing respawn mixin routes the final respawn teleport through
 * internalTeleport(), which intentionally refuses to teleport a removed player.
 * The reuse hooks in PlayerListMixin are currently commented out, so the old
 * ServerPlayer can still be marked removed when that teleport is attempted.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListRespawnMixin {

    @WrapOperation(
            method = "respawn",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/level/ServerPlayer;"
            )
    )
    private ServerPlayer cardboard$reusePlayerInstance(
            MinecraftServer server,
            ServerLevel level,
            GameProfile profile,
            ClientInformation clientInformation,
            Operation<ServerPlayer> original,
            @Local(argsOnly = true) ServerPlayer originalPlayer
    ) {
        // A freshly constructed ServerPlayer would already belong to the respawn
        // level. Keep that invariant while preserving the existing player instance.
        ((ServerPlayerBridge) originalPlayer).spawnIn(level);
        return originalPlayer;
    }

    @WrapOperation(
            method = "respawn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;copyRespawnPosition(Lnet/minecraft/server/level/ServerPlayer;)V"
            )
    )
    private void cardboard$skipSelfRespawnPositionCopy(
            ServerPlayer instance,
            ServerPlayer source,
            Operation<Void> original
    ) {
        // The respawn player and source are the same object when reusing the player.
    }

    @Inject(
            method = "respawn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;snapTo(DDDFF)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void cardboard$prepareReusedPlayerForRespawn(
            ServerPlayer player,
            boolean keepInventory,
            Entity.RemovalReason removalReason,
            CallbackInfoReturnable<ServerPlayer> cir
    ) {
        /*
         * ServerPlayerBridge#reset() is declared by Cardboard but has no concrete
         * implementation on 26.2, so invoking it currently throws
         * AbstractMethodError. Do not call it here. The immediate lifecycle bug is
         * that internalTeleport() sees the reused entity as removed.
         */
        player.unsetRemoved();
        player.setShiftKeyDown(false);
    }
}
