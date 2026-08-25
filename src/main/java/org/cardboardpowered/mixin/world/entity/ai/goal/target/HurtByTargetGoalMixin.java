package org.cardboardpowered.mixin.world.entity.ai.goal.target;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import org.bukkit.event.entity.EntityTargetEvent;
import org.cardboardpowered.bridge.world.entity.MobBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(HurtByTargetGoal.class)
public abstract class HurtByTargetGoalMixin {

    @Redirect(
            method = "start",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Mob;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"
            )
    )
    private void cardboard$setAttackerTargetReason(Mob mob, LivingEntity target) {
        ((MobBridge) mob).cardboard$setTarget(target, EntityTargetEvent.TargetReason.TARGET_ATTACKED_ENTITY);
    }

    @Redirect(
            method = "alertOther",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Mob;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"
            )
    )
    private void cardboard$setNearbyAttackerTargetReason(Mob mob, LivingEntity target) {
        ((MobBridge) mob).cardboard$setTarget(target, EntityTargetEvent.TargetReason.TARGET_ATTACKED_NEARBY_ENTITY);
    }
}
