package org.cardboardpowered.smoke;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Note;
import org.bukkit.Particle;
import org.bukkit.craftbukkit.entity.CraftPlayer;

/** Disposable outbound observers on the owner's local channel and the guest's real TCP channel. */
final class WorldEffectsSmoke {
    static void verify(CraftPlayer owner, CraftPlayer guest) {
        require(owner.getWorld().equals(guest.getWorld()), "Effect clients are in different worlds");
        var a = owner.getLocation();
        var b = guest.getLocation();
        var point = new Location(owner.getWorld(), quarter((a.getX() + b.getX()) / 2),
                quarter((a.getY() + b.getY()) / 2) + 1, quarter((a.getZ() + b.getZ()) / 2));
        require(a.distanceSquared(point) < 32 * 32 && b.distanceSquared(point) < 32 * 32, "Effect clients are outside the native unforced radius");
        var world = owner.getWorld();
        try (var host = new Capture(owner.getHandle()); var remote = new Capture(guest.getHandle())) {
            var note = new Note(13);
            world.playNote(point, Instrument.PIANO, note);
            drain(host, remote);
            for (var capture : List.of(host, remote)) {
                var sounds = capture.sounds();
                require(sounds.size() == 1, "World note did not emit exactly one sound to each nearby client");
                var sound = sounds.getFirst();
                require(number(sound, "volume").floatValue() == 3f
                                && Math.abs(number(sound, "pitch").floatValue() - note.getPitch()) < 1e-6
                                && ((Enum<?>) property(sound, "source")).name().equals("RECORDS"),
                        "World note changed volume, pitch or sound category");
            }
            clear(host, remote);
            world.playNote(point, Instrument.CUSTOM_HEAD, note);
            drain(host, remote);
            require(host.sounds().isEmpty() && remote.sounds().isEmpty(), "Custom-head note emitted a sound without a head sound");

            clear(host, remote);
            world.spawnParticle(Particle.END_ROD, point, 5);
            drain(host, remote);
            particle(host, 5, point, false);
            particle(remote, 5, point, false);

            clear(host, remote);
            world.spawnParticle(Particle.END_ROD, List.of(owner), null, point.getX(), point.getY(), point.getZ(),
                    7, 0.1, 0.2, 0.3, 0.25, null, false);
            drain(host, remote);
            particle(host, 7, point, false);
            require(remote.particles().isEmpty(), "Explicit particle receivers leaked to the other client");

            clear(host, remote);
            world.spawnParticle(Particle.END_ROD, List.of(), null, point.getX(), point.getY(), point.getZ(),
                    9, 0, 0, 0, 0.25, null, true);
            drain(host, remote);
            require(host.particles().isEmpty() && remote.particles().isEmpty(), "Empty receiver list broadcast particles");

            clear(host, remote);
            world.spawnParticle(Particle.END_ROD, List.of(guest), null, point.getX(), point.getY(), point.getZ(),
                    11, 0.1, 0.2, 0.3, 0.25, null, true);
            drain(host, remote);
            require(host.particles().isEmpty(), "Guest-only forced particles reached the owner");
            particle(remote, 11, point, true);
            System.out.println("WORLD_EFFECT_PACKETS_PASS owner=" + owner.getUniqueId() + " guest=" + guest.getUniqueId());
        } catch (Exception failure) {
            throw new IllegalStateException("World effect packet probe failed", failure);
        }
    }

    private static double quarter(double value) { return Math.floor(value * 4) / 4; }

    private static void particle(Capture capture, int count, Location point, boolean force) throws Exception {
        var packets = capture.particles();
        require(packets.size() == 1, "Classic particle call did not produce exactly one native packet per receiver");
        Object packet = packets.getFirst();
        require(number(packet, "count").intValue() == count
                        && number(packet, "x").doubleValue() == point.getX()
                        && number(packet, "y").doubleValue() == point.getY()
                        && number(packet, "z").doubleValue() == point.getZ()
                        && ((Boolean) property(packet, "overrideLimiter")) == force,
                "Classic particle dispatch changed count, position or force flag");
    }

    private static Number number(Object value, String name) throws Exception { return (Number) property(value, name); }

    private static Object property(Object value, String name) throws Exception {
        String upper = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String method : List.of("get" + upper, "is" + upper, name)) {
            try { return value.getClass().getMethod(method).invoke(value); }
            catch (NoSuchMethodException missing) { }
        }
        return field(value, name);
    }

    private static Object field(Object value, String name) throws Exception {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(value);
            } catch (NoSuchFieldException missing) { }
        }
        throw new NoSuchFieldException(value.getClass().getName() + "." + name);
    }

    private static void drain(Capture... captures) throws Exception { for (var capture : captures) capture.drain(); }
    private static void clear(Capture... captures) throws Exception {
        drain(captures);
        for (var capture : captures) capture.packets.clear();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Capture implements AutoCloseable {
        private static final AtomicInteger SEQUENCE = new AtomicInteger();
        final ConcurrentLinkedQueue<Object> packets = new ConcurrentLinkedQueue<>();
        final Channel channel;
        final String name = "cardboard-world-effect-" + SEQUENCE.incrementAndGet();

        Capture(ServerPlayer player) throws Exception {
            this.channel = (Channel) field(field(player.connection, "connection"), "channel");
            require(this.channel != null && this.channel.isActive(), "Effect client channel is not active");
            this.channel.eventLoop().submit(() -> this.channel.pipeline().addLast(this.name, new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                    if (message instanceof ClientboundLevelParticlesPacket || message instanceof ClientboundSoundPacket) packets.add(message);
                    super.write(context, message, promise);
                }
            })).get(10, TimeUnit.SECONDS);
        }

        void drain() throws Exception { this.channel.eventLoop().submit(() -> {}).get(10, TimeUnit.SECONDS); }
        List<Object> particles() { return this.packets.stream().filter(ClientboundLevelParticlesPacket.class::isInstance).toList(); }
        List<Object> sounds() { return this.packets.stream().filter(ClientboundSoundPacket.class::isInstance).toList(); }
        @Override public void close() throws Exception {
            this.channel.eventLoop().submit(() -> {
                if (this.channel.pipeline().get(this.name) != null) this.channel.pipeline().remove(this.name);
            }).get(10, TimeUnit.SECONDS);
        }
    }
}
