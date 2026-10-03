package org.cardboardpowered.mixin.world.level.block.entity;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.level.block.entity.CreakingHeartBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(CreakingHeartBlockEntity.class)
public interface CreakingHeartBlockEntityAccessor {

    @Invoker("clearCreakingInfo")
    void cardboard$clearCreakingInfo();

    @Invoker("getCreakingProtector")
    Optional<Creaking> cardboard$getCreakingProtector();

    @Invoker("spreadResin")
    Optional<BlockPos> cardboard$spreadResin(ServerLevel level);

    @Invoker("spawnProtector")
    static Creaking cardboard$spawnProtector(ServerLevel level, CreakingHeartBlockEntity blockEntity) {
        throw new AssertionError();
    }
}
