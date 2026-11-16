package org.cardboardpowered.bridge.world.level.levelgen;

import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public interface StructureTemplateManagerBridge {
    Map<Identifier, Optional<StructureTemplate>> cardboard$getRepository();
    Optional<StructureTemplate> cardboard$tryLoad(Identifier id);
    StructureTemplate cardboard$read(CompoundTag tag);
}
