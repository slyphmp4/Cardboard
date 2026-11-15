package org.cardboardpowered.mixin.world.entity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.cardboardpowered.bridge.world.level.LevelBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class EntityVoidDamageMixin {
    @ModifyExpressionValue(method = "checkBelowWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getY()D", ordinal = 0))
    private double cardboard$voidThreshold(double y) {
        if (!(((Entity) (Object) this).level() instanceof ServerLevel level)) return y;
        var world = ((LevelBridge) level).cardboard$getWorld();
        if (!world.isVoidDamageEnabled()) return Double.POSITIVE_INFINITY;
        // Native compares Y against minY - 64. Shift only that operand to retain its algorithm.
        return y - world.getVoidDamageMinBuildHeightOffset() - 64.0;
    }
}
