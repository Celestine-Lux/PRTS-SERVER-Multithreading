/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tally the seam keeps of the observations its guards drop: one count per dropped call,
 * moved by no install, kept out of the host path, and readable while the host path runs
 * unchanged. The tally is read as a difference, so what another case dropped before this one
 * ran is none of this case's business. */
class PrtsChunkDemandDropTallyTest {

    private static final long TIMEOUT_SECONDS = 30L;

    @AfterEach
    void detach() {
        PrtsChunkDemand.install(null);
    }

    /** A tap that throws on every entry and counts how often each was reached before it did:
     * the reach is how a dropped observation is told apart from an event that never arrived. */
    private static final class ThrowingTap implements PrtsChunkDemand.DemandTap {

        final LongAdder started = new LongAdder();
        final LongAdder startedWithChunk = new LongAdder();
        final LongAdder finished = new LongAdder();
        final LongAdder opened = new LongAdder();
        final LongAdder descriptor = new LongAdder();
        final LongAdder stamps = new LongAdder();
        final LongAdder taken = new LongAdder();
        final LongAdder completed = new LongAdder();

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
            started.increment();
            throw new IllegalStateException("no room for this ask");
        }

        @Override
        public void demandStarted(String worldId, int chunkX, int chunkZ, String status,
                                  boolean blocking) {
            startedWithChunk.increment();
            throw new IllegalStateException("no room for this ask");
        }

        @Override
        public void demandFinished(String worldId, String status, boolean blocking,
                                   boolean satisfied) {
            finished.increment();
            throw new IllegalStateException("no room for this answer");
        }

        @Override
        public void futureOpened(String worldId, String status) {
            opened.increment();
            throw new IllegalStateException("no room for this future");
        }

        @Override
        public void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                     boolean load) {
            descriptor.increment();
            throw new IllegalStateException("no room for this future");
        }

        @Override
        public long futureOpenedAt() {
            stamps.increment();
            throw new IllegalStateException("no stamp was kept");
        }

        @Override
        public void futureTaken(String worldId, String status) {
            taken.increment();
            throw new IllegalStateException("no room for this handover");
        }

        @Override
        public void futureCompleted(String worldId, String status, long startedNanos,
                                    boolean satisfied) {
            completed.increment();
            throw new IllegalStateException("no room for this completion");
        }
    }

    /** A tap written against the seam as it was: it knows the world and the status, throws on
     * both of the old entries, and never implements the entries that carry the chunk. */
    private static final class LegacyThrowingTap implements PrtsChunkDemand.DemandTap {

        final LongAdder started = new LongAdder();
        final LongAdder opened = new LongAdder();

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
            started.increment();
            throw new IllegalStateException("no room for this ask");
        }

        @Override
        public void demandFinished(String worldId, String status, boolean blocking,
                                   boolean satisfied) {
        }

        @Override
        public void futureOpened(String worldId, String status) {
            opened.increment();
            throw new IllegalStateException("no room for this future");
        }

        @Override
        public long futureOpenedAt() {
            return -1L;
        }

        @Override
        public void futureTaken(String worldId, String status) {
        }

        @Override
        public void futureCompleted(String worldId, String status, long startedNanos,
                                    boolean satisfied) {
        }
    }

    /** A tap that answers every entry and throws on none. */
    private static final class AnsweringTap implements PrtsChunkDemand.DemandTap {

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
        }

        @Override
        public void demandFinished(String worldId, String status, boolean blocking,
                                   boolean satisfied) {
        }

        @Override
        public void futureOpened(String worldId, String status) {
        }

        @Override
        public long futureOpenedAt() {
            return -1L;
        }

        @Override
        public void futureTaken(String worldId, String status) {
        }

        @Override
        public void futureCompleted(String worldId, String status, long startedNanos,
                                    boolean satisfied) {
        }
    }

    /** A value the host computes for the watch and cannot produce. */
    private static String worldIdTheHostCannotName() {
        throw new IllegalStateException("the world is not there");
    }

    /** One call per reporting entry, as the host path makes them. */
    private static void everyEntryOnce() {
        PrtsChunkDemand.demandStarted("minecraft:overworld", "full", false);
        PrtsChunkDemand.demandStarted("minecraft:overworld", 3, -7, "full", false);
        PrtsChunkDemand.demandFinished("minecraft:overworld", "full", false, true);
        PrtsChunkDemand.futureOpened("minecraft:overworld", "full");
        PrtsChunkDemand.demandDescriptor("minecraft:overworld", 3, -7, "full", true);
        PrtsChunkDemand.futureOpenedAt();
        PrtsChunkDemand.futureTaken("minecraft:overworld", "full");
        PrtsChunkDemand.futureCompleted("minecraft:overworld", "full", 7L, true);
    }

    @Test
    void everyGuardedEntryTalliesExactlyTheDropItMade() {
        ThrowingTap tap = new ThrowingTap();
        PrtsChunkDemand.install(tap);
        long before = PrtsChunkDemand.droppedObservations();

        assertDoesNotThrow(PrtsChunkDemandDropTallyTest::everyEntryOnce,
            "no entry fails its caller while the watch throws");

        assertEquals(8L, PrtsChunkDemand.droppedObservations() - before,
            "one dropped call per entry, and no entry dropped twice");
        assertEquals(1L, tap.started.sum(), "the old ask was reached once");
        assertEquals(1L, tap.startedWithChunk.sum(), "the chunk-carrying ask was reached once");
        assertEquals(1L, tap.finished.sum(), "the answer was reached once");
        assertEquals(1L, tap.opened.sum(), "the old open was reached once");
        assertEquals(1L, tap.descriptor.sum(), "the chunk-carrying open was reached once");
        assertEquals(1L, tap.stamps.sum(), "the stamp query was reached once");
        assertEquals(1L, tap.taken.sum(), "the handover was reached once");
        assertEquals(1L, tap.completed.sum(), "the completion was reached once");
    }

    @Test
    void anAskOnTheOldEntryIsNotFailedByAThrowingWatch() {
        ThrowingTap tap = new ThrowingTap();
        PrtsChunkDemand.install(tap);
        long before = PrtsChunkDemand.droppedObservations();

        assertDoesNotThrow(() -> PrtsChunkDemand.demandStarted("minecraft:overworld", "full", true),
            "the last entry that was called straight through now behaves as the others");
        assertEquals(1L, tap.started.sum(),
            "the watch was reached once: the guard drops the failure, not the event");
        assertEquals(1L, PrtsChunkDemand.droppedObservations() - before,
            "and the drop is tallied, not silent");
    }

    @Test
    void aDropThroughADefaultIsTalliedOnce() {
        LegacyThrowingTap tap = new LegacyThrowingTap();
        PrtsChunkDemand.install(tap);
        long before = PrtsChunkDemand.droppedObservations();

        PrtsChunkDemand.demandStarted("minecraft:overworld", 1, 2, "full", true);
        PrtsChunkDemand.demandDescriptor("minecraft:overworld", 1, 2, "full", true);

        assertEquals(2L, PrtsChunkDemand.droppedObservations() - before,
            "the guard that caught the default's failure tallies it once, not once more for the entry it forwarded to");
        assertEquals(1L, tap.started.sum(), "the old ask entry was reached exactly once through the default");
        assertEquals(1L, tap.opened.sum(), "the old open entry was reached exactly once through the default");
    }

    @Test
    void aWatchThatAnswersMovesTheTallyNotAtAll() {
        PrtsChunkDemand.install(new AnsweringTap());
        long before = PrtsChunkDemand.droppedObservations();

        everyEntryOnce();

        assertEquals(0L, PrtsChunkDemand.droppedObservations() - before,
            "an observation that arrives is not a drop");
    }

    @Test
    void noWatchInstalledMovesTheTallyNotAtAll() {
        PrtsChunkDemand.install(null);
        long before = PrtsChunkDemand.droppedObservations();

        everyEntryOnce();

        assertEquals(0L, PrtsChunkDemand.droppedObservations() - before,
            "a call no watch was installed for is a no-op, not a drop");
    }

    @Test
    void theTallyOutlivesTheWatchThatMadeIt() {
        PrtsChunkDemand.install(new ThrowingTap());
        PrtsChunkDemand.futureOpened("minecraft:overworld", "full");
        long carried = PrtsChunkDemand.droppedObservations();

        PrtsChunkDemand.install(new AnsweringTap());
        assertEquals(carried, PrtsChunkDemand.droppedObservations(),
            "an install moves no tally: the drop a failed watch made stays readable after it is replaced");
        PrtsChunkDemand.futureOpened("minecraft:overworld", "full");
        assertEquals(carried, PrtsChunkDemand.droppedObservations(),
            "and the watch that answers adds none");
    }

    @Test
    void aFailureOfTheHostFrameIsNotADroppedObservation() {
        PrtsChunkDemand.install(new ThrowingTap());
        long before = PrtsChunkDemand.droppedObservations();

        assertThrows(IllegalStateException.class,
            () -> PrtsChunkDemand.demandFinished(worldIdTheHostCannotName(), "full", false, true),
            "the argument is read in the host frame before the entry: the guard covers the watch only");
        assertEquals(0L, PrtsChunkDemand.droppedObservations() - before,
            "the watch was never reached, so nothing was dropped: the tally stays still");
    }

    @Test
    void aStampTheWatchCannotGiveStillReadsTheSentinelAndTalliesTheDrop() {
        PrtsChunkDemand.install(new ThrowingTap());
        long before = PrtsChunkDemand.droppedObservations();

        assertEquals(-1L, PrtsChunkDemand.futureOpenedAt(),
            "the sentinel the last revision gave the stamp entry is what the caller still reads");
        assertEquals(1L, PrtsChunkDemand.droppedObservations() - before,
            "and the query the watch could not answer is a drop like any other");
    }

    @Test
    void dropsFromManyThreadsAllReachTheOneTally() throws Exception {
        ThrowingTap tap = new ThrowingTap();
        PrtsChunkDemand.install(tap);
        long before = PrtsChunkDemand.droppedObservations();
        int droppers = 4;
        int perDropper = 250;
        CyclicBarrier barrier = new CyclicBarrier(droppers + 1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < droppers; index++) {
            Thread thread = new Thread(() -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    for (int round = 0; round < perDropper; round++) {
                        PrtsChunkDemand.futureOpened("minecraft:overworld", "full");
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
            threads.add(thread);
            thread.start();
        }
        barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertTrue(!thread.isAlive(), "every dropper returns within its timeout");
        }
        assertTrue(failures.isEmpty(), "no dropper failed: " + failures);

        assertEquals(droppers * perDropper, PrtsChunkDemand.droppedObservations() - before,
            "no drop is lost and none is tallied twice");
        assertEquals((long) droppers * perDropper, tap.opened.sum(),
            "and every dropped call reached the watch exactly once");
    }
}
