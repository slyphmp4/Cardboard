package org.cardboardpowered.mixin.server.level;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.cardboardpowered.bridge.world.level.ServerExplosionBridge;
import org.cardboardpowered.impl.world.CardboardExplosionContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelExplosionMixin {
    @WrapOperation(method = "explode", at = @At(value = "NEW",
            target = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;Lnet/minecraft/world/phys/Vec3;FZLnet/minecraft/world/level/Explosion$BlockInteraction;)Lnet/minecraft/world/level/ServerExplosion;"))
    private ServerExplosion cardboard$configureApiExplosion(ServerLevel level, Entity source, DamageSource damageSource,
            ExplosionDamageCalculator calculator, Vec3 center, float radius, boolean fire,
            Explosion.BlockInteraction interaction, Operation<ServerExplosion> original) {
        var request = CardboardExplosionContext.claim(level);
        if (request != null && request.noDropDecay()) interaction = Explosion.BlockInteraction.DESTROY;
        ServerExplosion explosion = original.call(level, source, damageSource, calculator, center, radius, fire, interaction);
        if (request != null) request.attach(explosion);
        return explosion;
    }

    @Inject(method = "explode",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ServerExplosion;explode()I", shift = At.Shift.AFTER),
            cancellable = true)
    private void cardboard$stopCancelledExplosionPackets(CallbackInfo ci, @Local ServerExplosion explosion) {
        if (((ServerExplosionBridge) (Object) explosion).cardboard$wasCancelled()) ci.cancel();
    }
}
