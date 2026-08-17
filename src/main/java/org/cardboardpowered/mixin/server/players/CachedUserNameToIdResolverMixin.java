package org.cardboardpowered.mixin.server.players;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.server.players.CachedUserNameToIdResolver;
import net.minecraft.server.players.NameAndId;

import org.cardboardpowered.bridge.server.players.CachedUserNameToIdResolverBridge;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(CachedUserNameToIdResolver.class)
public abstract class CachedUserNameToIdResolverMixin
        implements CachedUserNameToIdResolverBridge {

    @Shadow
    @Final
    private Map<String, ?> profilesByName;

    @Shadow
    @Final
    private AtomicLong operationCount;

    @Override
    public NameAndId cardboard$getIfCached(final String name) {
        final Object entry =
                this.profilesByName.get(
                        name.toLowerCase(Locale.ROOT)
                );

        if (entry == null) {
            return null;
        }

        final CachedUserNameToIdResolverGameProfileInfoAccessor accessor =
                (CachedUserNameToIdResolverGameProfileInfoAccessor) entry;

        accessor.cardboard$setLastAccess(
                this.operationCount.incrementAndGet()
        );

        return accessor.cardboard$getNameAndId();
    }
}
