package org.cardboardpowered.impl;

import net.minecraft.world.level.block.Block;
import net.minecraft.core.registries.BuiltInRegistries;

public class CardboardModdedBlock implements CardboardModdedMaterial {

    private Block block;
    private String id;

    public CardboardModdedBlock(String id) {
        this.id = id;
        this.block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(id));
    }

    public CardboardModdedBlock(Block block) {
        this.block = block;
    }

    @Override
    public short getDamage() {
        return 0;
    }

    @Override
    public boolean isBlock() {
        return true;
    }

    @Override
    public boolean isItem() {
        // Block items share their registry key with the block. These materials
        // are registered from the block registry first, so their metadata must
        // still expose the item form to Bukkit's recipe choices.
        return BuiltInRegistries.ITEM.getOptional(BuiltInRegistries.BLOCK.getKey(block)).isPresent();
    }

    @Override
    public boolean isEdible() {
        return false;
    }

    @Override
    public String getId() {
        return id;
    }

}
