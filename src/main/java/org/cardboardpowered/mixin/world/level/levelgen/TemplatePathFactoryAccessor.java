package org.cardboardpowered.mixin.world.level.levelgen;

import java.nio.file.Path;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplatePathFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(TemplatePathFactory.class)
public interface TemplatePathFactoryAccessor {
    @Invoker("createAndValidatePathToStructure")
    Path cardboard$structurePath(Identifier id, FileToIdConverter converter);
}
