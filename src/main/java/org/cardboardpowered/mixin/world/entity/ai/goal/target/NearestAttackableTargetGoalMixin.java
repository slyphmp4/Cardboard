package org.cardboardpowered.mixin.world.entity.ai.goal.target;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import org.bukkit.event.entity.EntityTargetEvent;
import org.cardboardpowered.bridge.world.entity.MobBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NearestAttackableTargetGoal.class)
public abstract class NearestAttackableTargetGoalMixin {

    @Redirect(
            method = "start",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Mob;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"
            )
    )
    private void cardboard$setNearestAttackableTargetReason(Mob mob, LivingEntity target) {
        EntityTargetEvent.TargetReason reason = target instanceof ServerPlayer
                ? EntityTargetEvent.TargetReason.CLOSEST_PLAYER
                : EntityTargetEvent.TargetReason.CLOSEST_ENTITY;

        ((MobBridge) mob).cardboard$setTarget(target, reason);
    }
}
