package org.cardboardpowered.impl.tag;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.Registry;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import org.bukkit.Material;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;

public class CraftBlockTag extends CraftTag<Block, Material> {

    public CraftBlockTag(Registry<Block> registry, TagKey<Block> tag) {
        super(registry, tag);
    }

    @Override
    public boolean isTagged(Material item) {
        Block block = CraftMagicNumbers.getBlock(item);

        if (block == null) {
            return false;
        }

        return block.builtInRegistryHolder().is(this.tag);
    }

    @Override
    public Set<Material> getValues() {
        return this.getHandle().stream()
                .map(holder -> CraftMagicNumbers.getMaterial(holder.value()))
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }
}