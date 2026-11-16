package org.cardboardpowered.mixin.world.level.dimension.end;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.dimension.end.DragonRespawnStage;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.cardboardpowered.bridge.world.level.dimension.end.EnderDragonFightBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnderDragonFight.class)
public abstract class EnderDragonFightMixin implements EnderDragonFightBridge {
    @Shadow private DragonRespawnStage respawnStage;
    @Shadow public List<net.minecraft.world.entity.EntityReference<net.minecraft.world.entity.boss.enderdragon.EndCrystal>> respawnCrystals;
    @Unique private boolean cardboard$emptyCrystalRespawnAllowed;

    @Override
    public void cardboard$allowEmptyCrystalRespawn(boolean allowed) {
        this.cardboard$emptyCrystalRespawnAllowed = allowed;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void cardboard$restoreEmptyRespawn(net.minecraft.server.level.ServerLevel level, long seed, BlockPos origin, CallbackInfo ci) {
        // A pending sequence with no stored references is created by the API's
        // explicit empty-crystal overload; vanilla initiation requires crystals.
        this.cardboard$emptyCrystalRespawnAllowed = this.respawnStage != null && this.respawnCrystals.isEmpty();
    }

    @Inject(method = {"respawnDragon", "abortRespawnSequence"}, at = @At("HEAD"))
    private void cardboard$resetEmptyRespawn(CallbackInfo ci) {
        this.cardboard$emptyCrystalRespawnAllowed = false;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"))
    private boolean cardboard$hasMissingRespawnCrystals(List<?> crystals) {
        return crystals.isEmpty() && !this.cardboard$emptyCrystalRespawnAllowed;
    }
    @Override @Accessor("exitPortalLocation")
    public abstract BlockPos cardboard$getExitPortalLocation();
    @Override @Accessor("respawnStage")
    public abstract DragonRespawnStage cardboard$getRespawnStage();
    @Override @Accessor("gateways")
    public abstract List<Integer> cardboard$getGateways();
    @Override @Accessor("hasPreviouslyKilledDragon")
    public abstract void cardboard$setPreviouslyKilled(boolean killed);
    @Override @Invoker("setRespawnStage")
    public abstract void cardboard$setRespawnStage(DragonRespawnStage stage);
    @Override @Invoker("spawnNewGateway")
    public abstract void cardboard$spawnNewGateway();
}
