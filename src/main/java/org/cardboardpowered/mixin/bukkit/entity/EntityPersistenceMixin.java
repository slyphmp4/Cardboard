package org.cardboardpowered.mixin.bukkit.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.cardboardpowered.bridge.world.entity.EntityPersistenceBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityPersistenceMixin implements EntityPersistenceBridge {

    @Unique
    private boolean cardboard$persistent = true;

    @Override
    public boolean cardboard$isPersistent() {
        return this.cardboard$persistent;
    }

    @Override
    public void cardboard$setPersistent(boolean persistent) {
        this.cardboard$persistent = persistent;
    }

    @Inject(
            method = "saveAsPassenger(Lnet/minecraft/world/level/storage/ValueOutput;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cardboard$skipNonPersistentSave(ValueOutput output, CallbackInfoReturnable<Boolean> cir) {
        if (!this.cardboard$persistent) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "saveWithoutId", at = @At("TAIL"))
    private void cardboard$writePersistentState(ValueOutput output, CallbackInfo ci) {
        if (!this.cardboard$persistent) {
            output.putBoolean("Bukkit.persist", false);
        }
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void cardboard$readPersistentState(ValueInput input, CallbackInfo ci) {
        this.cardboard$persistent = input.getBooleanOr("Bukkit.persist", true);
    }
}
