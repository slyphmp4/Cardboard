package org.cardboardpowered.mixin.world.level.levelgen;

import java.io.IOException;
import java.io.InputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplateSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(TemplateSource.class)
public interface TemplateSourceAccessor {
    @Invoker("readStructure")
    StructureTemplate cardboard$readTag(CompoundTag tag);

    @Invoker("readStructure")
    static CompoundTag cardboard$readStream(InputStream stream) throws IOException {
        throw new AssertionError("Mixin invoker was not applied");
    }
}
