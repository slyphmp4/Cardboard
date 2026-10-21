package org.cardboardpowered.smoke.bootstrap;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.registry.event.RegistryEvents;

public final class IntegratedBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        increment("cardboard.smoke.bootstrap.count");
        context.getLifecycleManager().registerEventHandler(RegistryEvents.ENCHANTMENT.compose(), event -> {
            increment("cardboard.smoke.compose.count");
        });
    }

    private static synchronized void increment(String key) {
        System.setProperty(key, Integer.toString(Integer.parseInt(System.getProperty(key, "0")) + 1));
    }
}
