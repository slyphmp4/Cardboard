package org.cardboardpowered.mixin.server.level;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ClockNetworkState;
import org.bukkit.WeatherType;
import org.cardboardpowered.bridge.server.level.PlayerPersonalStateBridge;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerPersonalStateMixin implements PlayerPersonalStateBridge {
    @Unique private long cardboard$timeOffset;
    @Unique private boolean cardboard$relativeTime = true;
    @Unique private @Nullable WeatherType cardboard$weather;
    @Unique private float cardboard$rainPosition;
    @Unique private boolean cardboard$sendingOwnWeather;
    @Unique private boolean cardboard$sleepingIgnored;

    @Override public long cardboard$getTimeOffset() { return this.cardboard$timeOffset; }
    @Override public boolean cardboard$isTimeRelative() { return this.cardboard$relativeTime; }

    @Override
    public long cardboard$getPlayerTime() {
        long time = ((ServerPlayer) (Object) this).level().getDefaultClockTime();
        return this.cardboard$relativeTime ? time + this.cardboard$timeOffset
                : time - time % SharedConstants.TICKS_PER_GAME_DAY + this.cardboard$timeOffset;
    }

    @Override
    public void cardboard$setPlayerTime(long time, boolean relative) {
        this.cardboard$timeOffset = time;
        this.cardboard$relativeTime = relative;
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (player.connection != null && player.level().dimensionType().defaultClock().isPresent()) {
            // The connection bridge adjusts only this player's default clock.
            player.connection.send(player.level().clockManager().createFullSyncPacket());
        }
    }

    @Override public @Nullable WeatherType cardboard$getPlayerWeather() { return this.cardboard$weather; }

    @Override
    public void cardboard$setPlayerWeather(@Nullable WeatherType type, boolean plugin) {
        if (!plugin && this.cardboard$weather != null) return;
        if (plugin) this.cardboard$weather = type;
        this.cardboard$sendWeather(new ClientboundGameEventPacket(type == WeatherType.DOWNFALL
                ? ClientboundGameEventPacket.STOP_RAINING : ClientboundGameEventPacket.START_RAINING, 0));
    }

    @Override
    public void cardboard$resetPlayerWeather() {
        this.cardboard$weather = null;
        this.cardboard$setPlayerWeather(((ServerPlayer) (Object) this).level().isRaining()
                ? WeatherType.DOWNFALL : WeatherType.CLEAR, false);
    }

    @Unique
    private void cardboard$sendWeather(Packet<?> packet) {
        boolean previous = this.cardboard$sendingOwnWeather;
        this.cardboard$sendingOwnWeather = true;
        try { ((ServerPlayer) (Object) this).connection.send(packet); }
        finally { this.cardboard$sendingOwnWeather = previous; }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void cardboard$tickPersonalRain(CallbackInfo ci) {
        if (this.cardboard$weather == null) return;
        float previous = this.cardboard$rainPosition;
        this.cardboard$rainPosition = Math.clamp(previous + (this.cardboard$weather == WeatherType.DOWNFALL ? 0.01F : -0.01F), 0F, 1F);
        if (previous != this.cardboard$rainPosition) this.cardboard$sendWeather(this.cardboard$weatherSnapshotPacket());
    }

    @Override
    public Packet<?> cardboard$weatherSnapshotPacket() {
        return new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, this.cardboard$rainPosition);
    }

    @Override
    public @Nullable Packet<?> cardboard$personalizePacket(Packet<?> packet) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (packet instanceof ClientboundSetTimePacket time && (this.cardboard$timeOffset != 0 || !this.cardboard$relativeTime)) {
            var clock = player.level().dimensionType().defaultClock().orElse(null);
            ClockNetworkState state = clock == null ? null : time.clockUpdates().get(clock);
            if (state != null) {
                var updates = new HashMap<>(time.clockUpdates());
                updates.put(clock, new ClockNetworkState(this.cardboard$getPlayerTime(), state.partialTick(),
                        this.cardboard$relativeTime ? state.rate() : 0F));
                return new ClientboundSetTimePacket(time.gameTime(), Map.copyOf(updates));
            }
        }
        if (this.cardboard$weather != null && !this.cardboard$sendingOwnWeather && packet instanceof ClientboundGameEventPacket weather) {
            var event = weather.getEvent();
            if (event == ClientboundGameEventPacket.START_RAINING || event == ClientboundGameEventPacket.STOP_RAINING
                    || event == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) return null;
            if (event == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE && this.cardboard$weather == WeatherType.CLEAR) {
                return new ClientboundGameEventPacket(event, 0F);
            }
        }
        return packet;
    }

    @Override public boolean cardboard$isSleepingIgnored() { return this.cardboard$sleepingIgnored; }

    @Override
    public void cardboard$setSleepingIgnored(boolean ignored) {
        this.cardboard$sleepingIgnored = ignored;
        ((ServerPlayer) (Object) this).level().updateSleepingPlayerList();
    }
}
