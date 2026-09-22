package io.papermc.paper.registry.event.type;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler;
import io.papermc.paper.plugin.lifecycle.event.handler.configuration.PrioritizedLifecycleEventHandlerConfiguration;
import io.papermc.paper.plugin.lifecycle.event.types.CardboardHandlerConfiguration;
import io.papermc.paper.plugin.lifecycle.event.types.CardboardPrioritizableEventType;
import io.papermc.paper.registry.RegistryBuilder;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.event.RegistryEntryAddEvent;
import java.util.function.Predicate;

public final class CardboardRegistryEntryAddEventType<T, B extends RegistryBuilder<T>>
        extends CardboardPrioritizableEventType<BootstrapContext, RegistryEntryAddEvent<T, B>>
        implements RegistryEntryAddEventType<T, B> {

    public CardboardRegistryEntryAddEventType(RegistryKey<T> registryKey, String eventName) {
        super(registryKey + " / " + eventName);
    }

    @Override
    public RegistryEntryAddConfiguration<T> newHandler(LifecycleEventHandler<? super RegistryEntryAddEvent<T, B>> handler) {
        final class Configuration implements RegistryEntryAddConfiguration<T>, CardboardHandlerConfiguration {
            private Predicate<TypedKey<T>> filter = key -> true;
            private final PrioritizedLifecycleEventHandlerConfiguration<BootstrapContext> delegate =
                    CardboardRegistryEntryAddEventType.super.newHandler(event -> {
                        if (this.filter.test(event.key())) {
                            handler.run(event);
                        }
                    });

            @Override
            public RegistryEntryAddConfiguration<T> filter(Predicate<TypedKey<T>> predicate) {
                this.filter = java.util.Objects.requireNonNull(predicate);
                return this;
            }

            @Override
            public RegistryEntryAddConfiguration<T> priority(int priority) {
                this.delegate.priority(priority);
                return this;
            }

            @Override
            public RegistryEntryAddConfiguration<T> monitor() {
                this.delegate.monitor();
                return this;
            }

            @Override
            public void registerTo(io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner owner) {
                ((CardboardHandlerConfiguration) this.delegate).registerTo(owner);
            }
        }
        return new Configuration();
    }
}
