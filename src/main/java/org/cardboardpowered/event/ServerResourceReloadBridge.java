package org.cardboardpowered.event;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.bukkit.craftbukkit.CraftServer;

public final class ServerResourceReloadBridge implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success && CraftServer.INSTANCE != null && CraftServer.INSTANCE.getServer() == server) {
                CraftServer.INSTANCE.cardboard$afterDataReload();
            }
        });
    }
}
