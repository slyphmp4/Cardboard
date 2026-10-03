package org.cardboardpowered.mixin.world.entity.monster;

import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import org.cardboardpowered.bridge.world.entity.monster.SlimeBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({
        AbstractCubeMob.CubeMobAttackGoal.class,
        AbstractCubeMob.CubeMobFloatGoal.class,
        AbstractCubeMob.CubeMobRandomDirectionGoal.class,
        AbstractCubeMob.CubeMobKeepOnJumpingGoal.class
})
public abstract class CubeMobWanderGoalMixin {

    @Shadow
    @Final
    private AbstractCubeMob cubeMob;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void cardboard$respectWanderFlag(CallbackInfoReturnable<Boolean> cir) {
        if (!((SlimeBridge) this.cubeMob).cardboard$canWander()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canContinueToUse", at = @At("HEAD"), cancellable = true, require = 0)
    private void cardboard$respectWanderFlagWhileRunning(CallbackInfoReturnable<Boolean> cir) {
        if (!((SlimeBridge) this.cubeMob).cardboard$canWander()) {
            cir.setReturnValue(false);
        }
    }
}
