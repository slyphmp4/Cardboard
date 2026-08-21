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
 * Restores the Paper respawn lifecycle when Cardboard reuses a player connection.
 *
 * Vanilla creates a new ServerPlayer during respawn, while Paper currently reuses
 * the existing instance. Cardboard already ports the Paper internal-teleport path,
 * but its reuse/reset hooks in PlayerListMixin are commented out. That leaves the
 * connection pointing at the removed player and causes internalTeleport to reject
 * the respawn with "Attempt to teleport removed player ... restricted".
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
        // level. Keep that invariant while preserving Paper's player-instance reuse.
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
        // The local respawn player and the original player are now the same object.
        // Paper skips this copy while it reuses the ServerPlayer instance.
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
        // Paper: the reused entity must be live again before internalTeleport runs.
        if (!keepInventory) {
            ((ServerPlayerBridge) player).reset();
        }
        player.unsetRemoved();
        player.setShiftKeyDown(false);
    }
}
