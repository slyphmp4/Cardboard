package org.cardboardpowered.bridge.world.entity;

import net.minecraft.world.entity.LivingEntity;
import org.bukkit.event.entity.EntityTargetEvent;
import org.jspecify.annotations.Nullable;

public interface MobBridge {
    boolean cardboard$setTarget(@Nullable LivingEntity target, EntityTargetEvent.@Nullable TargetReason reason);

    /**
     * Calls the mob's own setTarget path while carrying a Bukkit target reason.
     * This preserves subclass target side effects while avoiding UNKNOWN events.
     */
    void cardboard$setTargetWithReason(@Nullable LivingEntity target, EntityTargetEvent.TargetReason reason);
}
