package org.cardboardpowered.bridge.server.players;

import net.minecraft.server.players.NameAndId;
import org.jetbrains.annotations.Nullable;

public interface CachedUserNameToIdResolverBridge {

    @Nullable
    NameAndId cardboard$getIfCached(String name);
}
