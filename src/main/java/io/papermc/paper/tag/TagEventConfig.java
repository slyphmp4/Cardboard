package io.papermc.paper.tag;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.CardboardLifecycleEventType;
import io.papermc.paper.registry.RegistryKey;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;

@DefaultQualifier(NonNull.class)
public record TagEventConfig<M, A>(
    @Nullable CardboardLifecycleEventType<BootstrapContext, ? extends ReloadableRegistrarEvent<PreFlattenTagRegistrar<A>>, ?> preFlatten,
    @Nullable CardboardLifecycleEventType<BootstrapContext, ? extends ReloadableRegistrarEvent<PostFlattenTagRegistrar<A>>, ?> postFlatten,
    ReloadableRegistrarEvent.Cause cause,
    Function<Identifier, Optional<? extends M>> fromIdConverter,
    Function<M, Identifier> toIdConverter,
    RegistryKey<A> apiRegistryKey
) {
}
