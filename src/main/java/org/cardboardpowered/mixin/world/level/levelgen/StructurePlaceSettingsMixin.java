package org.cardboardpowered.mixin.world.level.levelgen;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.bukkit.craftbukkit.util.CraftStructureTransformer;
import org.cardboardpowered.bridge.world.level.levelgen.StructurePlaceSettingsBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructurePlaceSettings.class)
public abstract class StructurePlaceSettingsMixin implements StructurePlaceSettingsBridge {
    @Unique private int cardboard$palette = -1;
    @Unique private CraftStructureTransformer cardboard$transformer;

    @Override public void cardboard$setPalette(int palette) { this.cardboard$palette = palette; }
    @Override public void cardboard$setTransformer(CraftStructureTransformer transformer) { this.cardboard$transformer = transformer; }
    @Override public CraftStructureTransformer cardboard$getTransformer() { return this.cardboard$transformer; }

    @Inject(method = "getRandomPalette", at = @At("HEAD"), cancellable = true)
    private void cardboard$explicitPalette(List<StructureTemplate.Palette> palettes, BlockPos pos, CallbackInfoReturnable<StructureTemplate.Palette> cir) {
        if (this.cardboard$palette >= 0 && !palettes.isEmpty()) {
            if (this.cardboard$palette >= palettes.size()) {
                throw new IllegalArgumentException("Palette index out of bounds: " + this.cardboard$palette + " of " + palettes.size());
            }
            cir.setReturnValue(palettes.get(this.cardboard$palette));
        }
    }
}
