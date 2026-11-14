package org.cardboardpowered.test;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** A real independent mod: no Cardboard internals or Bukkit on its classpath. */
public final class Port263Fixture implements ModInitializer {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("cardboard_port263_fixture", "probe");
    public static final EntityType<FixturePig> PIG = Registry.register(BuiltInRegistries.ENTITY_TYPE, ID,
            EntityType.Builder.of(FixturePig::new, MobCategory.CREATURE).sized(0.9F, 0.9F)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, ID)));
    public static final Block BLOCK = Registry.register(BuiltInRegistries.BLOCK, ID,
            new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, ID))));
    public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ID,
            new BlockItem(BLOCK, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID))));
    // A non-vanilla namespace with the same path as a vanilla item. It also
    // catches code which mistakes every namespace starting with minecraft for vanilla.
    public static final Identifier ITEM_ONLY_ID = Identifier.fromNamespaceAndPath("minecraft_fixture", "dirt");
    public static final Item ITEM_ONLY = Registry.register(BuiltInRegistries.ITEM, ITEM_ONLY_ID,
            new Item(new Item.Properties().stacksTo(16).setId(ResourceKey.create(Registries.ITEM, ITEM_ONLY_ID))));
    public static final DataComponentType<Integer> COMPONENT = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            ID, DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(PIG, Pig.createAttributes());
        System.out.println("PORT263_CUSTOM_FIXTURE_REGISTERED " + ID);
    }

    public static net.minecraft.world.item.ItemStack createMarkedStack() {
        var stack = new net.minecraft.world.item.ItemStack(ITEM);
        stack.set(COMPONENT, 1729);
        return stack;
    }

    public static int readMarker(net.minecraft.world.item.ItemStack stack) {
        return stack.getOrDefault(COMPONENT, -1);
    }

    public static final class FixturePig extends Pig {
        public FixturePig(EntityType<? extends Pig> type, Level level) {
            super(type, level);
        }
    }
}
