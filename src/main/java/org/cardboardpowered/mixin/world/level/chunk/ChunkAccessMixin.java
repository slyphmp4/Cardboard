package org.cardboardpowered.mixin.world.level.chunk;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.google.common.collect.Maps;
import org.cardboardpowered.bridge.world.level.chunk.ChunkAccessBridge;

@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin implements ChunkAccessBridge {

	@Shadow
	public final Map<BlockPos, BlockEntity> blockEntities = Maps.newHashMap();

	@Override
	public Map<BlockPos, BlockEntity> cardboard_getBlockEntities() {
		return blockEntities;
	}
	


}
