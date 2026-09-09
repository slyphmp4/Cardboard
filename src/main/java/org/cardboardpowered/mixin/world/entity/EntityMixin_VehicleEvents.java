package org.cardboardpowered.mixin.world.entity;

import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.cardboardpowered.bridge.world.entity.EntityBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin_VehicleEvents {

    @Inject(
            method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cardboard$vehicleEnterEvent(Entity entityToRide, boolean force, boolean sendEventAndTriggers, CallbackInfoReturnable<Boolean> cir) {
        if (!sendEventAndTriggers) {
            return;
        }

        Entity passenger = (Entity) (Object) this;
        if (passenger.getVehicle() == entityToRide) {
            return;
        }

        org.bukkit.entity.Entity bukkitPassenger = ((EntityBridge) passenger).getBukkitEntity();
        org.bukkit.entity.Entity bukkitVehicle = ((EntityBridge) entityToRide).getBukkitEntity();
        if (!(bukkitVehicle instanceof Vehicle vehicle) || !(bukkitPassenger instanceof LivingEntity)) {
            return;
        }

        VehicleEnterEvent event = new VehicleEnterEvent(vehicle, bukkitPassenger);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "removePassenger(Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cardboard$vehicleExitEvent(Entity passenger, CallbackInfo ci) {
        Entity vehicleEntity = (Entity) (Object) this;
        org.bukkit.entity.Entity bukkitPassenger = ((EntityBridge) passenger).getBukkitEntity();
        org.bukkit.entity.Entity bukkitVehicle = ((EntityBridge) vehicleEntity).getBukkitEntity();
        if (!(bukkitVehicle instanceof Vehicle vehicle) || !(bukkitPassenger instanceof LivingEntity living)) {
            return;
        }

        VehicleExitEvent event = new VehicleExitEvent(vehicle, living);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }
}
