package org.cardboardpowered.mixin.world.level;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.cardboardpowered.bridge.server.level.ServerChunkCacheBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {

    @Inject(method = "spawnCategoryForChunk", at = @At("HEAD"), cancellable = true)
    private static void cardboard$respectFriendlySpawnFlag(
            MobCategory category,
            ServerLevel level,
            LevelChunk chunk,
            NaturalSpawner.SpawnPredicate extraTest,
            NaturalSpawner.AfterSpawnCallback spawnCallback,
            CallbackInfo ci) {
        if (category.isFriendly()
                && !((ServerChunkCacheBridge) (Object) level.getChunkSource())
                        .cardboard$getSpawnFriendlies()) {
            ci.cancel();
        }
    }
}
