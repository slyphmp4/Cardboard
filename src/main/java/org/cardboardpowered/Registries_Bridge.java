package org.cardboardpowered;

import io.papermc.paper.registry.data.util.Conversions;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.RegistryLayer;

public class Registries_Bridge {
    public static final Conversions BUILT_IN_CONVERSIONS = new Conversions(
            new RegistryOps.HolderLookupAdapter(RegistryLayer.STATIC_ACCESS));
}
