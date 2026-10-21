package org.cardboardpowered.util;

import io.papermc.paper.plugin.PluginInitializerManager;
import io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventRunner;
import io.papermc.paper.plugin.lifecycle.event.types.CardboardLifecycleEventRunner;
import org.cardboardpowered.CardboardMod;

/** Plugin discovery must precede registry loading, including after closing a world. */
public final class IntegratedServerLifecycle {
    private static boolean rediscoverPlugins;

    private IntegratedServerLifecycle() {}

    public static synchronized void prepareWorldLoad() {
        if (!rediscoverPlugins) return;
        LaunchEntryPointHandler.INSTANCE.populateProviderStorage();
        try {
            PluginInitializerManager.load(CardboardMod.options);
            LaunchEntryPointHandler.enterBootstrappers();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not rediscover plugins for this world", failure);
        }
        rediscoverPlugins = false;
    }

    public static synchronized void serverStopped() {
        var server = org.bukkit.craftbukkit.CraftServer.INSTANCE;
        if (server != null) {
            ((org.bukkit.craftbukkit.scheduler.CraftScheduler) server.getScheduler()).shutdown();
            ((io.papermc.paper.threadedregions.scheduler.FoliaAsyncScheduler) server.getAsyncScheduler()).shutdown();
        }
        org.spigotmc.WatchdogThread.doStop();
        // Bootstrap contexts may outlive their plugin instance or fail before onEnable.
        CardboardLifecycleEventRunner.resetForServerStop();
        LifecycleEventRunner.INSTANCE.resetForServerStop();
        rediscoverPlugins = true;
    }
}
