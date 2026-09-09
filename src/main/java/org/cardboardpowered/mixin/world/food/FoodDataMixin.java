package org.cardboardpowered.mixin.world.food;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.cardboardpowered.bridge.server.level.ServerPlayerBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FoodData.class)
@SuppressWarnings({"deprecation", "removal"})
public abstract class FoodDataMixin {

    @Shadow
    private int foodLevel;

    /**
     * Vanilla's starvation/exhaustion branch computes the next food level with
     * Math.max(int, int) immediately before assigning it. Replacing that value
     * lets Bukkit cancel or alter the level before the mutation is committed.
     */
    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I")
    )
    private int cardboard$foodLevelChange(int left, int right, ServerPlayer player) {
        int proposed = Math.max(left, right);
        HumanEntity bukkitPlayer = (HumanEntity) ((ServerPlayerBridge) player).getBukkitEntity();
        FoodLevelChangeEvent event = new FoodLevelChangeEvent(bukkitPlayer, proposed);
        Bukkit.getPluginManager().callEvent(event);
        return event.isCancelled() ? this.foodLevel : event.getFoodLevel();
    }
}
