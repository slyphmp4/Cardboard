package org.cardboardpowered.mixin.world.entity.monster;

import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import org.cardboardpowered.bridge.world.entity.monster.SlimeBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractCubeMob.CubeMobRandomDirectionGoal.class)
public abstract class CubeMobRandomDirectionGoalMixin {

    @Shadow
    @Final
    private AbstractCubeMob cubeMob;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void cardboard$respectWanderFlag(CallbackInfoReturnable<Boolean> cir) {
        if (!((SlimeBridge) this.cubeMob).cardboard$canWander()) {
            cir.setReturnValue(false);
        }
    }
}
