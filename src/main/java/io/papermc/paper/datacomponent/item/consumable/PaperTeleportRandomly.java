package io.papermc.paper.datacomponent.item.consumable;

import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;

public record PaperTeleportRandomly(
    TeleportRandomlyConsumeEffect internal
) implements ConsumeEffect.TeleportRandomly, PaperConsumableEffectImpl<net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect> {

    @Override
    public float diameter() {
        return this.internal.diameter();
    }

    @Override
    public boolean directionalParticles() {
        return this.internal.directionalParticles();
    }
    @Override
    public TeleportRandomlyConsumeEffect getHandle() { return this.internal; }
}
