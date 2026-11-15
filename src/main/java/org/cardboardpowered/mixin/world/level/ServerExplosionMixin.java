package org.cardboardpowered.mixin.world.level;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftExplosionResult;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.cardboardpowered.bridge.world.entity.EntityBridge;
import org.cardboardpowered.bridge.world.level.ServerExplosionBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin implements ServerExplosionBridge {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Vec3 center;
    @Shadow @Final private Entity source;
    @Shadow @Final private float radius;
    @Shadow @Final private Explosion.BlockInteraction blockInteraction;

    @Unique private boolean cardboard$cancelled;
    @Unique private boolean cardboard$excludeSource = true;
    @Unique private float cardboard$yield;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cardboard$initialYield(CallbackInfo ci) {
        float yield = this.blockInteraction == Explosion.BlockInteraction.DESTROY_WITH_DECAY ? 1f / this.radius : 1f;
        this.cardboard$yield = Float.isFinite(yield) ? yield : 0f;
    }

    @Override public boolean cardboard$wasCancelled() { return this.cardboard$cancelled; }
    @Override public float cardboard$getYield() { return this.cardboard$yield; }
    @Override public void cardboard$setExcludeSource(boolean exclude) { this.cardboard$excludeSource = exclude; }

    @ModifyArg(method = "hurtEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"), index = 0)
    private Entity cardboard$selectExcludedSource(Entity original) {
        return this.cardboard$excludeSource ? original : null;
    }

    @Inject(method = "interactWithBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Util;shuffle(Ljava/util/List;Lnet/minecraft/util/RandomSource;)V", shift = At.Shift.AFTER),
            cancellable = true)
    private void cardboard$blockExplosionEvent(List<BlockPos> targetBlocks, CallbackInfo ci) {
        List<org.bukkit.block.Block> blocks = new ArrayList<>();
        for (int i = targetBlocks.size() - 1; i >= 0; i--) {
            var block = CraftBlock.at(this.level, targetBlocks.get(i));
            if (!block.getType().isAir()) blocks.add(block);
        }
        List<org.bukkit.block.Block> selected;
        var result = CraftExplosionResult.toExplosionResult(((ServerExplosion) (Object) this).getBlockInteraction());
        if (this.source != null) {
            var entity = ((EntityBridge) this.source).getBukkitEntity();
            var event = new EntityExplodeEvent(entity, entity.getLocation(), blocks, this.cardboard$yield, result);
            Bukkit.getPluginManager().callEvent(event);
            this.cardboard$cancelled = event.isCancelled();
            this.cardboard$yield = event.getYield();
            selected = event.blockList();
        } else {
            var block = CraftBlock.at(this.level, BlockPos.containing(this.center));
            var event = new BlockExplodeEvent(block, block.getState(), blocks, this.cardboard$yield, result);
            Bukkit.getPluginManager().callEvent(event);
            this.cardboard$cancelled = event.isCancelled();
            this.cardboard$yield = event.getYield();
            selected = event.blockList();
        }
        targetBlocks.clear();
        for (var block : selected) targetBlocks.add(new BlockPos(block.getX(), block.getY(), block.getZ()));
        // Native entity damage already happened. Fire retains its separate ignite event.
        if (this.cardboard$cancelled) ci.cancel();
    }

    @WrapOperation(method = "interactWithBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;onExplosionHit(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Explosion;Ljava/util/function/BiConsumer;)V"))
    private void cardboard$chainPrimeEvent(BlockState state, ServerLevel level, BlockPos pos, Explosion explosion,
            BiConsumer<ItemStack, BlockPos> onHit, Operation<Void> original) {
        if (level.getGameRules().get(GameRules.TNT_EXPLODES) && state.getBlock() instanceof TntBlock) {
            var entity = this.source == null ? null : ((EntityBridge) this.source).getBukkitEntity();
            var sourceBlock = this.source == null ? CraftBlock.at(level, BlockPos.containing(this.center)) : null;
            var event = new TNTPrimeEvent(CraftBlock.at(level, pos), TNTPrimeEvent.PrimeCause.EXPLOSION, entity, sourceBlock);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                level.sendBlockUpdated(pos, Blocks.AIR.defaultBlockState(), state, Block.UPDATE_ALL);
                return;
            }
        }
        original.call(state, level, pos, explosion, onHit);
    }

    @WrapOperation(method = "createFire", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean cardboard$igniteEvent(ServerLevel level, BlockPos pos, BlockState state, Operation<Boolean> original) {
        var explosion = (ServerExplosion) (Object) this;
        return !CraftEventFactory.callBlockIgniteEvent(level, pos.getX(), pos.getY(), pos.getZ(), explosion).isCancelled()
                && original.call(level, pos, state);
    }
}
