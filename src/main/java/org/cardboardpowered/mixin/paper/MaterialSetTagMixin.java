package org.cardboardpowered.mixin.paper;

import com.destroystokyo.paper.MaterialSetTag;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.cardboardpowered.bridge.bukkit.BukkitMaterialBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Paper's built-in MaterialTags scoped to Bukkit/Paper materials.
 *
 * <p>Cardboard injects Fabric mod materials into the Bukkit Material enum.
 * Paper's MaterialSetTag builds a number of vanilla compatibility tags by
 * scanning Material.values() and matching enum names using startsWith,
 * endsWith and contains.</p>
 *
 * <p>Without this filter, modded materials can accidentally become members
 * of Paper's vanilla compatibility tags. For example, a modded material
 * ending in POTATO would expand MaterialTags.POTATOES and make Paper's
 * ensureSize check fail during class initialization.</p>
 */
@Mixin(value = MaterialSetTag.class, remap = false)
public abstract class MaterialSetTagMixin {

    @Inject(
            method = "getAllPossibleValues",
            at = @At("RETURN"),
            cancellable = true,
            remap = false
    )
    private void cardboard$excludeModdedMaterialsFromPaperTags(
            final CallbackInfoReturnable<Set<Material>> cir
    ) {
        final Set<Material> original = cir.getReturnValue();

        if (original == null || original.isEmpty()) {
            return;
        }

        final Set<Material> filtered = new HashSet<>(original);

        filtered.removeIf(material ->
                (Object) material instanceof BukkitMaterialBridge bridge
                        && bridge.isModded()
        );

        if (filtered.size() != original.size()) {
            cir.setReturnValue(filtered);
        }
    }
}
