package org.cardboardpowered.mixin.server.level;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.NaturalSpawner;
import org.cardboardpowered.bridge.server.level.ServerChunkCacheBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin implements ServerChunkCacheBridge {

    @Shadow
    private boolean spawnEnemies;

    private boolean cardboard$spawnFriendlies = true;

    @Override
    public boolean cardboard$getSpawnFriendlies() {
        return this.cardboard$spawnFriendlies;
    }

    @Override
    public boolean cardboard$getSpawnEnemies() {
        return this.spawnEnemies;
    }

    @Override
    public void cardboard$setSpawnSettings(boolean spawnEnemies, boolean spawnFriendlies) {
        this.spawnEnemies = spawnEnemies;
        this.cardboard$spawnFriendlies = spawnFriendlies;
    }

    @ModifyArg(
            method = "tickChunks",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/NaturalSpawner;getFilteredSpawningCategories(Lnet/minecraft/world/level/NaturalSpawner$SpawnState;ZZZ)Ljava/util/List;"
            ),
            index = 1
    )
    private boolean cardboard$useFriendlySpawnSetting(boolean vanillaAlwaysTrue) {
        return this.cardboard$spawnFriendlies;
    }
}
