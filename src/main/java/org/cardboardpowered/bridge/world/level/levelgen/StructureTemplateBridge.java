package org.cardboardpowered.bridge.world.level.levelgen;

import java.util.List;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;

public interface StructureTemplateBridge {
    List<StructureTemplate.Palette> cardboard$getPalettes();
    List<StructureTemplate.StructureEntityInfo> cardboard$getEntities();
    CraftPersistentDataContainer cardboard$getPersistentDataContainer();
}
