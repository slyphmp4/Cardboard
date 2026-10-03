package org.cardboardpowered.bridge.world.damagesource;

public interface CombatTrackerBridge {

    void cardboard$recordDamageAndCheckCombatState(net.minecraft.world.damagesource.CombatEntry entry);

    void cardboard$resetCombatState();
}
