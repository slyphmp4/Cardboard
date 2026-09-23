package org.cardboardpowered.impl.util;

import org.bukkit.craftbukkit.CraftServer;

/** Compatibility alias for Cardboard integrations using the previous class name. */
@Deprecated
public class SimpleHelpMap extends org.bukkit.craftbukkit.help.SimpleHelpMap {

    public SimpleHelpMap(CraftServer server) {
        super(server);
    }
}
