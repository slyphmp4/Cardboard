package org.bukkit.craftbukkit.util;

import java.util.Collection;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import org.bukkit.block.BlockState;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.generator.CraftLimitedRegion;
import org.bukkit.util.BlockTransformer;
import org.bukkit.util.BlockTransformer.TransformationState;
import org.bukkit.util.EntityTransformer;

public class CraftStructureTransformer {

    private static class CraftTransformationState implements TransformationState {

        private final BlockState original;
        private final BlockState world;

        private BlockState originalCopy;
        private BlockState worldCopy;

        private CraftTransformationState(BlockState original, BlockState world) {
            this.original = original;
            this.world = world;
        }

        @Override
        public BlockState getOriginal() {
            if (this.originalCopy != null) {
                return this.originalCopy;
            }
            return this.originalCopy = this.original.copy();
        }

        @Override
        public BlockState getWorld() {
            if (this.worldCopy != null) {
                return this.worldCopy;
            }
            return this.worldCopy = this.world.copy();
        }

        private void destroyCopies() {
            this.originalCopy = null;
            this.worldCopy = null;
        }

    }

    private CraftLimitedRegion limitedRegion;
    private BlockTransformer[] blockTransformers;
    private EntityTransformer[] entityTransformers;

    public CraftStructureTransformer(WorldGenLevel level, ChunkPos center, Collection<BlockTransformer> blockTransformers, Collection<EntityTransformer> entityTransformers) {
        this.blockTransformers = blockTransformers.toArray(BlockTransformer[]::new);
        this.entityTransformers = entityTransformers.toArray(EntityTransformer[]::new);
        this.limitedRegion = new CraftLimitedRegion(level, center);
    }

    public boolean transformEntity(Entity entity) {
        EntityTransformer[] transformers = this.entityTransformers;
        if (transformers == null || transformers.length == 0) {
            return true;
        }
        CraftLimitedRegion region = this.limitedRegion;
        if (region == null) {
            return true;
        }
        ((org.cardboardpowered.bridge.world.entity.EntityBridge) entity).cardboard$setGeneration(true);
        CraftEntity craftEntity = ((org.cardboardpowered.bridge.world.entity.EntityBridge) entity).getBukkitEntity();
        int x = entity.getBlockX();
        int y = entity.getBlockY();
        int z = entity.getBlockZ();
        boolean allowedToSpawn = true;
        for (EntityTransformer transformer : transformers) {
            allowedToSpawn = transformer.transform(region, x, y, z, craftEntity, allowedToSpawn);
        }
        return allowedToSpawn;
    }

    public boolean canTransformBlocks() {
        return this.blockTransformers != null && this.blockTransformers.length != 0 && this.limitedRegion != null;
    }

    public boolean canTransformEntities() {
        return this.entityTransformers != null && this.entityTransformers.length != 0 && this.limitedRegion != null;
    }

    public CraftBlockState transformCraftState(CraftBlockState originalState) {
        CraftLimitedRegion region = this.limitedRegion;
        if (region == null) {
            return originalState;
        }
        originalState.setWorldHandle(region.getHandle());
        BlockPos position = originalState.getPosition();
        BlockState blockState = originalState.copy();
        CraftTransformationState transformationState = new CraftTransformationState(originalState, region.getBlockState(position.getX(), position.getY(), position.getZ()));
        for (BlockTransformer transformer : this.blockTransformers) {
            blockState = Objects.requireNonNull(transformer.transform(region, position.getX(), position.getY(), position.getZ(), blockState, transformationState), "BlockState can't be null");
            transformationState.destroyCopies();
        }
        return (CraftBlockState) blockState;
    }

    public void discard() {
        this.discard(true);
    }

    public void discard(boolean commit) {
        try {
            if (commit) this.limitedRegion.saveEntities();
        } finally {
            this.limitedRegion.breakLink();
            this.limitedRegion = null;
            this.blockTransformers = null;
            this.entityTransformers = null;
        }
    }
}
