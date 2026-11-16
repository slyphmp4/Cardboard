package org.cardboardpowered.bridge.world.level.dimension.end;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.dimension.end.DragonRespawnStage;

public interface EnderDragonFightBridge {
    BlockPos cardboard$getExitPortalLocation();
    DragonRespawnStage cardboard$getRespawnStage();
    List<Integer> cardboard$getGateways();
    void cardboard$setPreviouslyKilled(boolean killed);
    void cardboard$setRespawnStage(DragonRespawnStage stage);
    void cardboard$spawnNewGateway();
    void cardboard$allowEmptyCrystalRespawn(boolean allowed);
}
