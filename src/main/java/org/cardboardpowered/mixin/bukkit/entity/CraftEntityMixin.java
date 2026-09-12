package org.cardboardpowered.mixin.bukkit.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftTNTPrimed;
import org.cardboardpowered.bridge.world.entity.EntityPersistenceBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftEntity.class, remap = false)
public abstract class CraftEntityMixin {

    @Shadow
    protected Entity entity;

    @Inject(method = "getEntity", at = @At("HEAD"), cancellable = true, remap = false)
    private static <T extends Entity> void cardboard$wrapModdedPrimedTnt(
            CraftServer server,
            T entity,
            CallbackInfoReturnable<CraftEntity> cir
    ) {
        if (!(entity instanceof PrimedTnt primedTnt)) {
            return;
        }

        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id != null && !"minecraft".equals(id.getNamespace())) {
            cir.setReturnValue(new CraftTNTPrimed(server, primedTnt));
        }
    }

    @Inject(method = "isPersistent", at = @At("HEAD"), cancellable = true, remap = false)
    private void cardboard$isPersistent(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(((EntityPersistenceBridge) this.entity).cardboard$isPersistent());
    }

    @Inject(method = "setPersistent", at = @At("HEAD"), cancellable = true, remap = false)
    private void cardboard$setPersistent(boolean persistent, CallbackInfo ci) {
        ((EntityPersistenceBridge) this.entity).cardboard$setPersistent(persistent);
        ci.cancel();
    }
}
