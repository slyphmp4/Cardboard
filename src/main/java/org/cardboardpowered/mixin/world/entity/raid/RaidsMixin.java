package org.cardboardpowered.mixin.world.entity.raid;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import org.cardboardpowered.bridge.world.entity.raid.RaidsBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Raids.class)
public interface RaidsMixin extends RaidsBridge {
    @Override
    @Accessor("raidMap")
    Int2ObjectMap<Raid> cardboard$getRaidMap();
}
