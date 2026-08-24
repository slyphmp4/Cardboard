package org.cardboardpowered.mixin.world.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.bukkit.event.entity.EntityTargetEvent;
import org.cardboardpowered.bridge.world.entity.MobBridge;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.cardboardpowered.bridge.world.entity.EntityBridge;
import org.cardboardpowered.bridge.server.level.ServerLevelBridge;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobMixin extends LivingEntity implements MobBridge, EntityBridge {
    @Shadow
    @Nullable
    public LivingEntity target;

    protected MobMixin(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    public void setTargetCraftBukkit(@Nullable LivingEntity livingEntity, CallbackInfo ci) {
        // CraftBukkit start - fire event for the target Minecraft is actually trying to set.
        boolean set = this.cardboard$setTarget(livingEntity, EntityTargetEvent.TargetReason.UNKNOWN);
        if (set) { // Let the other mods call their @Inject if set is false.
            ci.cancel();
        }
    }

    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean cardboard$warnedUnknownTarget =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public boolean cardboard$setTarget(@Nullable LivingEntity newTarget, EntityTargetEvent.@Nullable TargetReason reason) {
        // Use the raw target field here. In 26.2 Mob#getTarget() can apply validity checks,
        // while Bukkit needs to compare against the actual target currently stored by Minecraft.
        LivingEntity oldTarget = this.target;
        if (oldTarget == newTarget) {
            return false;
        }

        if (reason != null) {
            if (reason == EntityTargetEvent.TargetReason.UNKNOWN && oldTarget != null && newTarget == null) {
                reason = oldTarget.isAlive()
                        ? EntityTargetEvent.TargetReason.FORGOT_TARGET
                        : EntityTargetEvent.TargetReason.TARGET_DIED;
            }
            if (reason == EntityTargetEvent.TargetReason.UNKNOWN && cardboard$warnedUnknownTarget.compareAndSet(false, true)) {
                // Some generic target acquisitions still do not expose a Bukkit reason.
                // Report only the first occurrence so useful diagnostics remain without log spam.
                ((ServerLevelBridge) this.level()).getCraftServer().getLogger().log(java.util.logging.Level.WARNING,
                        "Unknown target reason, please report on the issue tracker (further occurrences suppressed)", new Exception());
            }

            CraftLivingEntity craftTarget = null;
            if (newTarget != null) {
                craftTarget = (CraftLivingEntity) newTarget.getBukkitEntity();
            }

            org.bukkit.event.entity.EntityTargetLivingEntityEvent event =
                    new org.bukkit.event.entity.EntityTargetLivingEntityEvent(this.getBukkitEntity(), craftTarget, reason);
            if (!event.callEvent()) {
                return false;
            }

            if (event.getTarget() != null) {
                newTarget = ((CraftLivingEntity) event.getTarget()).getHandle();
            } else {
                newTarget = null;
            }
        }

        this.target = newTarget;
        return true;
        // CraftBukkit end
    }
}
