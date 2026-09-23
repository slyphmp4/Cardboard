package io.papermc.paper.plugin.lifecycle.event.types;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEvent;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.tag.PostFlattenTagRegistrar;
import io.papermc.paper.tag.PreFlattenTagRegistrar;
import io.papermc.paper.tag.PaperTagListenerManager;

/**
 * Backs the lifecycle event API. Found through {@code META-INF/services}; without it every
 * reference to {@link LifecycleEvents} fails to initialise.
 */
public class CardboardLifecycleEventTypeProvider implements LifecycleEventTypeProvider {

    @Override
    public <O extends LifecycleEventOwner, E extends LifecycleEvent> LifecycleEventType.Monitorable<O, E> monitor(String name, Class<? extends O> ownerType) {
        return new CardboardMonitorableEventType<>(name);
    }

    @Override
    public <O extends LifecycleEventOwner, E extends LifecycleEvent> LifecycleEventType.Prioritizable<O, E> prioritized(String name, Class<? extends O> ownerType) {
        return new CardboardPrioritizableEventType<>(name);
    }

    @Override
    public TagEventTypeProvider tagProvider() {
        return new CardboardTagEventTypeProvider();
    }

    private static final class CardboardTagEventTypeProvider implements TagEventTypeProvider {

        @Override
        public <T> LifecycleEventType.Prioritizable<BootstrapContext, ReloadableRegistrarEvent<PreFlattenTagRegistrar<T>>> preFlatten(RegistryKey<T> registryKey) {
            return PaperTagListenerManager.INSTANCE.getCardboardPreFlattenType(registryKey);
        }

        @Override
        public <T> LifecycleEventType.Prioritizable<BootstrapContext, ReloadableRegistrarEvent<PostFlattenTagRegistrar<T>>> postFlatten(RegistryKey<T> registryKey) {
            return PaperTagListenerManager.INSTANCE.getCardboardPostFlattenType(registryKey);
        }
    }
}
