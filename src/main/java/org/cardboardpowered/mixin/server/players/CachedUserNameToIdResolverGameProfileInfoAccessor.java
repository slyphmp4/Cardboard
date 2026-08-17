package org.cardboardpowered.mixin.server.players;

import net.minecraft.server.players.NameAndId;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(
        targets =
                "net.minecraft.server.players."
                        + "CachedUserNameToIdResolver$GameProfileInfo"
)
public interface CachedUserNameToIdResolverGameProfileInfoAccessor {

    @Invoker("nameAndId")
    NameAndId cardboard$getNameAndId();

    @Invoker("setLastAccess")
    void cardboard$setLastAccess(long lastAccess);
}
