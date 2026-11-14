package org.bukkit.craftbukkit.inventory;

import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import io.papermc.paper.registry.HolderableBase;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.List;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.item.component.Compostable;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.number.ints.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider;
import net.minecraft.world.level.storage.loot.providers.number.ints.NumberDispatcher;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.level.storage.loot.providers.number.ints.WeightedListValue;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockType;
import org.bukkit.craftbukkit.CraftEquipmentSlot;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.attribute.CraftAttribute;
import org.bukkit.craftbukkit.attribute.CraftAttributeInstance;
import org.bukkit.craftbukkit.block.CraftBlockType;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.inventory.CreativeCategory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.meta.ItemMeta;
import org.cardboardpowered.impl.world.CraftWorld;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class CraftItemType<M extends ItemMeta> extends HolderableBase<Item> implements ItemType.Typed<M>, io.papermc.paper.world.flag.PaperFeatureDependent<Item> {

    private final Supplier<CraftItemMetas.ItemMetaData<M>> itemMetaData;

    public static Material minecraftToBukkit(Item item) {
        return CraftMagicNumbers.getMaterial(item);
    }

    public static Item bukkitToMinecraft(Material material) {
        return CraftMagicNumbers.getItem(material);
    }

    public static ItemType minecraftToBukkitNew(Item minecraft) {
        return CraftRegistry.minecraftToBukkit(minecraft, Registries.ITEM);
    }

    public static Item bukkitToMinecraftNew(ItemType bukkit) {
        return CraftRegistry.bukkitToMinecraft(bukkit);
    }

    public CraftItemType(final Holder<Item> holder) {
        super(holder);
        this.itemMetaData = Suppliers.memoize(() -> CraftItemMetas.getItemMetaData(this));
    }

    @Override
    public Typed<ItemMeta> typed() {
        return this.typed(ItemMeta.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <Other extends ItemMeta> Typed<Other> typed(final Class<Other> itemMetaType) {
        if (itemMetaType.isAssignableFrom(this.itemMetaData.get().metaClass())) return (Typed<Other>) this;

        throw new IllegalArgumentException("Cannot type item type " + this + " to meta type " + itemMetaType.getSimpleName());
    }

    @Override
    public ItemStack createItemStack() {
        return this.createItemStack(1, null);
    }

    @Override
    public ItemStack createItemStack(final int amount) {
        return this.createItemStack(amount, null);
    }

    @Override
    public ItemStack createItemStack(final @Nullable Consumer<? super M> metaConfigurator) {
        return this.createItemStack(1, metaConfigurator);
    }

    @Override
    public ItemStack createItemStack(final int amount, final @Nullable Consumer<? super M> metaConfigurator) {
        final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(this.getHandle(), amount);
        final CraftItemStack mirror = CraftItemStack.asCraftMirror(stack);
        if (metaConfigurator != null) {
            mirror.editMeta(this.getItemMetaClass(), metaConfigurator);
        }
        return mirror;
    }

    public M getItemMeta(net.minecraft.world.item.ItemStack itemStack, final java.util.Set<net.minecraft.core.component.DataComponentType<?>> extraHandledDcts) {
        return this.itemMetaData.get().fromItemStack().apply(itemStack, extraHandledDcts);
    }

    public M getItemMeta(ItemMeta itemMeta) {
        return this.itemMetaData.get().fromItemMeta().apply(this, (CraftMetaItem) itemMeta);
    }

    @Override
    public boolean hasBlockType() {
        return this.getHandle() instanceof BlockItem;
    }

    @Override
    public BlockType getBlockType() {
        if (!(this.getHandle() instanceof BlockItem block)) {
            throw new IllegalStateException("The item type " + this.getKey() + " has no corresponding block type");
        }

        return CraftBlockType.minecraftToBukkitNew(block.getBlock());
    }

    @Override
    public Class<M> getItemMetaClass() {
        if (this == ItemType.AIR) {
            throw new UnsupportedOperationException("Air does not have ItemMeta");
        }
        return this.itemMetaData.get().metaClass();
    }

    @Override
    public int getMaxStackSize() {
        return this.getHandle().components().getOrDefault(DataComponents.MAX_STACK_SIZE, 64);
    }

    @Override
    public short getMaxDurability() {
        return this.getHandle().components().getOrDefault(DataComponents.MAX_DAMAGE, 0).shortValue();
    }

    @Override
    public boolean isEdible() {
        return this.getHandle().components().has(DataComponents.FOOD) && this.getHandle().components().has(DataComponents.CONSUMABLE);
    }

    @Override
    public boolean isRecord() {
        return this.getHandle().components().has(DataComponents.JUKEBOX_PLAYABLE);
    }

    @Override
    public boolean isFuel() {
        return this.getHandle().components().has(DataComponents.COOKING_FUEL);
    }

    @Override
    public int getBurnDuration() {
        CookingFuel cookingFuel = this.getHandle().components().get(DataComponents.COOKING_FUEL);
        if (cookingFuel == null) {
            return 0;
        }

        // Legacy API has no processing context; match Paper for registry-backed values.
        if (cookingFuel.burnTime() instanceof ResolvableInt.Constant constant) {
            return constant.value();
        }
        if (cookingFuel.burnTime() instanceof ResolvableInt.Reference burnTime) {
            if (CraftServer.INSTANCE == null) {
                throw new IllegalArgumentException("Too early to call this method");
            }

            Holder.Reference<ContextIntProvider> provider = CraftServer.INSTANCE.getServer().reloadableRegistries().lookup().getOrThrow(burnTime.key());
            LootContext fakeContext = new LootContext(new LootParams.Builder(null).create(LootContextParamSets.EMPTY),
                    net.minecraft.util.RandomSource.create(42L),
                    CraftServer.INSTANCE.getServer().reloadableRegistries().lookup());
            return provider.value().getInt(fakeContext);
        }
        return 0;
    }

    @Override
    public boolean isCompostable() {
        return this.getHandle().components().has(DataComponents.COMPOSTABLE);
    }

    @Override
    public float getCompostChance() {
        Compostable compostable = this.getHandle().components().get(DataComponents.COMPOSTABLE);
        Preconditions.checkArgument(compostable != null, "The item type " + this.getKey() + " is not compostable");

        // Legacy API has no processing context; match Paper for registry-backed values.
        if (compostable.layers() instanceof ResolvableInt.Constant constant) {
            return constant.value() > 0 ? 1.0F : 0.0F;
        }
        if (compostable.layers() instanceof ResolvableInt.Reference reference) {
            if (CraftServer.INSTANCE == null) {
                throw new IllegalArgumentException("Too early to call this method");
            }

            Holder.Reference<ContextIntProvider> provider = CraftServer.INSTANCE.getServer().reloadableRegistries().lookup().getOrThrow(reference.key());
            ConstantValue oneLayerValue = new ConstantValue(1);
            if (provider.value().equals(oneLayerValue)) {
                return 1.0F;
            }
            if (provider.value() instanceof NumberDispatcher dispatcher) {
                if (dispatcher.defaultValue().value() instanceof WeightedListValue(WeightedList<Holder<ContextIntProvider>> distribution)) {
                    List<Weighted<Holder<ContextIntProvider>>> weightedList = distribution.unwrap();
                    int total = 0;
                    int current = 0;
                    for (Weighted<Holder<ContextIntProvider>> weighted : weightedList) {
                        total += weighted.weight();
                        if (weighted.value().value().equals(oneLayerValue)) {
                            current = weighted.weight();
                        }
                    }

                    return (float) current / total;
                }
            }
        }

        return 0.0F;
    }

    @Override
    public @Nullable ItemType getCraftingRemainingItem() {
        ItemStackTemplate craftingRemainder = this.getHandle().getCraftingRemainder();
        return craftingRemainder == null ? null : CraftItemType.minecraftHolderToBukkitNew(craftingRemainder.item());
    }

    public static ItemType minecraftHolderToBukkitNew(Holder<Item> minecraft) {
        return CraftRegistry.minecraftHolderToBukkit(minecraft, Registries.ITEM);
    }
    
    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers() {
        return this.getDefaultAttributeModifiers(sg -> true);
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        final net.minecraft.world.entity.EquipmentSlot nmsSlot = CraftEquipmentSlot.getNMS(slot);
        return this.getDefaultAttributeModifiers(sg -> sg.test(nmsSlot));
    }

    private Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(final java.util.function.Predicate<net.minecraft.world.entity.EquipmentSlotGroup> slotPredicate) {
        ImmutableMultimap.Builder<Attribute, AttributeModifier> defaultAttributes = ImmutableMultimap.builder();

        ItemAttributeModifiers nmsDefaultAttributes = this.getHandle().components().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        for (final net.minecraft.world.item.component.ItemAttributeModifiers.Entry entry : nmsDefaultAttributes.modifiers()) {
            if (!slotPredicate.test(entry.slot())) continue;
            final Attribute attribute = CraftAttribute.minecraftHolderToBukkit(entry.attribute());
            final AttributeModifier modifier = CraftAttributeInstance.convert(entry.modifier(), entry.slot());
            defaultAttributes.put(attribute, modifier);
        }

        return defaultAttributes.build();
    }

    @Override
    public CreativeCategory getCreativeCategory() {
        return CreativeCategory.BUILDING_BLOCKS;
    }

    @Override
    public boolean isEnabledByFeature(final World world) {
        Preconditions.checkArgument(world != null, "World cannot be null");
        return this.getHandle().isEnabled(((CraftWorld) world).getHandle().enabledFeatures());
    }

    @Override
    public String getTranslationKey() {
        return this.getHandle().getDescriptionId();
    }

    @Override
    public @Nullable Material asMaterial() {
        return CraftMagicNumbers.getMaterial(this.getHandle());
    }

    // Paper start - add Translatable
    @Override
    public String translationKey() {
        return this.getHandle().getDescriptionId();
    }
    // Paper end - add Translatable

    // Paper start - expand ItemRarity API
    @Override
    public org.bukkit.inventory.@Nullable ItemRarity getItemRarity() {
        final net.minecraft.world.item.Rarity rarity = this.getHandle().components().get(DataComponents.RARITY);
        return rarity == null ? null : org.bukkit.inventory.ItemRarity.valueOf(rarity.name());
    }
    // Paper end - expand ItemRarity API
    // Paper start - data component API
    @Override
    public <T> @Nullable T getDefaultData(final io.papermc.paper.datacomponent.DataComponentType.Valued<T> type) {
        return io.papermc.paper.datacomponent.PaperDataComponentType.convertDataComponentValue(this.getHandle().components(), ((io.papermc.paper.datacomponent.PaperDataComponentType.ValuedImpl<T, ?>) type));
    }

    @Override
    public boolean hasDefaultData(final io.papermc.paper.datacomponent.DataComponentType type) {
        return this.getHandle().components().has(io.papermc.paper.datacomponent.PaperDataComponentType.bukkitToMinecraft(type));
    }

    @Override
    public java.util.Set<io.papermc.paper.datacomponent.DataComponentType> getDefaultDataTypes() {
        return io.papermc.paper.datacomponent.PaperDataComponentType.minecraftToBukkit(this.getHandle().components().keySet());
    }
    // Paper end - data component API
}
