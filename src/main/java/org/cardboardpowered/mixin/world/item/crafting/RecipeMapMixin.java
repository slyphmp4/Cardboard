package org.cardboardpowered.mixin.world.item.crafting;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.*;
import org.cardboardpowered.bridge.world.item.crafting.RecipeMapBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Mixin(RecipeMap.class)
public abstract class RecipeMapMixin implements RecipeMapBridge {
    @Shadow
    @Final
    public Multimap<RecipeType<?>, RecipeHolder<?>> byType;

    @Shadow
    @Final
    public Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> byKey;

    @Shadow
    public abstract <I extends RecipeInput, T extends Recipe<I>> Collection<RecipeHolder<T>> byType(RecipeType<T> recipeType);

    /*
    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static void createCraftBukkit(Iterable<RecipeHolder<?>> recipes, CallbackInfoReturnable<RecipeMap> cir) {
        ImmutableMultimap.Builder<RecipeType<?>, RecipeHolder<?>> builder = ImmutableMultimap.builder();
        com.google.common.collect.ImmutableMap.Builder<ResourceKey<Recipe<?>>, RecipeHolder<?>> builder1 = ImmutableMap.builder();

        for (RecipeHolder<?> recipeHolder : recipes) {
            builder.put(recipeHolder.value().getType(), recipeHolder);
            builder1.put(recipeHolder.id(), recipeHolder);
        }

        // CraftBukkit start - mutable
        cir.setReturnValue(new RecipeMap(com.google.common.collect.LinkedHashMultimap.create(builder.build()), com.google.common.collect.Maps.newLinkedHashMap(builder1.build())));
    }
    */
    
    @ModifyReturnValue(method = "create", at = @At("RETURN"))
    private static RecipeMap cardboard$makeMutable(RecipeMap original) {
        return new RecipeMap(LinkedHashMultimap.create(original.byType),
                new LinkedHashMap<>(original.byKey));
    }

    @Override
    public void cardboard$addRecipe(RecipeHolder<?> holder) {
        Collection<RecipeHolder<?>> recipes = this.byType.get(holder.value().getType());

        if (this.byKey.containsKey(holder.id())) {
            throw new IllegalStateException("Duplicate recipe ignored with ID " + holder.id());
        } else {
            recipes.add(holder);
            this.byKey.put(holder.id(), holder);
        }
    }
    // CraftBukkit end

    // Paper start - replace removeRecipe implementation
    @Override
    public <T extends RecipeInput> boolean cardboard$removeRecipe(ResourceKey<Recipe<T>> mcKey) {
        //noinspection unchecked
        final RecipeHolder<Recipe<T>> remove = (RecipeHolder<Recipe<T>>) this.byKey.remove(mcKey);
        if (remove == null) {
            return false;
        }
        final Collection<? extends RecipeHolder<? extends Recipe<T>>> recipes = this.byType(remove.value().getType());
        return recipes.remove(remove);
        // Paper end - why are you using a loop???
    }
    // Paper end - replace removeRecipe implementation
}
