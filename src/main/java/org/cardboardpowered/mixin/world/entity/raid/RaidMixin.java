package org.cardboardpowered.mixin.world.entity.raid;

import net.minecraft.world.entity.raid.Raid;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.serialization.MapCodec;
import org.cardboardpowered.impl.persistence.RaidPdcCodec;
import org.cardboardpowered.impl.persistence.RaidPdcState;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import net.minecraft.world.entity.raid.Raider;
import org.cardboardpowered.bridge.world.entity.raid.RaidBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.Set;

@Mixin(Raid.class)
public class RaidMixin implements RaidBridge {
    @Unique private final RaidPdcState cardboard$pdc = new RaidPdcState();

    @Override
    public RaidPdcState cardboard$getPdcState() { return this.cardboard$pdc; }

    @ModifyExpressionValue(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/codecs/RecordCodecBuilder;mapCodec(Ljava/util/function/Function;)Lcom/mojang/serialization/MapCodec;"))
    private static MapCodec<Raid> cardboard$persistentRaidData(MapCodec<Raid> original) {
        return RaidPdcCodec.wrap(original);
    }

    @Shadow
    private Raid.RaidStatus status;

    @Shadow
    @Final
    private Map<Integer, Set<Raider>> groupRaiderMap;

    // CraftBukkit start
    @Override
    public boolean isInProgress() {
        return this.status == Raid.RaidStatus.ONGOING;
    }
    // CraftBukkit end

    // CraftBukkit start - a method to get all raiders
    @Override
    public java.util.Collection<Raider> getRaiders() {
        return this.groupRaiderMap.values().stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet());
    }
    // CraftBukkit end
}