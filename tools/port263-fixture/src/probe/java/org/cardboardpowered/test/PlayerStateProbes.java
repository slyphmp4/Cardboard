package org.cardboardpowered.test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Unregistered native players exercise API state without fake network connections. */
final class PlayerStateProbes {
    static void verify(World world, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("player-personal-time-default-relative-fixed-reset-and-isolation", () -> {
            var first = actor(world, "TimeProbeA");
            var second = actor(world, "TimeProbeB");
            long base = world.getFullTime();
            require(first.api.isPlayerTimeRelative(), "Fresh player time is not relative");
            require(first.api.getPlayerTimeOffset() == 0 && first.api.getPlayerTime() == base, "Fresh personal time differs from the native world clock");
            for (long offset : new long[] {-1234, 13000, Long.MAX_VALUE}) {
                first.api.setPlayerTime(offset, true);
                require(first.api.isPlayerTimeRelative() && first.api.getPlayerTimeOffset() == offset
                        && first.api.getPlayerTime() == base + offset, "Relative player time did not preserve its signed offset");
            }
            for (long fixed : new long[] {13200, -50, 48099}) {
                first.api.setPlayerTime(fixed, false);
                require(!first.api.isPlayerTimeRelative() && first.api.getPlayerTimeOffset() == fixed
                        && first.api.getPlayerTime() == base - base % 24000 + fixed, "Fixed player time did not use the current day boundary");
            }
            require(second.api.isPlayerTimeRelative() && second.api.getPlayerTimeOffset() == 0
                    && second.api.getPlayerTime() == base && world.getFullTime() == base, "Personal time changed another player or the world clock");
            first.api.resetPlayerTime();
            require(first.api.isPlayerTimeRelative() && first.api.getPlayerTimeOffset() == 0
                    && first.api.getPlayerTime() == base, "Personal time reset did not restore the world clock");
        });
        check.accept("player-personal-weather-default-no-override", () -> {
            require(actor(world, "RainProbeA").api.getPlayerWeather() == null
                    && actor(world, "RainProbeB").api.getPlayerWeather() == null,
                    "Fresh player reports a weather override instead of inheriting the world");
        });
        check.accept("player-sleeping-ignored-flags-and-native-status", () -> {
            var first = actor(world, "SleepProbeA");
            var second = actor(world, "SleepProbeB");
            require(!first.api.isSleepingIgnored() && !second.api.isSleepingIgnored(), "Fresh player is ignored for sleep");
            try {
                first.api.setSleepingIgnored(true);
                require(first.api.isSleepingIgnored() && !second.api.isSleepingIgnored(), "Sleep-ignore flag did not roundtrip independently");
                Class<?> statusType = Class.forName("net.minecraft.server.players.SleepStatus");
                Object status = statusType.getConstructor().newInstance();
                var players = List.of(first.handle, second.handle);
                var update = statusType.getMethod("update", List.class);
                var enough = statusType.getMethod("areEnoughSleeping", int.class);
                var deep = statusType.getMethod("areEnoughDeepSleeping", int.class, List.class);
                require(!(Boolean) update.invoke(status, players), "Ignored-only players announced real sleep");
                require((Boolean) enough.invoke(status, 50) && !(Boolean) enough.invoke(status, 100), "Native sleep threshold ignored the API flag");
                second.api.setSleepingIgnored(true);
                require(!(Boolean) update.invoke(status, players) && (Boolean) enough.invoke(status, 100)
                        && !(Boolean) deep.invoke(status, 100, players), "All-ignored players skipped the real deep-sleeper requirement");
                first.api.setSleepingIgnored(false);
                var head = world.getSpawnLocation().add(0, 6, 0).getBlock();
                var foot = head.getRelative(org.bukkit.block.BlockFace.WEST);
                var savedHead = head.getState();
                var savedFoot = foot.getState();
                try {
                    foot.setBlockData(org.bukkit.Bukkit.createBlockData("minecraft:red_bed[facing=east,part=foot]"), false);
                    head.setBlockData(org.bukkit.Bukkit.createBlockData("minecraft:red_bed[facing=east,part=head]"), false);
                    Class<?> pos = Class.forName("net.minecraft.core.BlockPos");
                    Object bed = pos.getConstructor(int.class, int.class, int.class).newInstance(head.getX(), head.getY(), head.getZ());
                    first.handle.getClass().getMethod("startSleeping", pos).invoke(first.handle, bed);
                    Field timer = Class.forName("net.minecraft.world.entity.player.Player").getDeclaredField("sleepCounter");
                    timer.setAccessible(true);
                    timer.setInt(first.handle, 200);
                    require((Boolean) first.handle.getClass().getMethod("isSleeping").invoke(first.handle), "Native fixture did not enter bed sleep");
                    require((Boolean) first.handle.getClass().getMethod("isSleepingLongEnough").invoke(first.handle),
                            "Native fixture is not deep sleeping with timer=" + timer.getInt(first.handle));
                    update.invoke(status, players);
                    require((Boolean) deep.invoke(status, 100, players), "A real deep sleeper plus an ignored player did not meet the threshold");
                    second.api.setSleepingIgnored(false);
                    require((Boolean) update.invoke(status, players) && !(Boolean) enough.invoke(status, 100)
                            && !(Boolean) deep.invoke(status, 100, players), "Clearing ignore did not restore native sleep accounting");
                } finally {
                    savedFoot.update(true, false);
                    savedHead.update(true, false);
                }
            } finally {
                first.api.setSleepingIgnored(false);
                second.api.setSleepingIgnored(false);
            }
        });
    }

    private static Actor actor(World world, String name) throws Exception {
        Object level = world.getClass().getMethod("getHandle").invoke(world);
        Object server = level.getClass().getMethod("getServer").invoke(level);
        Class<?> profile = Class.forName("com.mojang.authlib.GameProfile");
        Object identity = profile.getConstructor(UUID.class, String.class).newInstance(UUID.randomUUID(), name);
        Class<?> information = Class.forName("net.minecraft.server.level.ClientInformation");
        Object options = information.getMethod("createDefault").invoke(null);
        Object nativePlayer = Class.forName("net.minecraft.server.level.ServerPlayer")
                .getConstructor(Class.forName("net.minecraft.server.MinecraftServer"), Class.forName("net.minecraft.server.level.ServerLevel"), profile, information)
                .newInstance(server, level, identity, options);
        Player player = (Player) nativePlayer.getClass().getMethod("getBukkitEntity").invoke(nativePlayer);
        require(!player.isOnline(), "State fixture unexpectedly registered an online player");
        return new Actor(nativePlayer, player);
    }

    private record Actor(Object handle, Player api) { }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
