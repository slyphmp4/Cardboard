package org.cardboardpowered.mixin.world.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import org.bukkit.Bukkit;
import org.bukkit.event.entity.EntityDamageEvent;
import org.cardboardpowered.bridge.world.entity.EntityBridge;
import net.minecraft.world.entity.LivingEntity;
import org.cardboardpowered.bridge.world.level.LevelBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(LivingEntity.class)
public abstract class LivingEntityVoidDamageMixin {
    /** Preserve native damage handling after one cancellable, editable VOID event. */
    @WrapMethod(method = "hurtServer")
    private boolean cardboard$voidDamageEvent(ServerLevel level, DamageSource source, float amount,
                                               Operation<Boolean> original) {
        if (!source.is(DamageTypes.FELL_OUT_OF_WORLD)) return original.call(level, source, amount);
        var event = new EntityDamageEvent(((EntityBridge) this).getBukkitEntity(),
                EntityDamageEvent.DamageCause.VOID, amount);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled() && original.call(level, source, (float) event.getDamage());
    }

    @ModifyConstant(method = "onBelowWorld", constant = @Constant(floatValue = 4.0F))
    private float cardboard$voidDamageAmount(float original) {
        return ((LivingEntity) (Object) this).level() instanceof ServerLevel level
                ? ((LevelBridge) level).cardboard$getWorld().getVoidDamageAmount() : original;
    }
}
