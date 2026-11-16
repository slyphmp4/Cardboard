package org.bukkit.craftbukkit;

import net.minecraft.world.level.Explosion;
import org.bukkit.ExplosionResult;

public final class CraftExplosionResult {
    private CraftExplosionResult() {}

    public static ExplosionResult toExplosionResult(Explosion.BlockInteraction interaction) {
        return switch (interaction) {
            case KEEP -> ExplosionResult.KEEP;
            case DESTROY -> ExplosionResult.DESTROY;
            case DESTROY_WITH_DECAY -> ExplosionResult.DESTROY_WITH_DECAY;
            case TRIGGER_BLOCK -> ExplosionResult.TRIGGER_BLOCK;
        };
    }
}
