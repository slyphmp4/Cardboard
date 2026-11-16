package org.cardboardpowered.impl.world;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.bukkit.craftbukkit.block.CraftBlock;

/** Apply the Bukkit block predicate to both solid and fluid collision shapes. */
final class CardboardRayTraceContext extends ClipContext {
    private final LevelAccessor level;
    private final Predicate<? super org.bukkit.block.Block> filter;
    private BlockPos lastPosition;
    private boolean lastAllowed;

    CardboardRayTraceContext(LevelAccessor level, Vec3 from, Vec3 to, ClipContext.Block blocks,
            ClipContext.Fluid fluids, Predicate<? super org.bukkit.block.Block> filter) {
        super(from, to, blocks, fluids, CollisionContext.empty());
        this.level = level;
        this.filter = filter;
    }

    private boolean allows(BlockPos position) {
        if (this.filter == null) return true;
        // Vanilla asks for solid and fluid shapes separately at the same position.
        // Retain an immutable key because traversal reuses a mutable BlockPos.
        if (!position.equals(this.lastPosition)) {
            this.lastPosition = position.immutable();
            this.lastAllowed = this.filter.test(CraftBlock.at(this.level, this.lastPosition));
        }
        return this.lastAllowed;
    }

    @Override
    public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos position) {
        return this.allows(position) ? super.getBlockShape(state, level, position) : Shapes.empty();
    }

    @Override
    public VoxelShape getFluidShape(FluidState state, BlockGetter level, BlockPos position) {
        return this.allows(position) ? super.getFluidShape(state, level, position) : Shapes.empty();
    }
}
