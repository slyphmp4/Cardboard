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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
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
            // Cardboard compatibility: modded block entities do not necessarily have a
            // CraftBukkit BlockState factory. Paper assumes every block entity belongs
            // to a vanilla block type and throws "Unexpected BlockState" otherwise.
            // Inventory integrations such as CoreProtect only need a nullable holder,
            // so treat an unknown modded owner as absent instead of failing the event.
            org.bukkit.Material material = org.bukkit.craftbukkit.block.CraftBlockType.minecraftToBukkit(
                    ((BlockEntity) (Object) this).getBlockState().getBlock()
            );
            if (material != null && !"minecraft".equals(material.getKey().getNamespace())) {
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
