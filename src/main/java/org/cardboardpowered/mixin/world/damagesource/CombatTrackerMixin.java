package org.cardboardpowered.mixin.world.damagesource;

import java.util.List;
import net.minecraft.world.damagesource.CombatEntry;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.entity.LivingEntity;
import org.cardboardpowered.bridge.world.damagesource.CombatTrackerBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(CombatTracker.class)
public abstract class CombatTrackerMixin implements CombatTrackerBridge {

    @Shadow @Final private List<CombatEntry> entries;
    @Shadow @Final private LivingEntity mob;
    @Shadow private int lastDamageTime;
    @Shadow private int combatStartTime;
    @Shadow private int combatEndTime;
    @Shadow private boolean inCombat;
    @Shadow private boolean takingDamage;

    @Override
    public void cardboard$recordDamageAndCheckCombatState(final CombatEntry entry) {
        this.entries.add(entry);
        this.lastDamageTime = this.mob.tickCount;
        this.takingDamage = true;

        if (!this.inCombat && this.mob.isAlive() && entry.source().getEntity() instanceof LivingEntity) {
            this.inCombat = true;
            this.combatStartTime = this.mob.tickCount;
            this.combatEndTime = this.combatStartTime;
            this.mob.onEnterCombat();
        }
    }

    @Override
    public void cardboard$resetCombatState() {
        final boolean wasInCombat = this.inCombat;
        this.takingDamage = false;
        this.inCombat = false;
        this.combatEndTime = this.mob.tickCount;
        if (wasInCombat) {
            this.mob.onLeaveCombat();
        }
        this.entries.clear();
    }
}
