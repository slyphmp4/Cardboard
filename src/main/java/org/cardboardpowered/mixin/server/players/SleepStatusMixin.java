package org.cardboardpowered.mixin.server.players;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.cardboardpowered.bridge.server.level.PlayerPersonalStateBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SleepStatus.class)
public abstract class SleepStatusMixin {
    @Shadow private int activePlayers;
    @Shadow private int sleepingPlayers;
    @Shadow public abstract int sleepersNeeded(int percentage);

    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void cardboard$countIgnored(List<ServerPlayer> players, CallbackInfoReturnable<Boolean> cir) {
        int oldActive = this.activePlayers, oldSleeping = this.sleepingPlayers;
        this.activePlayers = 0;
        this.sleepingPlayers = 0;
        boolean anyRealSleep = false;
        for (ServerPlayer player : players) {
            if (!player.isSpectator()) {
                this.activePlayers++;
                boolean realSleep = player.isSleeping();
                if (realSleep || ((PlayerPersonalStateBridge) player).cardboard$isSleepingIgnored()) this.sleepingPlayers++;
                anyRealSleep |= realSleep;
            }
        }
        cir.setReturnValue(anyRealSleep && (oldSleeping > 0 || this.sleepingPlayers > 0)
                && (oldActive != this.activePlayers || oldSleeping != this.sleepingPlayers));
    }

    @Inject(method = "areEnoughDeepSleeping", at = @At("HEAD"), cancellable = true)
    private void cardboard$requireRealDeepSleep(int percentage, List<ServerPlayer> players, CallbackInfoReturnable<Boolean> cir) {
        int deepSleepers = 0;
        boolean anyRealDeepSleep = false;
        for (ServerPlayer player : players) {
            boolean deep = player.isSleepingLongEnough();
            if (deep || ((PlayerPersonalStateBridge) player).cardboard$isSleepingIgnored()) deepSleepers++;
            anyRealDeepSleep |= deep;
        }
        cir.setReturnValue(anyRealDeepSleep && deepSleepers >= this.sleepersNeeded(percentage));
    }
}
