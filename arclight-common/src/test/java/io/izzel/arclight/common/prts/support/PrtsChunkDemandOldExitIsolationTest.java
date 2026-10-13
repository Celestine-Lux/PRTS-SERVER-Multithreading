/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The four observation entries the seam had before it named the chunk: what the host path sees
 * when the watch on them throws, what it still sees when the host path itself fails, and the one
 * count per event that the guard must not change. The entry that answers with a stamp is read here
 * as it now behaves: a watch it cannot question is answered with -1 rather than carried out. */
class PrtsChunkDemandOldExitIsolationTest {

    @AfterEach
    void detach() {
        PrtsChunkDemand.install(null);
    }

    /** A tap that throws on every entry and counts how often it was reached before it did: that
     * count is how a dropped observation is told apart from an event that never arrived. */
    private static final class ThrowingTap implements PrtsChunkDemand.DemandTap {

        final LongAdder finished = new LongAdder();
        final LongAdder opened = new LongAdder();
        final LongAdder taken = new LongAdder();
        final LongAdder completed = new LongAdder();
        final LongAdder stamps = new LongAdder();

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
        }

        @Override
        public void demandFinished(String worldId, String status, boolean blocking, boolean satisfied) {
            finished.increment();
            throw new IllegalStateException("no room for this answer");
        }

        @Override
        public void futureOpened(String worldId, String status) {
            opened.increment();
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

    /** A tap that keeps every event it is told, per entry, so a count that moved twice shows. */
    private static final class CountingTap implements PrtsChunkDemand.DemandTap {

        final List<String> events = new ArrayList<>();
        final Map<String, Integer> counts = new LinkedHashMap<>();

        private void told(String entry, String worldId, String status) {
            events.add(entry + " " + worldId + " " + status);
            counts.merge(entry, 1, Integer::sum);
        }

        int count(String entry) {
            return counts.getOrDefault(entry, 0);
        }

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
            told("started", worldId, status);
        }

        @Override
        public void demandFinished(String worldId, String status, boolean blocking, boolean satisfied) {
            told("finished", worldId, status);
        }

        @Override
        public void futureOpened(String worldId, String status) {
            told("opened", worldId, status);
        }

        @Override
        public long futureOpenedAt() {
            return -1L;
        }

        @Override
        public void futureTaken(String worldId, String status) {
            told("taken", worldId, status);
        }

        @Override
        public void futureCompleted(String worldId, String status, long startedNanos,
                                    boolean satisfied) {
            told("completed", worldId, status);
        }
    }

    /** The shape of a return-side watch as the host path runs it: the watch is told, then the host
     * answer is produced. The answer and the host's own failure are what the case reads. */
    private static String hostReturn(boolean hostFails) {
        PrtsChunkDemand.demandFinished("minecraft:overworld", "full", false, true);
        if (hostFails) {
            throw new IllegalStateException("the ask itself failed");
        }
        return "the chunk the ask named";
    }

    /** A value the host computes for the watch and cannot produce. */
    private static String worldIdTheHostCannotName() {
        throw new IllegalStateException("the world is not there");
    }

    @Test
    void anAnsweredAskIsNotFailedByAThrowingTap() {
        ThrowingTap tap = new ThrowingTap();
        PrtsChunkDemand.install(tap);

        assertDoesNotThrow(() -> PrtsChunkDemand.demandFinished("minecraft:overworld", "full", false, true),
            "the answer the ask produced is not failed by the watch that saw it");
        assertEquals(1L, tap.finished.sum(), "the watch was reached once: the guard drops the failure, not the event");

        CountingTap next = new CountingTap();
        PrtsChunkDemand.install(next);
        PrtsChunkDemand.demandFinished("minecraft:overworld", "full", false, true);
        assertEquals(List.of("finished minecraft:overworld full"), next.events,
            "the entry still reports the event that follows a dropped one");
    }

    @Test
    void aFutureOpenedByAThrowingTapStillOpens() {
        ThrowingTap tap = new ThrowingTap();
        PrtsChunkDemand.install(tap);

        assertDoesNotThrow(() -> PrtsChunkDemand.futureOpened("minecraft:overworld", "full"),
            "a future is not failed by the watch that saw it opened");
        assertEquals(1L, tap.opened.sum(), "the watch was reached once: the guard drops the failure, not the event");

        CountingTap next = new CountingTap();
        PrtsChunkDemand.install(next);
        PrtsChunkDemand.futureOpened("minecraft:overworld", "full");
        assertEquals(List.of("opened minecraft:overworld full"), next.events,
            "the entry still reports the event that follows a dropped one");
    }

    @Test
    void aFutureTakenByAThrowingTapIsStillHandedOut() {
        ThrowingTap tap = new ThrowingTap();
        PrtsChunkDemand.install(tap);

        assertDoesNotThrow(() -> PrtsChunkDemand.futureTaken("minecraft:overworld", "full"),
            "a handover is not failed by the watch that saw it");
        assertEquals(1L, tap.taken.sum(), "the watch was reached once: the guard drops the failure, not the event");

        CountingTap next = new CountingTap();
        PrtsChunkDemand.install(next);
        PrtsChunkDemand.futureTaken("minecraft:overworld", "full");
        assertEquals(List.of("taken minecraft:overworld full"), next.events,
            "the entry still reports the event that follows a dropped one");
    }

    @Test
    void aFutureCompletedUnderAThrowingTapStillCompletes() {
        ThrowingTap tap = new ThrowingTap();
        PrtsChunkDemand.install(tap);

        assertDoesNotThrow(() -> PrtsChunkDemand.futureCompleted("minecraft:overworld", "full", 7L, true),
            "a completion is not failed by the watch that saw it");
        assertEquals(1L, tap.completed.sum(), "the watch was reached once: the guard drops the failure, not the event");

        CountingTap next = new CountingTap();
        PrtsChunkDemand.install(next);
        PrtsChunkDemand.futureCompleted("minecraft:overworld", "full", 7L, true);
        assertEquals(List.of("completed minecraft:overworld full"), next.events,
            "the entry still reports the event that follows a dropped one");
    }

    @Test
    void eachOldEntryIsToldOnceWhileTheWatchAnswers() {
        CountingTap tap = new CountingTap();
        PrtsChunkDemand.install(tap);
        PrtsChunkDemand.demandStarted("minecraft:overworld", 3, -7, "full", false);
        PrtsChunkDemand.demandDescriptor("minecraft:overworld", 3, -7, "full", true);
        PrtsChunkDemand.demandFinished("minecraft:overworld", "full", false, true);
        PrtsChunkDemand.futureOpened("minecraft:overworld", "full");
        PrtsChunkDemand.futureTaken("minecraft:overworld", "full");
        PrtsChunkDemand.futureCompleted("minecraft:overworld", "full", 7L, true);

        assertEquals(1, tap.count("started"), "one ask, one count through the default that dropped the chunk");
        assertEquals(1, tap.count("finished"), "one answer, one count: the guard adds none and drops none");
        assertEquals(2, tap.count("opened"),
            "one open from the coordinate entry's default, one from the old entry itself");
        assertEquals(1, tap.count("taken"), "one handover, one count: the guard adds none and drops none");
        assertEquals(1, tap.count("completed"), "one completion, one count: the guard adds none and drops none");
    }

    @Test
    void theHostAnswerAndItsFailureAreNotTouchedByTheWatch() {
        PrtsChunkDemand.install(new ThrowingTap());

        assertEquals("the chunk the ask named", hostReturn(false),
            "the answer the host path produced comes back unchanged while the watch throws");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> hostReturn(true),
            "a failure of the host path itself is not the watch failing, and is not swallowed");
        assertEquals("the ask itself failed", failure.getMessage(), "the host failure arrives as it was raised");
    }

    @Test
    void aHostValueComputedForTheWatchStillFailsOutsideIt() {
        PrtsChunkDemand.install(new ThrowingTap());

        assertThrows(IllegalStateException.class,
            () -> PrtsChunkDemand.demandFinished(worldIdTheHostCannotName(), "full", false, true),
            "the argument is read in the host frame before the entry: the guard covers the watch only");
    }

    @Test
    void aTapThatThrowsOnTheEntryTheDefaultForwardsToIsAlreadyContained() {
        ThrowingTap tap = new ThrowingTap();
        PrtsChunkDemand.install(tap);

        assertDoesNotThrow(() -> PrtsChunkDemand.demandDescriptor("minecraft:overworld", 1, 2, "full", true),
            "the coordinate entry already contained the failure of the old entry it forwards to");
        assertEquals(1L, tap.opened.sum(), "the old entry was reached exactly once through the default");
    }

    @Test
    void theStampEntryAnswersTheSentinelInsteadOfTheFailure() {
        ThrowingTap tap = new ThrowingTap();
        PrtsChunkDemand.install(tap);

        long stamp = assertDoesNotThrow(PrtsChunkDemand::futureOpenedAt,
            "a stamp the watch cannot give is answered for, not carried out to the caller");
        assertEquals(-1L, stamp, "the sentinel is no observation time, and it is what the caller reads");
        assertEquals(1L, tap.stamps.sum(), "the query was reached once");
    }
}
