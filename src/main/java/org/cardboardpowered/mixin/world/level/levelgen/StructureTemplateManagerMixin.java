package org.cardboardpowered.mixin.world.level.levelgen;

import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.ResourceManagerTemplateSource;
import org.cardboardpowered.bridge.world.level.levelgen.StructureTemplateManagerBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateManagerMixin implements StructureTemplateManagerBridge {
    @Shadow @Final private ResourceManagerTemplateSource resourceManagerSource;

    @Override @Accessor("structureRepository")
    public abstract Map<Identifier, Optional<StructureTemplate>> cardboard$getRepository();

    @Override @Invoker("tryLoad")
    public abstract Optional<StructureTemplate> cardboard$tryLoad(Identifier id);

    @Override
    public StructureTemplate cardboard$read(CompoundTag tag) {
        return ((TemplateSourceAccessor) (Object) this.resourceManagerSource).cardboard$readTag(tag);
    }
}
