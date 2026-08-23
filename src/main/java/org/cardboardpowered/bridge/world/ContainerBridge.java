/**
 * Cardboard - Paper API for Fabric
 * Copyright (C) 2020-2025
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either 
 * version 3 of the License, or (at your option) any later version.
 */
package org.cardboardpowered.bridge.world;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.inventory.InventoryHolder;
import org.cardboardpowered.bridge.world.level.block.entity.BlockEntityBridge;

public interface ContainerBridge {

    default java.util.List<ItemStack> getContents() {
    	return null;
    }

    default void onOpen(CraftHumanEntity who) {
    }

    // These are defaulted rather than abstract because MixinContainer grafts this
    // interface onto every net.minecraft.world.Container. Any container class
    // without a dedicated mixin would otherwise throw AbstractMethodError at the
    // first call site, which callers cannot catch.
    default void onClose(CraftHumanEntity who) {
    }

    default java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        return java.util.Collections.emptyList();
    }

    default org.bukkit.inventory.InventoryHolder getOwner() {
        // Generic compatibility path for modded block containers. Dedicated vanilla
        // container mixins can still override this method, but arbitrary BlockEntity
        // implementations should expose their Bukkit owner rather than silently
        // looking like ownerless custom inventories.
        if ((Object) this instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
            return ((BlockEntityBridge) blockEntity).cardboard$getOwner();
        }
        return null;
    }

    default void cardboard$setMaxStackSize(int size) {
    }

    default org.bukkit.Location getLocation() {
        // Bukkit Inventory#getLocation() is expected to identify the backing block
        // for block inventories. Most modded containers have no dedicated Cardboard
        // mixin, so derive this directly from the BlockEntity instead.
        if ((Object) this instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity
                && blockEntity.getLevel() != null) {
            return org.bukkit.craftbukkit.block.CraftBlock.at(
                    blockEntity.getLevel(),
                    blockEntity.getBlockPos()
            ).getLocation();
        }
        return null;
    }

    default Recipe<?> getCurrentRecipe() {
        return null;
    }

    default void setCurrentRecipe(Recipe<?> recipe) {
    }

    int MAX_STACK = 64;

    default void cardboard$setOwner(InventoryHolder owner) {
        // TODO
    }

}
