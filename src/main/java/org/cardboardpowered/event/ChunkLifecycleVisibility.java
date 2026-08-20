package org.cardboardpowered.event;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Tracks the logical Bukkit lifecycle independently from later physical eviction.
 */
final class ChunkLifecycleVisibility<K, O> {
    private final ConcurrentHashMap<K, VisibilityState<O>> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ClaimedUnload<K, O>, Boolean> claimedUnloads =
        new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();

    synchronized LoadHandle<O> beginLoad(K key, O owner) {
        return beginLoad(key, owner, ignored -> { });
    }

    synchronized LoadHandle<O> beginLoad(
        K key,
        O owner,
        Consumer<LoadHandle<O>> publish
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(publish, "publish");

        while (true) {
            VisibilityState<O> current = this.states.get(key);
            if (current != null) {
                if (current.owner() == owner
                    && (current.phase() == Phase.PENDING_LOAD
                        || current.phase() == Phase.VISIBLE)) {
                    return new LoadHandle<>(current.generation(), owner, false);
                }
                if (current.phase() == Phase.PENDING_UNLOAD
                    && !current.physicallyUnloaded()) {
                    if (current.owner() != owner) {
                        claimUnload(key, current);
                        VisibilityState<O> pending = new VisibilityState<>(
                            nextGeneration(),
                            owner,
                            Phase.PENDING_LOAD,
                            false
                        );
                        this.states.put(key, pending);
                        LoadHandle<O> handle =
                            new LoadHandle<>(pending.generation(), owner, true);
                        publish.accept(handle);
                        return handle;
                    }
                    if (!this.states.replace(key, current, current.withPhase(Phase.VISIBLE))) {
                        continue;
                    }
                    return new LoadHandle<>(current.generation(), owner, false);
                }
            }

            VisibilityState<O> pending = new VisibilityState<>(
                nextGeneration(),
                owner,
                Phase.PENDING_LOAD,
                false
            );
            boolean stored = current == null
                ? this.states.putIfAbsent(key, pending) == null
                : this.states.replace(key, current, pending);
            if (stored) {
                LoadHandle<O> handle =
                    new LoadHandle<>(pending.generation(), owner, true);
                publish.accept(handle);
                return handle;
            }
        }
    }

    boolean dispatchLoad(K key, LoadHandle<O> handle, Runnable dispatch) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(dispatch, "dispatch");
        if (!handle.newLogicalLoad()) {
            return false;
        }

        synchronized (this) {
            VisibilityState<O> current = this.states.get(key);
            if (!matches(current, handle.generation(), handle.owner(), Phase.PENDING_LOAD)
                || current.physicallyUnloaded()) {
                return false;
            }
            this.states.put(key, current.withPhase(Phase.VISIBLE));
        }

        dispatch.run();
        return true;
    }

    synchronized UnloadHandle<O> beginUnload(K key, O owner) {
        return beginUnload(key, owner, ignored -> { });
    }

    synchronized UnloadHandle<O> beginUnload(
        K key,
        O owner,
        Consumer<UnloadHandle<O>> publish
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(publish, "publish");

        while (true) {
            VisibilityState<O> current = this.states.get(key);
            if (current == null || current.owner() != owner) {
                return UnloadHandle.none(owner);
            }
            if (current.phase() == Phase.PENDING_LOAD) {
                if (current.physicallyUnloaded()) {
                    this.states.remove(key, current);
                } else if (!this.states.replace(key, current, current.withPhase(Phase.INACCESSIBLE))) {
                    continue;
                }
                return UnloadHandle.none(owner);
            }
            if (current.phase() != Phase.VISIBLE) {
                return UnloadHandle.none(owner);
            }

            VisibilityState<O> pending = current.withPhase(Phase.PENDING_UNLOAD);
            if (!this.states.replace(key, current, pending)) {
                continue;
            }
            UnloadHandle<O> handle =
                new UnloadHandle<>(pending.generation(), owner, true);
            publish.accept(handle);
            return handle;
        }
    }

    boolean dispatchUnload(K key, UnloadHandle<O> handle, Runnable dispatch) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(dispatch, "dispatch");
        if (!handle.newLogicalUnload()) {
            return false;
        }

        boolean detached;
        synchronized (this) {
            detached = this.claimedUnloads.remove(
                new ClaimedUnload<>(key, handle.generation(), handle.owner())
            ) != null;
            if (detached) {
                // The replacement generation owns the map entry; dispatch via its claim.
            } else {
            VisibilityState<O> current = this.states.get(key);
            if (!matches(current, handle.generation(), handle.owner(), Phase.PENDING_UNLOAD)) {
                return false;
            }
                this.states.put(key, current.withPhase(Phase.UNLOADING));
            }
        }

        if (detached) {
            dispatch.run();
            return true;
        }

        try {
            dispatch.run();
        } finally {
            synchronized (this) {
                VisibilityState<O> latest = this.states.get(key);
                if (matches(latest, handle.generation(), handle.owner(), Phase.UNLOADING)) {
                    if (latest.physicallyUnloaded()) {
                        this.states.remove(key, latest);
                    } else {
                        this.states.replace(key, latest, latest.withPhase(Phase.INACCESSIBLE));
                    }
                }
            }
        }
        return true;
    }

    boolean isVisible(K key) {
        VisibilityState<O> state = this.states.get(Objects.requireNonNull(key, "key"));
        return state != null && state.phase() != Phase.INACCESSIBLE;
    }

    boolean isUnloadPendingOrDispatching(K key) {
        VisibilityState<O> state = this.states.get(Objects.requireNonNull(key, "key"));
        return state != null
            && (state.phase() == Phase.PENDING_UNLOAD || state.phase() == Phase.UNLOADING);
    }

    O visibleOwner(K key) {
        VisibilityState<O> state = this.states.get(Objects.requireNonNull(key, "key"));
        if (state == null || state.phase() == Phase.INACCESSIBLE) {
            return null;
        }
        return state.owner();
    }

    synchronized void markPhysicallyUnloaded(K key, O owner) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(owner, "owner");
        this.states.computeIfPresent(key, (ignored, state) -> {
            if (state.owner() != owner) {
                return state;
            }
            return switch (state.phase()) {
                case INACCESSIBLE -> null;
                case PENDING_UNLOAD -> {
                    claimUnload(key, state);
                    yield null;
                }
                case UNLOADING -> state.withPhysicallyUnloaded();
                case PENDING_LOAD, VISIBLE -> state;
            };
        });
    }

    synchronized void removeIf(Predicate<K> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        this.states.keySet().removeIf(predicate);
        this.claimedUnloads.keySet().removeIf(claimed -> predicate.test(claimed.key));
    }

    private long nextGeneration() {
        return this.generations.incrementAndGet();
    }

    private void claimUnload(K key, VisibilityState<O> state) {
        this.claimedUnloads.put(
            new ClaimedUnload<>(key, state.generation(), state.owner()),
            Boolean.TRUE
        );
    }

    private static <O> boolean matches(
        VisibilityState<O> state,
        long generation,
        O owner,
        Phase phase
    ) {
        return state != null
            && state.generation() == generation
            && state.owner() == owner
            && state.phase() == phase;
    }

    private enum Phase {
        PENDING_LOAD,
        VISIBLE,
        PENDING_UNLOAD,
        UNLOADING,
        INACCESSIBLE
    }

    private record VisibilityState<O>(
        long generation,
        O owner,
        Phase phase,
        boolean physicallyUnloaded
    ) {
        private VisibilityState<O> withPhase(Phase nextPhase) {
            return new VisibilityState<>(
                this.generation,
                this.owner,
                nextPhase,
                this.physicallyUnloaded
            );
        }

        private VisibilityState<O> withPhysicallyUnloaded() {
            return new VisibilityState<>(this.generation, this.owner, this.phase, true);
        }
    }

    record LoadHandle<O>(long generation, O owner, boolean newLogicalLoad) {
        LoadHandle {
            Objects.requireNonNull(owner, "owner");
        }
    }

    record UnloadHandle<O>(long generation, O owner, boolean newLogicalUnload) {
        UnloadHandle {
            Objects.requireNonNull(owner, "owner");
        }

        private static <O> UnloadHandle<O> none(O owner) {
            return new UnloadHandle<>(0L, owner, false);
        }
    }

    private static final class ClaimedUnload<K, O> {
        private final K key;
        private final long generation;
        private final O owner;

        private ClaimedUnload(K key, long generation, O owner) {
            this.key = Objects.requireNonNull(key, "key");
            this.generation = generation;
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ClaimedUnload<?, ?> claimed)) {
                return false;
            }
            return this.generation == claimed.generation
                && this.owner == claimed.owner
                && this.key.equals(claimed.key);
        }

        @Override
        public int hashCode() {
            int result = this.key.hashCode();
            result = 31 * result + Long.hashCode(this.generation);
            return 31 * result + System.identityHashCode(this.owner);
        }
    }
}
