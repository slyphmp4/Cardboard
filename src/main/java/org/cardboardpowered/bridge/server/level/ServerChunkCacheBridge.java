package org.cardboardpowered.bridge.server.level;

public interface ServerChunkCacheBridge {

    boolean cardboard$getSpawnFriendlies();

    boolean cardboard$getSpawnEnemies();

    void cardboard$setSpawnSettings(boolean spawnEnemies, boolean spawnFriendlies);
}
