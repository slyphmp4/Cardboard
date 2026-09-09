package org.cardboardpowered.mixin.world.entity.player;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.bukkit.Bukkit;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.cardboardpowered.bridge.world.entity.player.PlayerBridge;
import org.cardboardpowered.bridge.world.entity.EntityBridge;
import org.cardboardpowered.bridge.world.entity.LivingEntityBridge;
import org.cardboardpowered.mixin.world.entity.LivingEntityMixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerMixin extends LivingEntityMixin implements EntityBridge, LivingEntityBridge, PlayerBridge {
    @Shadow
    public abstract Inventory getInventory();

    @Shadow
    public AbstractContainerMenu containerMenu;

    @Inject(method = "startFallFlying", at = @At("HEAD"), cancellable = true)
    private void cardboard$toggleGlideStart(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (self.isFallFlying()) {
            return;
        }

        EntityToggleGlideEvent event = new EntityToggleGlideEvent(
                (org.bukkit.entity.LivingEntity) this.getBukkitEntity(),
                true
        );
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Override
    public org.bukkit.craftbukkit.entity.CraftHumanEntity getBukkitEntity() {
        return (org.bukkit.craftbukkit.entity.CraftHumanEntity) super.getBukkitEntity();
    }
}
