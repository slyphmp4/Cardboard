package org.cardboardpowered.mixin.server;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

import net.minecraft.server.MinecraftServer;

import org.cardboardpowered.bridge.server.MinecraftServerTickMetricsBridge;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper-compatible server tick metrics for Cardboard.
 *
 * <p>Minecraft already keeps the processing time of the most recent
 * server ticks in {@code tickTimesNanos}. We reuse that data for
 * getTickTimes() and getAverageTickTime().</p>
 *
 * <p>Vanilla does not expose Paper's 1/5/15 minute TPS statistics,
 * so Cardboard records the wall-clock start time of server ticks and
 * calculates effective TPS from those timestamps.</p>
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTickMetricsMixin
        implements MinecraftServerTickMetricsBridge {

    @Unique
    private static final long CARDBOARD_NANOS_PER_SECOND = 1_000_000_000L;

    @Unique
    private static final long CARDBOARD_ONE_MINUTE =
            60L * CARDBOARD_NANOS_PER_SECOND;

    @Unique
    private static final long CARDBOARD_FIVE_MINUTES =
            5L * CARDBOARD_ONE_MINUTE;

    @Unique
    private static final long CARDBOARD_FIFTEEN_MINUTES =
            15L * CARDBOARD_ONE_MINUTE;

    /**
     * At normal 20 TPS this stores roughly 54 minutes of tick starts,
     * which is comfortably larger than Paper's 15 minute TPS window.
     */
    @Unique
    private static final int CARDBOARD_TICK_HISTORY_SIZE = 65_536;

    /**
     * Bukkit/Paper historically report normal server TPS capped at 20.
     */
    @Unique
    private static final double CARDBOARD_MAX_TPS = 20.0D;

    /**
     * Vanilla Minecraft 26.2 keeps the most recent 100 tick processing
     * durations in this array.
     */
    @Shadow
    @Final
    private long[] tickTimesNanos;

    @Unique
    private final long[] cardboard$tickStarts =
            new long[CARDBOARD_TICK_HISTORY_SIZE];

    @Unique
    private final Object cardboard$tickMetricsLock = new Object();

    @Unique
    private int cardboard$tickHistoryWriteIndex;

    @Unique
    private int cardboard$tickHistoryCount;

    @Unique
    private long cardboard$firstTickStartNanos = -1L;

    /**
     * tickChildren is called once for every normal server tick.
     *
     * <p>Cardboard already hooks this exact Minecraft 26.2 method in
     * MinecraftServerMixin for the Bukkit scheduler, so this avoids
     * introducing a new fragile injection target.</p>
     */
    @Inject(
            method = "tickChildren",
            at = @At("HEAD")
    )
    private void cardboard$recordTickStart(
            final BooleanSupplier hasTimeLeft,
            final CallbackInfo ci
    ) {
        final long now = System.nanoTime();

        synchronized (this.cardboard$tickMetricsLock) {
            if (this.cardboard$firstTickStartNanos < 0L) {
                this.cardboard$firstTickStartNanos = now;
            }

            this.cardboard$tickStarts[this.cardboard$tickHistoryWriteIndex] = now;

            this.cardboard$tickHistoryWriteIndex++;

            if (this.cardboard$tickHistoryWriteIndex
                    == CARDBOARD_TICK_HISTORY_SIZE) {
                this.cardboard$tickHistoryWriteIndex = 0;
            }

            if (this.cardboard$tickHistoryCount
                    < CARDBOARD_TICK_HISTORY_SIZE) {
                this.cardboard$tickHistoryCount++;
            }
        }
    }

    @Override
    public long[] cardboard$getTickTimes() {
        /*
         * Minecraft's array is already the rolling tick-duration
         * history. Return only populated entries during early startup
         * instead of exposing zero-filled slots.
         */
        final long[] snapshot = this.tickTimesNanos.clone();

        int validCount = 0;

        for (final long tickTime : snapshot) {
            if (tickTime > 0L) {
                validCount++;
            }
        }

        if (validCount == snapshot.length) {
            return snapshot;
        }

        if (validCount == 0) {
            return new long[0];
        }

        final long[] populated = new long[validCount];
        int outputIndex = 0;

        for (final long tickTime : snapshot) {
            if (tickTime > 0L) {
                populated[outputIndex++] = tickTime;
            }
        }

        return populated;
    }

    @Override
    public double cardboard$getAverageTickTime() {
        final long[] snapshot = this.tickTimesNanos;

        long totalNanos = 0L;
        int samples = 0;

        for (final long tickTime : snapshot) {
            if (tickTime <= 0L) {
                continue;
            }

            /*
             * Avoid overflowing the accumulator if something extremely
             * abnormal happens. With Minecraft's 100-sample history this
             * should effectively never trigger during normal operation.
             */
            if (Long.MAX_VALUE - totalNanos < tickTime) {
                return Arrays.stream(snapshot)
                        .filter(value -> value > 0L)
                        .average()
                        .orElse(0.0D)
                        / 1_000_000.0D;
            }

            totalNanos += tickTime;
            samples++;
        }

        if (samples == 0) {
            return 0.0D;
        }

        return ((double) totalNanos / (double) samples)
                / 1_000_000.0D;
    }

    @Override
    public double[] cardboard$getTPS() {
        final long now = System.nanoTime();

        synchronized (this.cardboard$tickMetricsLock) {
            if (this.cardboard$tickHistoryCount == 0
                    || this.cardboard$firstTickStartNanos < 0L) {
                return new double[] {
                        CARDBOARD_MAX_TPS,
                        CARDBOARD_MAX_TPS,
                        CARDBOARD_MAX_TPS
                };
            }

            return new double[] {
                    this.cardboard$calculateTPS(now, CARDBOARD_ONE_MINUTE),
                    this.cardboard$calculateTPS(now, CARDBOARD_FIVE_MINUTES),
                    this.cardboard$calculateTPS(now, CARDBOARD_FIFTEEN_MINUTES)
            };
        }
    }

    /**
     * Calculates effective TPS using server tick start timestamps.
     *
     * <p>The caller must hold cardboard$tickMetricsLock.</p>
     */
    @Unique
    private double cardboard$calculateTPS(
            final long now,
            final long windowNanos
    ) {
        final long desiredStart = now - windowNanos;

        /*
         * During the first few minutes after startup we use the actual
         * server age instead of pretending a full 1/5/15 minute history
         * already exists.
         */
        final long measurementStart = Math.max(
                this.cardboard$firstTickStartNanos,
                desiredStart
        );

        int ticksInWindow = 0;

        for (int offset = 0;
             offset < this.cardboard$tickHistoryCount;
             offset++) {

            int index =
                    this.cardboard$tickHistoryWriteIndex - 1 - offset;

            if (index < 0) {
                index += CARDBOARD_TICK_HISTORY_SIZE;
            }

            final long tickStart = this.cardboard$tickStarts[index];

            if (tickStart < measurementStart) {
                break;
            }

            ticksInWindow++;
        }

        final long elapsedNanos = now - measurementStart;

        /*
         * This can happen on the first sampled tick. Returning the normal
         * target TPS is more useful than NaN/Infinity.
         */
        if (elapsedNanos <= 0L) {
            return CARDBOARD_MAX_TPS;
        }

        final double measuredTPS =
                ((double) ticksInWindow * CARDBOARD_NANOS_PER_SECOND)
                        / (double) elapsedNanos;

        if (!Double.isFinite(measuredTPS) || measuredTPS < 0.0D) {
            return 0.0D;
        }

        return Math.min(CARDBOARD_MAX_TPS, measuredTPS);
    }
}
