package org.cardboardpowered.impl.persistence;

import java.lang.ref.WeakReference;
import net.minecraft.world.entity.raid.Raids;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;

/** Stable per-raid storage, bound to its SavedData owner only when a world is known. */
public final class RaidPdcState {
    private WeakReference<Raids> owner = new WeakReference<>(null);
    private final CraftPersistentDataContainer container = new CraftPersistentDataContainer(RaidPdcCodec.registry(), this::changed);

    public CraftPersistentDataContainer container() { return this.container; }

    public void bind(Raids raids) {
        if (this.owner.get() != raids) this.owner = new WeakReference<>(raids);
    }

    private void changed() {
        Raids raids = this.owner.get();
        if (raids != null) raids.setDirty();
    }
}
