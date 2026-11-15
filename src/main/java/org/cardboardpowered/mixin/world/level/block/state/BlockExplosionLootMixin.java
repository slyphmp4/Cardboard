package org.cardboardpowered.mixin.world.level.block.state;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.cardboardpowered.bridge.world.level.ServerExplosionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep vanilla block loot generation, changing only its explosion-yield inputs. */
@Mixin(BlockBehaviour.class)
public abstract class BlockExplosionLootMixin {
    @ModifyExpressionValue(method = "onExplosionHit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Explosion;getBlockInteraction()Lnet/minecraft/world/level/Explosion$BlockInteraction;", ordinal = 1))
    private Explosion.BlockInteraction cardboard$applyYieldGate(Explosion.BlockInteraction original,
            @Local(argsOnly = true) Explosion explosion) {
        if (!(explosion instanceof ServerExplosionBridge bridge)) return original;
        return bridge.cardboard$getYield() < 1f ? Explosion.BlockInteraction.DESTROY_WITH_DECAY : Explosion.BlockInteraction.DESTROY;
    }

    @ModifyExpressionValue(method = "onExplosionHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Explosion;radius()F"))
    private float cardboard$applyYieldRadius(float original, @Local(argsOnly = true) Explosion explosion) {
        return explosion instanceof ServerExplosionBridge bridge ? 1f / bridge.cardboard$getYield() : original;
    }
}
