package org.cardboardpowered.smoke;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import org.bukkit.GameMode;
import org.bukkit.WeatherType;
import org.bukkit.craftbukkit.entity.CraftPlayer;

/** Real owner/TCP guest state, native broadcasts and two rain phases over live server ticks. */
final class PlayerStateSmoke {
    private static State active;

    static boolean finished() { return active == null; }

    static void begin(CraftPlayer owner, CraftPlayer guest) {
        require(active == null, "Previous world's personal-state fixture survived cleanup");
        try {
            active = new State(owner, guest);
            active.verifyImmediate();
        } catch (Throwable failure) { fail(failure); }
    }

    static void poll() {
        if (active == null) return;
        try {
            if (active.step()) {
                State completed = active;
                active = null;
                completed.close();
                System.out.println("PLAYER_STATE_PACKETS_PASS world=" + completed.owner.getWorld().getUID()
                        + " ticks=" + (completed.serverTick() - completed.started) + " clockSamples=" + completed.syncs.size());
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private static void fail(Throwable failure) {
        State state = active;
        active = null;
        if (state != null) try { state.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("Personal player state probe failed", failure);
    }

    private static final class State implements AutoCloseable {
        final CraftPlayer owner, guest;
        final ServerPlayer nativeOwner, nativeGuest;
        final Capture host, remote;
        final Holder<WorldClock> clock;
        final int started;
        final Map<Long, ClockNetworkState> syncs = new HashMap<>();
        int phase, phaseStart, lastSync;

        State(CraftPlayer owner, CraftPlayer guest) throws Exception {
            this.owner = owner; this.guest = guest;
            this.nativeOwner = owner.getHandle(); this.nativeGuest = guest.getHandle();
            require(owner.getWorld().equals(guest.getWorld()), "Personal-state clients are in different worlds");
            require(owner.isPlayerTimeRelative() && guest.isPlayerTimeRelative()
                    && owner.getPlayerTimeOffset() == 0 && guest.getPlayerTimeOffset() == 0
                    && owner.getPlayerWeather() == null && guest.getPlayerWeather() == null
                    && !owner.isSleepingIgnored() && !guest.isSleepingIgnored(), "New world's player state was not fresh");
            this.clock = this.nativeOwner.level().dimensionType().defaultClock().orElseThrow();
            this.host = new Capture(this.nativeOwner);
            try { this.remote = new Capture(this.nativeGuest); }
            catch (Throwable failure) { this.host.close(); throw failure; }
            this.started = this.phaseStart = this.lastSync = serverTick();
        }

        int serverTick() { return this.nativeOwner.level().getServer().getTickCount(); }
        ClientboundSetTimePacket sync() { return this.nativeOwner.level().clockManager().createFullSyncPacket(); }
        void broadcast(ClientboundSetTimePacket packet) { this.nativeOwner.level().getServer().getPlayerList().broadcastAll(packet); }
        void weather(ClientboundGameEventPacket packet) { this.nativeOwner.level().getServer().getPlayerList().broadcastAll(packet); }

        void verifyImmediate() throws Exception {
            long base = this.owner.getWorld().getFullTime();
            var source = sync();
            this.owner.setPlayerTime(500, true);
            drain();
            time(this.host, source, base + 500, source.clockUpdates().get(this.clock).rate());
            require(this.remote.times().isEmpty(), "Personal time setter reached the other player");
            clear();
            this.guest.setPlayerTime(18000, false);
            drain();
            time(this.remote, source, base - base % 24000 + 18000, 0);
            require(this.host.times().isEmpty(), "Fixed guest time reached the owner");
            for (float rate : new float[] {0.5F, 0F}) {
                clear();
                var updates = new HashMap<>(source.clockUpdates());
                updates.put(this.clock, new ClockNetworkState(base, 0.375F, rate));
                var custom = new ClientboundSetTimePacket(source.gameTime(), Map.copyOf(updates));
                broadcast(custom);
                drain();
                time(this.host, custom, base + 500, rate);
                time(this.remote, custom, base - base % 24000 + 18000, 0);
                require(custom.clockUpdates().get(this.clock).totalTicks() == base
                        && custom.clockUpdates().get(this.clock).rate() == rate, "Personal dispatch mutated a shared clock packet");
            }
            clear();
            this.owner.resetPlayerTime();
            drain();
            time(this.host, source, base, source.clockUpdates().get(this.clock).rate());
            require(this.remote.times().isEmpty() && !this.guest.isPlayerTimeRelative(), "Owner reset changed the guest's fixed time");
            this.owner.setPlayerTime(500, true);

            boolean raining = this.nativeOwner.level().isRaining();
            float rain = this.nativeOwner.level().getRainLevel(1F), thunder = this.nativeOwner.level().getThunderLevel(1F);
            clear();
            this.owner.setPlayerWeather(WeatherType.CLEAR);
            drain();
            event(this.host, ClientboundGameEventPacket.START_RAINING, 0);
            require(this.remote.events().isEmpty(), "Owner weather setter reached the guest");
            clear();
            this.guest.setPlayerWeather(WeatherType.DOWNFALL);
            drain();
            event(this.remote, ClientboundGameEventPacket.STOP_RAINING, 0);
            require(this.host.events().isEmpty(), "Guest weather setter reached the owner");
            require(this.owner.getPlayerWeather() == WeatherType.CLEAR && this.guest.getPlayerWeather() == WeatherType.DOWNFALL, "Personal weather setters did not roundtrip independently");
            clear();
            weather(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0));
            weather(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.75F));
            weather(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.6F));
            drain();
            event(this.host, ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0);
            event(this.remote, ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.6F);
            clear();
            CompletableFuture<Void> callback = new CompletableFuture<>();
            this.nativeOwner.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.75F), future -> {
                if (future.isSuccess()) callback.complete(null);
                else callback.completeExceptionally(future.cause() != null ? future.cause() : new IllegalStateException("Weather write was cancelled"));
            });
            callback.get(10, TimeUnit.SECONDS);
            drain();
            event(this.host, ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0);
            require(this.remote.events().isEmpty(), "Weather write callback send reached another player");
            clear();
            this.owner.resetPlayerWeather();
            drain();
            event(this.host, raining ? ClientboundGameEventPacket.STOP_RAINING : ClientboundGameEventPacket.START_RAINING, 0);
            require(this.owner.getPlayerWeather() == null && this.guest.getPlayerWeather() == WeatherType.DOWNFALL, "Weather reset changed another player's override");
            clear();
            weather(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.37F));
            drain();
            event(this.host, ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.37F);
            require(this.remote.events().isEmpty(), "World rain escaped the guest override");
            require(this.nativeOwner.level().isRaining() == raining && this.nativeOwner.level().getRainLevel(1F) == rain
                    && this.nativeOwner.level().getThunderLevel(1F) == thunder && this.owner.getWorld().getFullTime() == base,
                    "Personal settings changed the native world's weather or clock");
            verifySleep();
            this.owner.setPlayerWeather(WeatherType.DOWNFALL);
            this.guest.setPlayerWeather(WeatherType.CLEAR);
            clear();
            this.phaseStart = this.lastSync = serverTick();
        }

        void verifySleep() throws Exception {
            SleepStatus status = (SleepStatus) field(this.nativeOwner.level(), "sleepStatus");
            var players = this.nativeOwner.level().players();
            require(players.size() == 2, "Sleep fixture does not have exactly two real players");
            GameMode previous = this.guest.getGameMode();
            try {
                this.owner.setSleepingIgnored(true);
                require(status.amountSleeping() == 1 && (Integer) field(status, "activePlayers") == 2, "Native world tracker did not update on the first ignore setter");
                this.guest.setSleepingIgnored(true);
                require(status.amountSleeping() == 2 && status.areEnoughSleeping(100)
                        && !status.areEnoughDeepSleeping(100, players), "All-ignored live players skipped the real sleeper requirement");
                this.guest.setGameMode(GameMode.SPECTATOR);
                this.guest.setSleepingIgnored(true);
                require(status.amountSleeping() == 1 && (Integer) field(status, "activePlayers") == 1, "Ignored spectator contributed to active/sleeping counts");
            } finally {
                this.guest.setGameMode(previous);
                this.owner.setSleepingIgnored(false);
                this.guest.setSleepingIgnored(false);
            }
            require(status.amountSleeping() == 0 && !this.owner.isSleepingIgnored() && !this.guest.isSleepingIgnored(), "Sleep-ignore cleanup retained live sleeping counts");
        }

        boolean step() throws Exception {
            int tick = serverTick();
            if (tick - this.lastSync >= 10) {
                var source = sync();
                this.syncs.put(source.gameTime(), source.clockUpdates().get(this.clock));
                broadcast(source);
                this.lastSync = tick;
            }
            if (tick - this.phaseStart < 65) return false;
            drain();
            if (this.phase == 0) {
                rainRamp(this.host, true);
                require(this.remote.rain().isEmpty(), "Clear guest inherited world rain");
                this.owner.setPlayerWeather(WeatherType.CLEAR);
                this.guest.setPlayerWeather(WeatherType.DOWNFALL);
                this.host.clearEvents(); this.remote.clearEvents();
                this.phase = 1;
                this.phaseStart = tick;
                return false;
            }
            rainRamp(this.host, false);
            rainRamp(this.remote, true);
            require(this.syncs.size() >= 2, "Live ticks did not exercise repeated native clock synchronization");
            for (var sample : this.syncs.entrySet()) {
                long base = sample.getValue().totalTicks();
                require(this.host.times().stream().anyMatch(packet -> packet.gameTime() == sample.getKey()
                        && packet.clockUpdates().get(this.clock).totalTicks() == base + 500
                        && packet.clockUpdates().get(this.clock).rate() == sample.getValue().rate()), "Relative owner time was lost during later native sync");
                require(this.remote.times().stream().anyMatch(packet -> packet.gameTime() == sample.getKey()
                        && packet.clockUpdates().get(this.clock).totalTicks() == base - base % 24000 + 18000
                        && packet.clockUpdates().get(this.clock).rate() == 0), "Fixed guest time was lost during later native sync");
            }
            return true;
        }

        void time(Capture capture, ClientboundSetTimePacket source, long ticks, float rate) {
            var packets = capture.times();
            require(packets.size() == 1, "Time setter/broadcast did not emit exactly one packet per intended receiver");
            var packet = packets.getFirst();
            var state = packet.clockUpdates().get(this.clock);
            require(packet.gameTime() == source.gameTime() && packet.clockUpdates().keySet().equals(source.clockUpdates().keySet())
                    && state.totalTicks() == ticks && state.rate() == rate
                    && state.partialTick() == source.clockUpdates().get(this.clock).partialTick(), "Personal clock changed game time, rate, partial tick or clock keys");
            source.clockUpdates().forEach((key, value) -> { if (!key.equals(this.clock)) require(packet.clockUpdates().get(key).equals(value), "Personal time altered a non-default clock"); });
        }

        void drain() throws Exception { this.host.drain(); this.remote.drain(); }
        void clear() throws Exception { drain(); this.host.packets.clear(); this.remote.packets.clear(); }

        @Override public void close() throws Exception {
            try {
                this.owner.resetPlayerTime(); this.guest.resetPlayerTime();
                this.owner.resetPlayerWeather(); this.guest.resetPlayerWeather();
                this.owner.setSleepingIgnored(false); this.guest.setSleepingIgnored(false);
            } finally { try { this.host.close(); } finally { this.remote.close(); } }
        }
    }

    private static void event(Capture capture, ClientboundGameEventPacket.Type type, float value) {
        var events = capture.events();
        require(events.size() == 1 && events.getFirst().getEvent() == type && events.getFirst().getParam() == value,
                "Weather event/filter did not preserve the intended type and value: " + events);
    }

    private static void rainRamp(Capture capture, boolean increasing) {
        var values = capture.rain();
        require(values.size() >= 3 && values.stream().anyMatch(value -> value > 0.1F && value < 0.9F), "Personal rain did not transition through intermediate levels: " + values);
        for (int i = 0; i < values.size(); i++) {
            float current = values.get(i);
            require(current >= 0 && current <= 1, "Personal rain escaped native bounds");
            if (i > 0) require(increasing ? current >= values.get(i - 1) : current <= values.get(i - 1), "Personal rain transition reversed direction");
        }
    }

    private static final class Capture implements AutoCloseable {
        private static final AtomicInteger NEXT = new AtomicInteger();
        final ConcurrentLinkedQueue<Object> packets = new ConcurrentLinkedQueue<>();
        final Channel channel;
        final String name = "cardboard-personal-state-" + NEXT.incrementAndGet();
        Capture(ServerPlayer player) throws Exception {
            this.channel = (Channel) field(field(player.connection, "connection"), "channel");
            require(this.channel != null && this.channel.isActive(), "Personal-state client channel is inactive");
            this.channel.eventLoop().submit(() -> this.channel.pipeline().addLast(this.name, new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                    if (message instanceof ClientboundSetTimePacket || message instanceof ClientboundGameEventPacket) packets.add(message);
                    super.write(context, message, promise);
                }
            })).get(10, TimeUnit.SECONDS);
        }
        void drain() throws Exception { this.channel.eventLoop().submit(() -> {}).get(10, TimeUnit.SECONDS); }
        List<ClientboundSetTimePacket> times() { return this.packets.stream().filter(ClientboundSetTimePacket.class::isInstance).map(ClientboundSetTimePacket.class::cast).toList(); }
        List<ClientboundGameEventPacket> events() { return this.packets.stream().filter(ClientboundGameEventPacket.class::isInstance).map(ClientboundGameEventPacket.class::cast).toList(); }
        List<Float> rain() { return events().stream().filter(packet -> packet.getEvent() == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE).map(ClientboundGameEventPacket::getParam).toList(); }
        void clearEvents() throws Exception { drain(); this.packets.removeIf(ClientboundGameEventPacket.class::isInstance); }
        @Override public void close() throws Exception {
            this.channel.eventLoop().submit(() -> { if (this.channel.pipeline().get(this.name) != null) this.channel.pipeline().remove(this.name); }).get(10, TimeUnit.SECONDS);
        }
    }

    private static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException missing) { }
        }
        throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
