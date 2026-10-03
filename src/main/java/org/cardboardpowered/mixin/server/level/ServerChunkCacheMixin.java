package org.cardboardpowered.mixin.server.level;

import net.minecraft.server.level.ServerChunkCache;
import org.cardboardpowered.bridge.server.level.ServerChunkCacheBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

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

}
