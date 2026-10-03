package org.cardboardpowered.mixin.world.item.alchemy;

import io.papermc.paper.potion.PaperPotionMix;
import io.papermc.paper.potion.PotionMix;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionBrewing;
import org.bukkit.NamespacedKey;
import org.cardboardpowered.bridge.world.item.alchemy.PotionBrewingBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PotionBrewing.class)
public class PotionBrewingMixin implements PotionBrewingBridge {

    @Unique
    private final Object2ObjectLinkedOpenHashMap<NamespacedKey, PaperPotionMix> cardboard$customMixes =
            new Object2ObjectLinkedOpenHashMap<>();

    @Inject(method = "isIngredient", at = @At("HEAD"), cancellable = true)
    private void cardboard$isCustomIngredient(ItemStack ingredient, CallbackInfoReturnable<Boolean> cir) {
        for (PaperPotionMix mix : this.cardboard$customMixes.values()) {
            if (mix.ingredient().test(ingredient)) {
                cir.setReturnValue(true);
                return;
            }
        }
    }

    @Inject(method = "hasMix", at = @At("HEAD"), cancellable = true)
    private void cardboard$hasCustomMix(ItemStack source, ItemStack ingredient, CallbackInfoReturnable<Boolean> cir) {
        for (PaperPotionMix mix : this.cardboard$customMixes.values()) {
            if (mix.input().test(source) && mix.ingredient().test(ingredient)) {
                cir.setReturnValue(true);
                return;
            }
        }
    }

    @Inject(method = "mix", at = @At("HEAD"), cancellable = true)
    private void cardboard$mixCustom(ItemStack ingredient, ItemStack source, CallbackInfoReturnable<ItemStack> cir) {
        for (PaperPotionMix mix : this.cardboard$customMixes.values()) {
            if (mix.input().test(source) && mix.ingredient().test(ingredient)) {
                cir.setReturnValue(mix.result().copy());
                return;
            }
        }
    }

    @Override
    public void cardboard$addPotionMix(PotionMix mix) {
        if (this.cardboard$customMixes.containsKey(mix.getKey())) {
            throw new IllegalArgumentException("Duplicate recipe ignored with ID " + mix.getKey());
        }
        this.cardboard$customMixes.putAndMoveToFirst(mix.getKey(), new PaperPotionMix(mix));
    }

    @Override
    public boolean cardboard$removePotionMix(NamespacedKey key) {
        return this.cardboard$customMixes.remove(key) != null;
    }

    @Override
    public void cardboard$clearPotionMixes() {
        this.cardboard$customMixes.clear();
    }
}
