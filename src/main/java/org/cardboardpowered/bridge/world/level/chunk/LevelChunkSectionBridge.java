package org.cardboardpowered.bridge.world.level.chunk;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

public interface LevelChunkSectionBridge {
    void cardboard$setBiome(int x, int y, int z, Holder<Biome> biome);
}
