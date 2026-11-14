package org.bukkit.craftbukkit.util;

import org.bukkit.Location;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.StructureSearchResult;

public final class CraftStructureSearchResult implements StructureSearchResult {
    private final Structure structure;
    private final Location location;

    public CraftStructureSearchResult(Structure structure, Location location) {
        this.structure = structure;
        this.location = location.clone();
    }

    @Override
    public Structure getStructure() {
        return this.structure;
    }

    @Override
    public Location getLocation() {
        return this.location.clone();
    }
}
