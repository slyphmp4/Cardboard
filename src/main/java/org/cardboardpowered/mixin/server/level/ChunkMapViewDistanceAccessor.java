package org.cardboardpowered.mixin.server.level;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapViewDistanceAccessor {
    @Accessor("serverViewDistance")
    int cardboard$getViewDistance();

    @Invoker("setServerViewDistance")
    void cardboard$setViewDistance(int distance);
}
