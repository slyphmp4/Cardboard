package org.cardboardpowered.mixin.world.level.chunk;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.cardboardpowered.bridge.world.level.chunk.LevelChunkSectionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin implements LevelChunkSectionBridge {
    @Shadow private PalettedContainerRO<Holder<Biome>> biomes;

    @Override
    public void cardboard$setBiome(int x, int y, int z, Holder<Biome> biome) {
        // Vanilla exposes a read-only container type. Preserve its other entries
        // if a section actually uses an immutable implementation.
        if (!(this.biomes instanceof PalettedContainer<Holder<Biome>>)) {
            this.biomes = this.biomes.copy();
        }
        ((PalettedContainer<Holder<Biome>>) this.biomes).set(x, y, z, biome);
    }
}
