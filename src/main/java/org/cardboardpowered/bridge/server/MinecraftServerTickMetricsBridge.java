package org.cardboardpowered.bridge.server;

/**
 * Bridge exposing server tick metrics required by the Paper API.
 *
 * <p>The implementation is supplied by
 * MinecraftServerTickMetricsMixin.</p>
 */
public interface MinecraftServerTickMetricsBridge {

    /**
     * Recent server tick processing times in nanoseconds.
     *
     * @return snapshot of recent tick times
     */
    long[] cardboard$getTickTimes();

    /**
     * Average tick processing time over the recent tick window.
     *
     * @return average MSPT
     */
    double cardboard$getAverageTickTime();

    /**
     * Effective TPS for approximately 1, 5 and 15 minute windows.
     *
     * @return TPS array in Paper-compatible order
     */
    double[] cardboard$getTPS();
}
