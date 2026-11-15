package org.cardboardpowered.bridge.world.level.levelgen;

import org.bukkit.craftbukkit.util.CraftStructureTransformer;

public interface StructurePlaceSettingsBridge {
    void cardboard$setPalette(int palette);
    void cardboard$setTransformer(CraftStructureTransformer transformer);
    CraftStructureTransformer cardboard$getTransformer();
}
