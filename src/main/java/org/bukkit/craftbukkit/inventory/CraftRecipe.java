package org.bukkit.craftbukkit.inventory;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.set.RegistrySet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Ingredient;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.recipe.CookingBookCategory;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.cardboardpowered.bridge.world.item.crafting.IngredientBridge;
import org.jetbrains.annotations.NotNull;

public interface CraftRecipe extends Recipe {

    void addToCraftingManager();

    default Optional<Ingredient> toNMSOptional(RecipeChoice bukkit, boolean requireNotEmpty) {
        return (bukkit == null || bukkit == RecipeChoice.empty()) ? Optional.empty() : Optional.of(this.toNMS(bukkit, requireNotEmpty)); // Paper - support "empty" choices
    }

    default Ingredient toNMS(RecipeChoice bukkit, boolean requireNotEmpty) {
        // Paper start
        return toIngredient(bukkit, requireNotEmpty);
    }
    static Ingredient toIngredient(RecipeChoice bukkit, boolean requireNotEmpty) {
        // Paper end
        Ingredient stack;

        if (bukkit == null) {
            stack = Ingredient.of();
        } else if (bukkit instanceof RecipeChoice.ItemTypeChoice itemTypeChoice) {
            stack = Ingredient.of(itemTypeChoice.itemTypes().resolve(org.bukkit.Registry.ITEM).stream()
                    .map(CraftItemType::bukkitToMinecraftNew));
        } else if (bukkit instanceof RecipeChoice.PredicateChoice predicateChoice) {
            stack = IngredientBridge.cb$ofStacks(List.of(CraftItemStack.asNMSCopy(predicateChoice.getItemStack())));
            ((IngredientBridge) stack).cardboard$setStackPredicate(
                    nmsStack -> predicateChoice.test(CraftItemStack.asBukkitCopy(nmsStack)));
        } else if (bukkit instanceof RecipeChoice.MaterialChoice) {
            stack = Ingredient.of(((RecipeChoice.MaterialChoice) bukkit).getChoices().stream().map((mat) -> CraftItemType.bukkitToMinecraft(mat)));
        } else if (bukkit instanceof RecipeChoice.ExactChoice) {
            stack = IngredientBridge.cb$ofStacks(((RecipeChoice.ExactChoice) bukkit).getChoices().stream().map((mat) -> CraftItemStack.asNMSCopy(mat)).toList());
            // Paper start - support "empty" choices - legacy method that spigot might incorrectly call
            // Their impl of Ingredient.of() will error, ingredients need at least one entry.
            // Callers running into this exception may have passed an incorrect empty() recipe choice to a non-empty slot or
            // spigot calls this method in a wrong place.
        } else if (bukkit == RecipeChoice.empty()) {
            throw new IllegalArgumentException("This ingredient cannot be empty");
            // Paper end - support "empty" choices
        } else {
            throw new IllegalArgumentException("Unknown recipe stack instance " + bukkit);
        }

        if (requireNotEmpty) {
            Preconditions.checkArgument(!stack.isEmpty(), "Recipe requires at least one non-air choice");
        }

        return stack;
    }

    public static RecipeChoice toBukkit(Optional<Ingredient> list) {
        return list.map(CraftRecipe::toBukkit).orElse(RecipeChoice.empty()); // Paper - fix issue with recipe API
    }

    public static RecipeChoice toBukkit(Ingredient list) {
        if (list.isEmpty()) {
            return RecipeChoice.empty(); // Paper - null breaks API contracts
        }

        IngredientBridge cblist = (IngredientBridge) list;

        Predicate<net.minecraft.world.item.ItemStack> nmsPredicate = cblist.cardboard$getStackPredicate();
        if (nmsPredicate != null) {
            net.minecraft.world.item.ItemStack example = cblist.cb$itemStacks().get(0);
            Predicate<org.bukkit.inventory.ItemStack> predicate =
                    bukkitStack -> nmsPredicate.test(CraftItemStack.asNMSCopy(bukkitStack));
            return RecipeChoice.predicateChoice(predicate, CraftItemStack.asBukkitCopy(example));
        }

        if (cblist.cb$isExact()) {
            List<org.bukkit.inventory.ItemStack> choices = new ArrayList<>(cblist.cb$itemStacks().size());
            for (net.minecraft.world.item.ItemStack i : cblist.cb$itemStacks()) {
                choices.add(CraftItemStack.asBukkitCopy(i));
            }

            return new RecipeChoice.ExactChoice(choices);
        } else {
            List<org.bukkit.inventory.ItemType> choices = list.items()
                    .map((holder) -> CraftItemType.minecraftToBukkitNew(holder.value()))
                    .toList();
            return RecipeChoice.itemType(RegistrySet.keySetFromValues(RegistryKey.ITEM, choices));
        }
    }

    public static net.minecraft.world.item.crafting.CraftingBookCategory getCategory(CraftingBookCategory bukkit) {
        return net.minecraft.world.item.crafting.CraftingBookCategory.valueOf(bukkit.name());
    }

    public static CraftingBookCategory getCategory(net.minecraft.world.item.crafting.CraftingBookCategory nms) {
        return CraftingBookCategory.valueOf(nms.name());
    }

    public static net.minecraft.world.item.crafting.CookingBookCategory getCategory(CookingBookCategory bukkit) {
        return net.minecraft.world.item.crafting.CookingBookCategory.valueOf(bukkit.name());
    }

    public static CookingBookCategory getCategory(net.minecraft.world.item.crafting.CookingBookCategory nms) {
        return CookingBookCategory.valueOf(nms.name());
    }

    public static ResourceKey<net.minecraft.world.item.crafting.Recipe<?>> toMinecraft(NamespacedKey key) {
        return ResourceKey.create(Registries.RECIPE, CraftNamespacedKey.toMinecraft(key));
    }

    static Optional<Ingredient> toPossibleIngredient(RecipeChoice bukkit, boolean requireNotEmpty) {
        return (bukkit == null || bukkit == RecipeChoice.empty()) ? Optional.empty() : Optional.of(toIngredient(bukkit, requireNotEmpty)); // Paper - support "empty" choices
    }

}
