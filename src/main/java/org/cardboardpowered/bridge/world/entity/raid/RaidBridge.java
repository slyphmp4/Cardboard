package org.cardboardpowered.bridge.world.entity.raid;

import net.minecraft.world.entity.raid.Raider;

public interface RaidBridge {
    org.cardboardpowered.impl.persistence.RaidPdcState cardboard$getPdcState();

    boolean isInProgress();

    java.util.Collection<Raider> getRaiders();
}
