package org.cardboardpowered.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ChunkLifecycleVisibilityTest {
    @Test
    void deferredEventsPreservePaperVisibilityThroughUnloadDispatch() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object chunk = new Object();
        dispatchNewLoad(visibility, "world:1,2", chunk);
        ChunkLifecycleVisibility.UnloadHandle<Object> unload =
            visibility.beginUnload("world:1,2", chunk);

        assertTrue(unload.newLogicalUnload());
        assertTrue(visibility.isVisible("world:1,2"));
        assertTrue(visibility.isUnloadPendingOrDispatching("world:1,2"));
        assertTrue(visibility.dispatchUnload("world:1,2", unload, () -> {
            assertTrue(visibility.isVisible("world:1,2"));
            assertTrue(visibility.isUnloadPendingOrDispatching("world:1,2"));
        }));

        assertFalse(visibility.isVisible("world:1,2"));
        assertFalse(visibility.isUnloadPendingOrDispatching("world:1,2"));
    }

    @Test
    void unloadDispatchAlwaysTransitionsAfterListenerFailure() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object chunk = new Object();
        dispatchNewLoad(visibility, "world:3,4", chunk);
        ChunkLifecycleVisibility.UnloadHandle<Object> unload =
            visibility.beginUnload("world:3,4", chunk);

        assertThrows(
            IllegalStateException.class,
            () -> visibility.dispatchUnload("world:3,4", unload, () -> {
                throw new IllegalStateException("listener failure");
            })
        );

        assertFalse(visibility.isVisible("world:3,4"));
        visibility.markPhysicallyUnloaded("world:3,4", chunk);
        assertTrue(visibility.beginLoad("world:3,4", chunk).newLogicalLoad());
    }

    @Test
    void reloadDuringDispatchKeepsTheNewGenerationVisible() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object chunk = new Object();
        dispatchNewLoad(visibility, "world:5,6", chunk);
        ChunkLifecycleVisibility.UnloadHandle<Object> unload =
            visibility.beginUnload("world:5,6", chunk);

        visibility.dispatchUnload("world:5,6", unload, () ->
            dispatchNewLoad(visibility, "world:5,6", chunk)
        );

        assertTrue(visibility.isVisible("world:5,6"));
        assertFalse(visibility.isUnloadPendingOrDispatching("world:5,6"));
    }

    @Test
    void worldCleanupRemovesOnlyMatchingKeys() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        dispatchNewLoad(visibility, "world-a:1,1", new Object());
        dispatchNewLoad(visibility, "world-b:1,1", new Object());

        visibility.removeIf(key -> key.startsWith("world-a:"));

        assertFalse(visibility.isVisible("world-a:1,1"));
        assertTrue(visibility.isVisible("world-b:1,1"));
    }

    @Test
    void physicalCleanupCannotRemoveAReplacementOwner() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object oldChunk = new Object();
        Object replacementChunk = new Object();
        dispatchNewLoad(visibility, "world:7,8", oldChunk);
        dispatchNewUnload(visibility, "world:7,8", oldChunk);
        dispatchNewLoad(visibility, "world:7,8", replacementChunk);

        visibility.markPhysicallyUnloaded("world:7,8", oldChunk);
        assertTrue(visibility.isVisible("world:7,8"));
    }

    @Test
    void duplicateLoadDoesNotStartANewLogicalCycle() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object chunk = new Object();
        ChunkLifecycleVisibility.LoadHandle<Object> first =
            visibility.beginLoad("world:9,10", chunk);
        ChunkLifecycleVisibility.LoadHandle<Object> duplicate =
            visibility.beginLoad("world:9,10", chunk);

        assertTrue(first.newLogicalLoad());
        assertFalse(duplicate.newLogicalLoad());
        assertTrue(visibility.dispatchLoad("world:9,10", first, () -> { }));
    }

    @Test
    void latePhysicalCleanupCannotEraseSameOwnerReload() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object chunk = new Object();
        dispatchNewLoad(visibility, "world:11,12", chunk);
        dispatchNewUnload(visibility, "world:11,12", chunk);
        dispatchNewLoad(visibility, "world:11,12", chunk);

        visibility.markPhysicallyUnloaded("world:11,12", chunk);
        assertTrue(visibility.isVisible("world:11,12"));
        dispatchNewUnload(visibility, "world:11,12", chunk);
        assertFalse(visibility.isVisible("world:11,12"));
    }

    @Test
    void duplicateUnloadIsRejectedAndPhysicalCleanupWaitsForListener() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object chunk = new Object();
        dispatchNewLoad(visibility, "world:13,14", chunk);
        ChunkLifecycleVisibility.UnloadHandle<Object> unload =
            visibility.beginUnload("world:13,14", chunk);

        assertTrue(visibility.dispatchUnload("world:13,14", unload, () -> {
            assertFalse(visibility.beginUnload("world:13,14", chunk).newLogicalUnload());
            visibility.markPhysicallyUnloaded("world:13,14", chunk);
            assertTrue(visibility.isVisible("world:13,14"));
        }));

        assertFalse(visibility.isVisible("world:13,14"));
    }

    @Test
    void demotionBeforeDeferredLoadSuppressesBothEvents() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object chunk = new Object();
        ChunkLifecycleVisibility.LoadHandle<Object> pending =
            visibility.beginLoad("world:15,16", chunk);

        assertFalse(visibility.beginUnload("world:15,16", chunk).newLogicalUnload());
        assertFalse(visibility.dispatchLoad("world:15,16", pending, () -> {
            throw new AssertionError("cancelled load event must not run");
        }));
        assertFalse(visibility.isVisible("world:15,16"));
    }

    @Test
    void reloadBeforeDeferredUnloadCancelsTheOldUnloadGeneration() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object chunk = new Object();
        dispatchNewLoad(visibility, "world:17,18", chunk);
        ChunkLifecycleVisibility.UnloadHandle<Object> oldUnload =
            visibility.beginUnload("world:17,18", chunk);
        ChunkLifecycleVisibility.LoadHandle<Object> collapsedReload =
            visibility.beginLoad("world:17,18", chunk);

        assertFalse(collapsedReload.newLogicalLoad());
        assertFalse(visibility.dispatchUnload("world:17,18", oldUnload, () -> {
            throw new AssertionError("stale unload event must not run");
        }));
        assertTrue(visibility.isVisible("world:17,18"));
    }

    @Test
    void unloadReloadUnloadBeforeDrainDeliversExactlyOneUnload() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object chunk = new Object();
        dispatchNewLoad(visibility, "world:19,20", chunk);
        ChunkLifecycleVisibility.UnloadHandle<Object> firstUnload =
            visibility.beginUnload("world:19,20", chunk);
        assertFalse(visibility.beginLoad("world:19,20", chunk).newLogicalLoad());
        ChunkLifecycleVisibility.UnloadHandle<Object> finalUnload =
            visibility.beginUnload("world:19,20", chunk);
        int[] delivered = {0};

        assertTrue(visibility.dispatchUnload(
            "world:19,20",
            firstUnload,
            () -> delivered[0]++
        ));
        assertFalse(visibility.dispatchUnload(
            "world:19,20",
            finalUnload,
            () -> delivered[0]++
        ));
        assertTrue(delivered[0] == 1);
        assertFalse(visibility.isVisible("world:19,20"));
    }

    @Test
    void physicalUnloadFlushesOldGenerationBeforeReplacementLoad() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object oldChunk = new Object();
        Object replacementChunk = new Object();
        dispatchNewLoad(visibility, "world:21,22", oldChunk);
        ChunkLifecycleVisibility.UnloadHandle<Object> oldUnload =
            visibility.beginUnload("world:21,22", oldChunk);
        int[] sequence = {0};

        visibility.markPhysicallyUnloaded("world:21,22", oldChunk);
        assertTrue(visibility.dispatchUnload("world:21,22", oldUnload, () -> {
            assertTrue(sequence[0]++ == 0);
        }));
        ChunkLifecycleVisibility.LoadHandle<Object> replacement =
            visibility.beginLoad("world:21,22", replacementChunk);
        assertTrue(visibility.dispatchLoad("world:21,22", replacement, () -> {
            assertTrue(sequence[0]++ == 1);
        }));

        assertTrue(sequence[0] == 2);
        assertTrue(visibility.isVisible("world:21,22"));
    }

    @Test
    void physicalUnloadThenTransientReplacementEndsWithOnlyTheOldUnload() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object oldChunk = new Object();
        Object replacementChunk = new Object();
        dispatchNewLoad(visibility, "world:23,24", oldChunk);
        ChunkLifecycleVisibility.UnloadHandle<Object> oldUnload =
            visibility.beginUnload("world:23,24", oldChunk);
        int[] unloadEvents = {0};

        visibility.markPhysicallyUnloaded("world:23,24", oldChunk);
        assertTrue(visibility.dispatchUnload(
            "world:23,24",
            oldUnload,
            () -> unloadEvents[0]++
        ));
        visibility.beginLoad("world:23,24", replacementChunk);
        assertFalse(
            visibility.beginUnload("world:23,24", replacementChunk).newLogicalUnload()
        );

        assertTrue(unloadEvents[0] == 1);
        assertFalse(visibility.isVisible("world:23,24"));
    }

    @Test
    void replacementBeforeDrainPreservesOldUnloadThenNewLoad() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object oldChunk = new Object();
        Object replacementChunk = new Object();
        dispatchNewLoad(visibility, "world:25,26", oldChunk);
        ChunkLifecycleVisibility.UnloadHandle<Object> oldUnload =
            visibility.beginUnload("world:25,26", oldChunk);
        ChunkLifecycleVisibility.LoadHandle<Object> replacementLoad =
            visibility.beginLoad("world:25,26", replacementChunk);
        int[] sequence = {0};

        assertTrue(replacementLoad.newLogicalLoad());
        assertTrue(visibility.dispatchUnload("world:25,26", oldUnload, () -> {
            assertTrue(sequence[0]++ == 0);
        }));
        assertTrue(visibility.dispatchLoad("world:25,26", replacementLoad, () -> {
            assertTrue(sequence[0]++ == 1);
        }));

        assertTrue(sequence[0] == 2);
        assertTrue(visibility.isVisible("world:25,26"));
        assertTrue(visibility.visibleOwner("world:25,26") == replacementChunk);
    }

    @Test
    void transientReplacementBeforeDrainStillDeliversOldUnload() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object oldChunk = new Object();
        Object replacementChunk = new Object();
        dispatchNewLoad(visibility, "world:27,28", oldChunk);
        ChunkLifecycleVisibility.UnloadHandle<Object> oldUnload =
            visibility.beginUnload("world:27,28", oldChunk);
        ChunkLifecycleVisibility.LoadHandle<Object> replacementLoad =
            visibility.beginLoad("world:27,28", replacementChunk);

        assertTrue(replacementLoad.newLogicalLoad());
        assertFalse(
            visibility.beginUnload("world:27,28", replacementChunk).newLogicalUnload()
        );
        assertTrue(visibility.dispatchUnload("world:27,28", oldUnload, () -> { }));
        assertFalse(visibility.dispatchLoad("world:27,28", replacementLoad, () -> {
            throw new AssertionError("transient replacement load must not run");
        }));
        assertFalse(visibility.isVisible("world:27,28"));
    }

    @Test
    void listenerDoesNotHoldTheLifecycleTransitionMonitor() {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object oldChunk = new Object();
        Object replacementChunk = new Object();
        dispatchNewLoad(visibility, "world:29,30", oldChunk);
        ChunkLifecycleVisibility.UnloadHandle<Object> oldUnload =
            visibility.beginUnload("world:29,30", oldChunk);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            () -> assertTrue(visibility.dispatchUnload("world:29,30", oldUnload, () -> {
                CompletableFuture<ChunkLifecycleVisibility.LoadHandle<Object>> transition =
                    CompletableFuture.supplyAsync(
                        () -> visibility.beginLoad("world:29,30", replacementChunk)
                    );
                assertTrue(transition.orTimeout(1, TimeUnit.SECONDS).join().newLogicalLoad());
            }))
        );
    }

    @Test
    void transitionPublicationCannotReorderUnloadAndReplacementLoad() throws Exception {
        ChunkLifecycleVisibility<String, Object> visibility = new ChunkLifecycleVisibility<>();
        Object oldChunk = new Object();
        Object replacementChunk = new Object();
        dispatchNewLoad(visibility, "world:31,32", oldChunk);
        CountDownLatch unloadPublisherEntered = new CountDownLatch(1);
        CountDownLatch releaseUnloadPublisher = new CountDownLatch(1);
        List<String> publicationOrder = new ArrayList<>();

        CompletableFuture<ChunkLifecycleVisibility.UnloadHandle<Object>> unload =
            CompletableFuture.supplyAsync(() -> visibility.beginUnload(
                "world:31,32",
                oldChunk,
                handle -> {
                    unloadPublisherEntered.countDown();
                    try {
                        assertTrue(releaseUnloadPublisher.await(1, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                    publicationOrder.add("unload");
                }
            ));
        assertTrue(unloadPublisherEntered.await(1, TimeUnit.SECONDS));

        CompletableFuture<ChunkLifecycleVisibility.LoadHandle<Object>> load =
            CompletableFuture.supplyAsync(() -> visibility.beginLoad(
                "world:31,32",
                replacementChunk,
                handle -> publicationOrder.add("load")
            ));
        assertFalse(load.isDone());
        releaseUnloadPublisher.countDown();

        assertTrue(unload.get(1, TimeUnit.SECONDS).newLogicalUnload());
        assertTrue(load.get(1, TimeUnit.SECONDS).newLogicalLoad());
        assertTrue(publicationOrder.equals(List.of("unload", "load")));
    }

    private static void dispatchNewLoad(
        ChunkLifecycleVisibility<String, Object> visibility,
        String key,
        Object owner
    ) {
        ChunkLifecycleVisibility.LoadHandle<Object> handle = visibility.beginLoad(key, owner);
        assertTrue(handle.newLogicalLoad());
        assertTrue(visibility.dispatchLoad(key, handle, () -> { }));
    }

    private static void dispatchNewUnload(
        ChunkLifecycleVisibility<String, Object> visibility,
        String key,
        Object owner
    ) {
        ChunkLifecycleVisibility.UnloadHandle<Object> handle = visibility.beginUnload(key, owner);
        assertTrue(handle.newLogicalUnload());
        assertTrue(visibility.dispatchUnload(key, handle, () -> { }));
    }
}
