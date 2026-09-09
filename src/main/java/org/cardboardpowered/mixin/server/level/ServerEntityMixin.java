package org.cardboardpowered.mixin.server.level;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;
import org.cardboardpowered.bridge.world.entity.EntityBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    @Shadow
    @Final
    private Entity entity;

    /**
     * CraftBukkit/Paper fires PlayerVelocityEvent when a player's marked
     * velocity is about to be synchronized. Clearing hurtMarked on
     * cancellation skips only the motion packet while allowing the rest of
     * ServerEntity#sendChanges to continue normally.
     */
    @Inject(method = "sendChanges", at = @At("HEAD"))
    private void cardboard$playerVelocityEvent(CallbackInfo ci) {
        if (!(this.entity instanceof ServerPlayer) || !this.entity.hurtMarked) {
            return;
        }

        Player player = (Player) ((EntityBridge) this.entity).getBukkitEntity();
        Vector velocity = player.getVelocity();
        PlayerVelocityEvent event = new PlayerVelocityEvent(player, velocity.clone());
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            this.entity.hurtMarked = false;
            return;
        }

        if (!velocity.equals(event.getVelocity())) {
            player.setVelocity(event.getVelocity());
        }
    }
}
