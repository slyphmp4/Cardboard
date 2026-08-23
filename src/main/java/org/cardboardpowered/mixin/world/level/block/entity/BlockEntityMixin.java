package org.cardboardpowered.mixin.world.level.block.entity;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry;
import org.cardboardpowered.bridge.world.level.block.entity.BlockEntityBridge;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public class BlockEntityMixin implements BlockEntityBridge {

    private static final CraftPersistentDataTypeRegistry DATA_TYPE_REGISTRY = new CraftPersistentDataTypeRegistry();
    public CraftPersistentDataContainer persistentDataContainer;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cardboard$initPersistentDataContainer(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state,
            CallbackInfo ci
    ) {
        this.persistentDataContainer =
                new CraftPersistentDataContainer(
                        DATA_TYPE_REGISTRY
                );
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void cardboard$loadPersistentDataContainer(
            ValueInput input,
            CallbackInfo ci
    ) {
        this.persistentDataContainer.clear();

        input.read(
                "PublicBukkitValues",
                CompoundTag.CODEC
        ).ifPresent(
                this.persistentDataContainer::putAll
        );
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void cardboard$savePersistentDataContainer(
            ValueOutput output,
            CallbackInfo ci
    ) {
        if (!this.persistentDataContainer.isEmpty()) {
            output.store(
                    "PublicBukkitValues",
                    CompoundTag.CODEC,
                    this.persistentDataContainer.toTagCompound()
            );
        }
    }

    @Shadow
    private DataComponentMap components = DataComponentMap.EMPTY;

    @Shadow public Level level;
    @Shadow public BlockPos worldPosition;

    @Override
    public CraftPersistentDataContainer getPersistentDataContainer() {
        return persistentDataContainer;
    }

    // CraftBukkit start - add method
    @Override
    public org.bukkit.inventory.@Nullable InventoryHolder cardboard$getOwner() {
        return cardboard$getOwner(true);
    }

    @Override
    public org.bukkit.inventory.@Nullable InventoryHolder cardboard$getOwner(boolean useSnapshot) {
        if (this.level == null) return null;
        org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at((ServerLevel) this.level, this.worldPosition);

        final org.bukkit.block.BlockState state;
        try {
            state = block.getState(useSnapshot); // Paper
        } catch (IllegalStateException ex) {
            // Cardboard compatibility: arbitrary modded block entities do not always
            // have a CraftBukkit BlockState factory. Detect the owning block directly
            // from Minecraft's registry instead of Bukkit Material#getKey(), because
            // dynamically injected Material values may report a vanilla namespace.
            Identifier key = BuiltInRegistries.BLOCK.getKey(
                    ((BlockEntity) (Object) this).getBlockState().getBlock()
            );
            if (key != null
                    && !Identifier.DEFAULT_NAMESPACE.equals(key.getNamespace())
                    && ex.getMessage() != null
                    && ex.getMessage().startsWith("Unexpected BlockState")) {
                // A nullable holder prevents crashes, but plugins such as CoreProtect
                // deliberately ignore ownerless inventories. If this BlockEntity is a
                // Container, expose the generic Paper BlockInventoryHolder contract so
                // plugins can recognize it as a real block-backed inventory without
                // pretending it is a vanilla Chest/Barrel BlockState.
                if ((Object) this instanceof Container container) {
                    return new org.bukkit.inventory.BlockInventoryHolder() {
                        @Override
                        public org.bukkit.block.Block getBlock() {
                            return block;
                        }

                        @Override
                        public org.bukkit.inventory.Inventory getInventory() {
                            return new org.bukkit.craftbukkit.inventory.CraftInventory(container);
                        }
                    };
                }
                return null;
            }
            throw ex;
        }

        return state instanceof final org.bukkit.inventory.InventoryHolder inventoryHolder ? inventoryHolder : null;
    }
    // CraftBukkit end

    @Override
    public void setCardboardPersistentDataContainer(CraftPersistentDataContainer c) {
        persistentDataContainer = c;
    }

    @Override
    public CraftPersistentDataTypeRegistry getCardboardDTR() {
        return DATA_TYPE_REGISTRY;
    }

    @Shadow
    public void applyImplicitComponents(DataComponentGetter components) {
    }

    @Override
    public Set<DataComponentType<?>> applyComponentsSet(DataComponentMap defaultComponents, DataComponentPatch components) {
        final Set<DataComponentType<?>> set = new HashSet<>();
        set.add(DataComponents.BLOCK_ENTITY_DATA);
        set.add(DataComponents.BLOCK_STATE);
        final DataComponentMap componentMap = PatchedDataComponentMap.fromPatch(defaultComponents, components);
        this.applyImplicitComponents(new DataComponentGetter() {

            @Override
            public <T> T get(DataComponentType<? extends T> type) {
                set.add(type);
                return componentMap.get(type);
            }

            @Override
            public <T> T getOrDefault(DataComponentType<? extends T> type, T fallback) {
                set.add(type);
                return componentMap.getOrDefault(type, fallback);
            }
        });
        DataComponentPatch componentChanges = components.forget(set::contains);
        this.components = componentChanges.split().added();

        // Paper - start
        set.remove(DataComponents.BLOCK_ENTITY_DATA); // Remove as never actually added by applyImplicitComponents
        return set;
        // Paper - end
    }

}
