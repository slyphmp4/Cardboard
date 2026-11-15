package org.cardboardpowered.bridge.server.level;

import net.minecraft.network.protocol.Packet;
import org.bukkit.WeatherType;
import org.jspecify.annotations.Nullable;

/** Transient settings owned by one native player, never by a world or connection cache. */
public interface PlayerPersonalStateBridge {
    long cardboard$getTimeOffset();
    boolean cardboard$isTimeRelative();
    long cardboard$getPlayerTime();
    void cardboard$setPlayerTime(long time, boolean relative);
    @Nullable WeatherType cardboard$getPlayerWeather();
    void cardboard$setPlayerWeather(@Nullable WeatherType type, boolean plugin);
    void cardboard$resetPlayerWeather();
    boolean cardboard$isSleepingIgnored();
    void cardboard$setSleepingIgnored(boolean ignored);
    @Nullable Packet<?> cardboard$personalizePacket(Packet<?> packet);
    Packet<?> cardboard$weatherSnapshotPacket();
}
