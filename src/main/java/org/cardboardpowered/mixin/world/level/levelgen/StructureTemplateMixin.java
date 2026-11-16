package org.cardboardpowered.mixin.world.level.levelgen;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.bukkit.craftbukkit.block.CraftBlockEntityState;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.block.CraftBlockStates;
import org.bukkit.craftbukkit.block.CraftLootable;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry;
import org.cardboardpowered.bridge.world.level.levelgen.StructurePlaceSettingsBridge;
import org.cardboardpowered.bridge.world.level.levelgen.StructureTemplateBridge;
import org.cardboardpowered.impl.world.CardboardStructureContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin implements StructureTemplateBridge {
    @Shadow @Final private List<StructureTemplate.Palette> palettes;
    @Shadow @Final private List<StructureTemplate.StructureEntityInfo> entityInfoList;
    @Unique private final CraftPersistentDataContainer cardboard$pdc = new CraftPersistentDataContainer(new CraftPersistentDataTypeRegistry());

    @Override public List<StructureTemplate.Palette> cardboard$getPalettes() { return this.palettes; }
    @Override public List<StructureTemplate.StructureEntityInfo> cardboard$getEntities() { return this.entityInfoList; }
    @Override public CraftPersistentDataContainer cardboard$getPersistentDataContainer() { return this.cardboard$pdc; }

    @Inject(method = "save", at = @At("RETURN"))
    private void cardboard$savePdc(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        if (!this.cardboard$pdc.isEmpty()) cir.getReturnValue().put("BukkitValues", this.cardboard$pdc.toTagCompound().copy());
        else cir.getReturnValue().remove("BukkitValues");
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void cardboard$loadPdc(HolderGetter<Block> blocks, CompoundTag tag, CallbackInfo ci) {
        this.cardboard$pdc.clear();
        tag.getCompound("BukkitValues").ifPresent(values -> this.cardboard$pdc.putAll(values.copy()));
    }

    @WrapMethod(method = "placeInWorld")
    private boolean cardboard$placementScope(ServerLevelAccessor level, BlockPos position, BlockPos reference,
            StructurePlaceSettings settings, RandomSource random, int flags, Operation<Boolean> original) {
        try (var scope = CardboardStructureContext.open(((StructurePlaceSettingsBridge) settings).cardboard$getTransformer())) {
            return original.call(level, position, reference, settings, random, flags);
        }
    }

    // Only the real template write is transformed. Barrier clearing and neighbour updates stay native.
    @WrapOperation(method = "placeInWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/ServerLevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 1))
    private boolean cardboard$transformBlock(ServerLevelAccessor level, BlockPos position, BlockState state, int flags,
            Operation<Boolean> original, @Local LocalRef<StructureTemplate.StructureBlockInfo> blockInfo,
            @Local LocalRef<BlockState> placedState,
            @Local(argsOnly = true) RandomSource random) {
        var transformer = CardboardStructureContext.current();
        if (transformer == null || !transformer.canTransformBlocks()) return original.call(level, position, state, flags);
        var nbt = blockInfo.get().nbt();
        CraftBlockState proposal = (CraftBlockState) CraftBlockStates.getBlockState(level.registryAccess(), position, state, nbt == null ? null : nbt.copy());
        if (proposal instanceof CraftLootable<?> lootable) lootable.setSeed(random.nextLong());
        CraftBlockState transformed = transformer.transformCraftState(proposal);
        var transformedNbt = transformed instanceof CraftBlockEntityState<?> tile ? tile.getSnapshotNBT() : null;
        placedState.set(transformed.getHandle());
        blockInfo.set(new StructureTemplate.StructureBlockInfo(position, transformed.getHandle(), transformedNbt));
        return original.call(level, position, transformed.getHandle(), flags);
    }

    @WrapOperation(method = "placeInWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextLong()J"))
    private long cardboard$keepTransformedLootSeed(RandomSource random, Operation<Long> original,
            @Local StructureTemplate.StructureBlockInfo blockInfo) {
        var transformer = CardboardStructureContext.current();
        return transformer != null && transformer.canTransformBlocks() && blockInfo.nbt() != null
                ? blockInfo.nbt().getLongOr("LootTableSeed", 0L) : original.call(random);
    }

    // The native entity addition is in a compiler-generated placeEntities lambda.
    @WrapOperation(method = "lambda$placeEntities$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/ServerLevelAccessor;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"))
    private static void cardboard$transformEntity(ServerLevelAccessor level, Entity entity, Operation<Void> original) {
        var transformer = CardboardStructureContext.current();
        if (!(level instanceof net.minecraft.server.level.ServerLevel) && (transformer == null || !transformer.canTransformEntities())) {
            original.call(level, entity);
            return;
        }
        org.cardboardpowered.impl.world.StructureEntitySpawner.add(level, entity, transformer);
    }
}
