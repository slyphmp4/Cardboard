package org.cardboardpowered.bridge.world.entity.raid;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.raid.Raid;

public interface RaidsBridge {
    Int2ObjectMap<Raid> cardboard$getRaidMap();
}
