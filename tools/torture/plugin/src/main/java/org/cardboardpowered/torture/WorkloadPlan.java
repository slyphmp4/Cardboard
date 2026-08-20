package org.cardboardpowered.torture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure-Java workload selection and coordinate planning for the torture plugin.
 */
final class WorkloadPlan {
    private static final long DEFAULT_SECONDS = 300L;
    private static final int BOUNDED_POOL_SIDE = 8;
    private static final int BOUNDED_POOL_START = 64;
    private static final int BOUNDED_POOL_STEP = 2;
    private static final int SATURATION_POOL_START = 1024;
    private static final int SATURATION_POOL_STEP = 8;
    private static final int SATURATION_ROW_WIDTH = 32_768;
    private static final long MAX_CHUNK_DRAIN_WINDOW_MILLIS = 45_000L;
    private static final List<ChunkOffset> BOUNDED_CHUNK_OFFSETS = createBoundedChunkOffsets();

    private WorkloadPlan() {
    }

    enum Profile {
        STABILITY(true, true, true, true, false),
        SCHEDULER(true, false, false, false, false),
        API(false, true, false, false, false),
        ENTITY(false, false, true, false, false),
        CHUNKS(false, false, false, true, false),
        SATURATION(false, false, false, false, true);

        private final boolean scheduler;
        private final boolean api;
        private final boolean entity;
        private final boolean chunks;
        private final boolean saturation;

        Profile(
            boolean scheduler,
            boolean api,
            boolean entity,
            boolean chunks,
            boolean saturation
        ) {
            this.scheduler = scheduler;
            this.api = api;
            this.entity = entity;
            this.chunks = chunks;
            this.saturation = saturation;
        }

        boolean scheduler() {
            return this.scheduler;
        }

        boolean api() {
            return this.api;
        }

        boolean entity() {
            return this.entity;
        }

        boolean chunks() {
            return this.chunks;
        }

        boolean saturation() {
            return this.saturation;
        }

        private static Profile fromToken(String token) {
            return switch (token.toLowerCase(Locale.ROOT)) {
                case "stability", "normal" -> STABILITY;
                case "scheduler" -> SCHEDULER;
                case "api", "data" -> API;
                case "entity" -> ENTITY;
                case "chunks", "chunk" -> CHUNKS;
                case "saturation" -> SATURATION;
                default -> throw new IllegalArgumentException("Unknown workload profile: " + token);
            };
        }
    }

    record StartRequest(long seconds, Profile profile) {
        StartRequest {
            Objects.requireNonNull(profile, "profile");
            if (seconds < 10L) {
                throw new IllegalArgumentException("Duration must be at least 10 seconds");
            }
            if (profile.chunks() && seconds < 60L) {
                throw new IllegalArgumentException(
                    "Stability and chunks profiles require at least 60 seconds"
                );
            }
        }
    }

    record ChunkOffset(int x, int z) {
    }

    enum UnloadVerificationDecision {
        RETRY,
        VERIFIED,
        LOADED_TIMEOUT,
        EVENT_ORDER_FAILURE
    }

    static StartRequest parseStartArguments(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException("Start arguments cannot be null");
        }
        if (args.length > 2) {
            throw new IllegalArgumentException("Start accepts at most a duration and a profile");
        }

        long seconds = DEFAULT_SECONDS;
        Profile profile = Profile.STABILITY;
        boolean durationSeen = false;
        boolean profileSeen = false;

        for (String rawToken : args) {
            if (rawToken == null || rawToken.isBlank()) {
                throw new IllegalArgumentException("Start arguments cannot be blank");
            }

            String token = rawToken.trim();
            try {
                long parsedSeconds = Long.parseLong(token);
                if (durationSeen) {
                    throw new IllegalArgumentException("Duration was specified more than once");
                }
                if (parsedSeconds < 10L) {
                    throw new IllegalArgumentException("Duration must be at least 10 seconds");
                }
                seconds = parsedSeconds;
                durationSeen = true;
            } catch (NumberFormatException ignored) {
                if (profileSeen) {
                    throw new IllegalArgumentException("Workload profile was specified more than once");
                }
                profile = Profile.fromToken(token);
                profileSeen = true;
            }
        }

        return new StartRequest(seconds, profile);
    }

    static List<ChunkOffset> boundedChunkOffsets() {
        return BOUNDED_CHUNK_OFFSETS;
    }

    static ChunkOffset entityAnchorOffset() {
        return BOUNDED_CHUNK_OFFSETS.get(0);
    }

    static long chunkDrainWindowMillis(long seconds) {
        if (seconds < 10L) {
            throw new IllegalArgumentException("Duration must be at least 10 seconds");
        }
        if (seconds >= 60L) {
            return MAX_CHUNK_DRAIN_WINDOW_MILLIS;
        }
        return Math.min(MAX_CHUNK_DRAIN_WINDOW_MILLIS, seconds * 500L);
    }

    static UnloadVerificationDecision evaluateChunkUnload(
        boolean chunkLoaded,
        boolean unloadEventObserved,
        long elapsedMillis,
        long timeoutMillis
    ) {
        if (elapsedMillis < 0L || timeoutMillis < 1L) {
            throw new IllegalArgumentException("Invalid chunk unload verification time");
        }
        if (!chunkLoaded && unloadEventObserved) {
            return UnloadVerificationDecision.VERIFIED;
        }
        if (!chunkLoaded) {
            return UnloadVerificationDecision.EVENT_ORDER_FAILURE;
        }
        if (elapsedMillis >= timeoutMillis) {
            return UnloadVerificationDecision.LOADED_TIMEOUT;
        }
        return UnloadVerificationDecision.RETRY;
    }

    static ChunkOffset saturationOffset(long sequence) {
        if (sequence < 0L || sequence > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Saturation sequence must be between 0 and " + Integer.MAX_VALUE
            );
        }

        int x = SATURATION_POOL_START
            + (int) (sequence % SATURATION_ROW_WIDTH) * SATURATION_POOL_STEP;
        int z = SATURATION_POOL_START
            + (int) (sequence / SATURATION_ROW_WIDTH) * SATURATION_POOL_STEP;
        return new ChunkOffset(x, z);
    }

    private static List<ChunkOffset> createBoundedChunkOffsets() {
        List<ChunkOffset> offsets = new ArrayList<>(BOUNDED_POOL_SIDE * BOUNDED_POOL_SIDE);
        for (int slot = 0; slot < BOUNDED_POOL_SIDE * BOUNDED_POOL_SIDE; slot++) {
            int x = BOUNDED_POOL_START + (slot % BOUNDED_POOL_SIDE) * BOUNDED_POOL_STEP;
            int z = BOUNDED_POOL_START + (slot / BOUNDED_POOL_SIDE) * BOUNDED_POOL_STEP;
            offsets.add(new ChunkOffset(x, z));
        }
        return List.copyOf(offsets);
    }
}
