package org.cardboardpowered.mixin.world.entity.ai.behavior;

import java.util.Optional;
import java.util.function.Consumer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.PrepareRamNearestTarget;

import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.EntityTargetEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PrepareRamNearestTarget.class)
public class PrepareRamNearestTargetMixin {

    @Redirect(
            method = "start",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Optional;ifPresent(Ljava/util/function/Consumer;)V"
            )
    )
    private void cardboard$targetEvent(
            Optional<LivingEntity> optional,
            Consumer<LivingEntity> consumer,
            ServerLevel level,
            PathfinderMob body,
            long timestamp
    ) {
        optional.ifPresent(target -> {
            EntityTargetEvent event =
                    CraftEventFactory.callEntityTargetLivingEvent(
                            body,
                            target,
                            target instanceof ServerPlayer
                                    ? EntityTargetEvent.TargetReason.CLOSEST_PLAYER
                                    : EntityTargetEvent.TargetReason.CLOSEST_ENTITY
                    );

            if (event.isCancelled() || event.getTarget() == null) {
                return;
            }

            LivingEntity newTarget =
                    ((CraftLivingEntity) event.getTarget()).getHandle();

            consumer.accept(newTarget);
        });
    }
}