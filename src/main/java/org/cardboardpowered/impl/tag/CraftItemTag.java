package org.cardboardpowered.impl.tag;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.Registry;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import org.bukkit.Material;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;

public class CraftItemTag extends CraftTag<Item, Material> {

    public CraftItemTag(Registry<Item> registry, TagKey<Item> tag) {
        super(registry, tag);
    }

    @Override
    public boolean isTagged(Material item) {
        Item minecraft = CraftMagicNumbers.getItem(item);

        if (minecraft == null) {
            return false;
        }

        return minecraft.builtInRegistryHolder().is(this.tag);
    }

    @Override
    public Set<Material> getValues() {
        return this.getHandle().stream()
                .map(holder -> CraftMagicNumbers.getMaterial(holder.value()))
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }
}