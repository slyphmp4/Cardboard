package org.cardboardpowered.torture;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class WorkloadPlanTest {
    @Test
    void defaultsToFiveMinutesOfStability() {
        WorkloadPlan.StartRequest request = WorkloadPlan.parseStartArguments(new String[0]);

        assertAll(
            () -> assertEquals(300L, request.seconds()),
            () -> assertEquals(WorkloadPlan.Profile.STABILITY, request.profile())
        );
    }

    @Test
    void acceptsDurationAndProfileInEitherOrder() {
        assertEquals(
            new WorkloadPlan.StartRequest(600L, WorkloadPlan.Profile.ENTITY),
            WorkloadPlan.parseStartArguments(new String[] {"600", "entity"})
        );
        assertEquals(
            new WorkloadPlan.StartRequest(600L, WorkloadPlan.Profile.ENTITY),
            WorkloadPlan.parseStartArguments(new String[] {"ENTITY", "600"})
        );
        assertEquals(
            new WorkloadPlan.StartRequest(60L, WorkloadPlan.Profile.STABILITY),
            WorkloadPlan.parseStartArguments(new String[] {"60"})
        );
        assertEquals(
            new WorkloadPlan.StartRequest(300L, WorkloadPlan.Profile.SCHEDULER),
            WorkloadPlan.parseStartArguments(new String[] {"scheduler"})
        );
        assertEquals(
            new WorkloadPlan.StartRequest(30L, WorkloadPlan.Profile.SCHEDULER),
            WorkloadPlan.parseStartArguments(new String[] {"30", "scheduler"})
        );
    }

    @Test
    void supportsCompatibilityAliases() {
        assertEquals(
            WorkloadPlan.Profile.STABILITY,
            WorkloadPlan.parseStartArguments(new String[] {"normal"}).profile()
        );
        assertEquals(
            WorkloadPlan.Profile.CHUNKS,
            WorkloadPlan.parseStartArguments(new String[] {"chunk"}).profile()
        );
        assertEquals(
            WorkloadPlan.Profile.API,
            WorkloadPlan.parseStartArguments(new String[] {"data"}).profile()
        );
    }

    @Test
    void rejectsInvalidStartArguments() {
        List<String[]> invalidArguments = List.of(
            new String[] {"9"},
            new String[] {"unknown"},
            new String[] {"300", "600"},
            new String[] {"api", "entity"},
            new String[] {"300", "api", "extra"},
            new String[] {"45"},
            new String[] {"45", "chunks"},
            new String[] {""}
        );

        for (String[] arguments : invalidArguments) {
            assertThrows(
                IllegalArgumentException.class,
                () -> WorkloadPlan.parseStartArguments(arguments)
            );
        }
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkloadPlan.parseStartArguments(null)
        );
    }

    @Test
    void profilesSelectOnlyTheirIntendedWorkloads() {
        assertSelectors(WorkloadPlan.Profile.STABILITY, true, true, true, true, false);
        assertSelectors(WorkloadPlan.Profile.SCHEDULER, true, false, false, false, false);
        assertSelectors(WorkloadPlan.Profile.API, false, true, false, false, false);
        assertSelectors(WorkloadPlan.Profile.ENTITY, false, false, true, false, false);
        assertSelectors(WorkloadPlan.Profile.CHUNKS, false, false, false, true, false);
        assertSelectors(WorkloadPlan.Profile.SATURATION, false, false, false, false, true);
    }

    @Test
    void boundedChunkPoolMatchesTheExistingEightByEightGrid() {
        List<WorkloadPlan.ChunkOffset> offsets = WorkloadPlan.boundedChunkOffsets();

        assertEquals(64, offsets.size());
        assertEquals(64, new HashSet<>(offsets).size());
        for (int slot = 0; slot < offsets.size(); slot++) {
            assertEquals(
                new WorkloadPlan.ChunkOffset(
                    64 + (slot % 8) * 2,
                    64 + (slot / 8) * 2
                ),
                offsets.get(slot)
            );
        }
        assertThrows(
            UnsupportedOperationException.class,
            () -> offsets.add(new WorkloadPlan.ChunkOffset(80, 80))
        );
        assertEquals(offsets.get(0), WorkloadPlan.entityAnchorOffset());
    }

    @Test
    void chunkDrainWindowLeavesWorkTimeForShortProfiles() {
        assertAll(
            () -> assertEquals(5_000L, WorkloadPlan.chunkDrainWindowMillis(10L)),
            () -> assertEquals(15_000L, WorkloadPlan.chunkDrainWindowMillis(30L)),
            () -> assertEquals(45_000L, WorkloadPlan.chunkDrainWindowMillis(60L)),
            () -> assertEquals(45_000L, WorkloadPlan.chunkDrainWindowMillis(90L)),
            () -> assertEquals(45_000L, WorkloadPlan.chunkDrainWindowMillis(300L)),
            () -> assertThrows(
                IllegalArgumentException.class,
                () -> WorkloadPlan.chunkDrainWindowMillis(9L)
            )
        );
    }

    @Test
    void chunkUnloadVerificationEnforcesPaperEventBeforeUnloadedState() {
        assertAll(
            () -> assertEquals(
                WorkloadPlan.UnloadVerificationDecision.RETRY,
                WorkloadPlan.evaluateChunkUnload(true, false, 0L, 20L)
            ),
            () -> assertEquals(
                WorkloadPlan.UnloadVerificationDecision.EVENT_ORDER_FAILURE,
                WorkloadPlan.evaluateChunkUnload(false, false, 0L, 20L)
            ),
            () -> assertEquals(
                WorkloadPlan.UnloadVerificationDecision.RETRY,
                WorkloadPlan.evaluateChunkUnload(true, true, 0L, 20L)
            ),
            () -> assertEquals(
                WorkloadPlan.UnloadVerificationDecision.VERIFIED,
                WorkloadPlan.evaluateChunkUnload(false, true, 0L, 20L)
            ),
            () -> assertEquals(
                WorkloadPlan.UnloadVerificationDecision.LOADED_TIMEOUT,
                WorkloadPlan.evaluateChunkUnload(true, true, 20L, 20L)
            ),
            () -> assertEquals(
                WorkloadPlan.UnloadVerificationDecision.EVENT_ORDER_FAILURE,
                WorkloadPlan.evaluateChunkUnload(false, false, 20L, 20L)
            ),
            () -> assertThrows(
                IllegalArgumentException.class,
                () -> WorkloadPlan.evaluateChunkUnload(false, true, -1L, 20L)
            ),
            () -> assertThrows(
                IllegalArgumentException.class,
                () -> WorkloadPlan.evaluateChunkUnload(false, true, 0L, 0L)
            )
        );
    }

    @Test
    void saturationOffsetsAreUniqueAndOutsideTheBoundedPool() {
        Set<WorkloadPlan.ChunkOffset> bounded = new HashSet<>(WorkloadPlan.boundedChunkOffsets());
        Set<WorkloadPlan.ChunkOffset> saturation = new HashSet<>();

        for (long sequence = 0L; sequence < 10_000L; sequence++) {
            WorkloadPlan.ChunkOffset offset = WorkloadPlan.saturationOffset(sequence);
            assertTrue(saturation.add(offset), "duplicate saturation offset at sequence " + sequence);
            assertFalse(bounded.contains(offset), "saturation offset entered bounded pool");
        }

        assertThrows(IllegalArgumentException.class, () -> WorkloadPlan.saturationOffset(-1L));
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkloadPlan.saturationOffset((long) Integer.MAX_VALUE + 1L)
        );
    }

    private static void assertSelectors(
        WorkloadPlan.Profile profile,
        boolean scheduler,
        boolean api,
        boolean entity,
        boolean chunks,
        boolean saturation
    ) {
        assertAll(
            () -> assertEquals(scheduler, profile.scheduler()),
            () -> assertEquals(api, profile.api()),
            () -> assertEquals(entity, profile.entity()),
            () -> assertEquals(chunks, profile.chunks()),
            () -> assertEquals(saturation, profile.saturation())
        );
    }
}
