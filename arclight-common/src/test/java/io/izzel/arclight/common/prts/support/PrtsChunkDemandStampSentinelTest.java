/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stamp entry answers with a value, so a watch that cannot answer is not the same event as a
 * watch that cannot be counted: the entry answers -1, the future the host produced is still handed
 * back and still completes, and a chain that never got a stamp records no completion at all. */
class PrtsChunkDemandStampSentinelTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String STATUS = "full";
    private static final String CHUNK = "the chunk the ask named";
    private static final long STAMP = 7L;

    @AfterEach
    void detach() {
        PrtsChunkDemand.install(null);
    }

    /** A watch whose stamp entry throws and whose later entries only record that they were reached:
     * anything recorded here is a record the observation chain invented for itself. */
    private static final class ThrowingStampTap implements PrtsChunkDemand.DemandTap {

        final List<String> takings = new ArrayList<>();
        final List<String> completions = new ArrayList<>();
        long stamps;

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
        }

        @Override
        public void demandFinished(String worldId, String status, boolean blocking, boolean satisfied) {
        }

        @Override
        public void futureOpened(String worldId, String status) {
        }

        @Override
        public long futureOpenedAt() {
            stamps++;
            throw new IllegalStateException("no stamp was kept");
        }

        @Override
        public void futureTaken(String worldId, String status) {
            takings.add(status);
        }

        @Override
        public void futureCompleted(String worldId, String status, long startedNanos, boolean satisfied) {
            completions.add(status + " " + startedNanos + " " + satisfied);
        }
    }

    /** A watch that keeps a stamp and records the hand-over and the completion it is told about. */
    private static final class KeepingStampTap implements PrtsChunkDemand.DemandTap {

        final List<String> takings = new ArrayList<>();
        final List<String> completions = new ArrayList<>();

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
        }

        @Override
        public void demandFinished(String worldId, String status, boolean blocking, boolean satisfied) {
        }

        @Override
        public void futureOpened(String worldId, String status) {
        }

        @Override
        public long futureOpenedAt() {
            return STAMP;
        }

        @Override
        public void futureTaken(String worldId, String status) {
            takings.add(status);
        }

        @Override
        public void futureCompleted(String worldId, String status, long startedNanos, boolean satisfied) {
            completions.add(status + " " + startedNanos + " " + satisfied);
        }
    }

    /** The return side of the future-returning ask as the host path runs it: the host frame has
     * already produced the future it is about to return, the watch is asked for the stamp at that
     * return, and the chain the stamp starts stops where there is no stamp to start it. */
    private static CompletableFuture<String> hostReturn(CompletableFuture<String> future) {
        if (!PrtsChunkDemand.installed()) {
            return future;
        }
        long startedAt = PrtsChunkDemand.futureOpenedAt();
        if (startedAt < 0L) {
            return future;
        }
        PrtsChunkDemand.futureTaken(WORLD, STATUS);
        future.whenComplete((result, failure) -> PrtsChunkDemand.futureCompleted(WORLD, STATUS,
            startedAt, failure == null && result != null));
        return future;
    }

    /** The entry answers -1 for a watch that throws, and the failure of the watch does not reach
     * the caller that asked for the stamp. */
    @Test
    void aStampTheWatchCannotAnswerReadsAsNoObservationTime() {
        ThrowingStampTap tap = new ThrowingStampTap();
        PrtsChunkDemand.install(tap);

        long stamp = assertDoesNotThrow(PrtsChunkDemand::futureOpenedAt,
            "the watch's failure is answered for, it is not carried out to the caller");
        assertEquals(-1L, stamp, "a stamp that cannot be read is the sentinel: no observation time");
        assertEquals(1L, tap.stamps, "the query was reached once: the guard drops the failure, not the call");
    }

    /** The future the host frame produced is the one the caller gets back, and the watch cannot
     * keep it from completing. */
    @Test
    void theFutureTheHostProducedIsStillHandedBackAndStillCompletes() {
        ThrowingStampTap tap = new ThrowingStampTap();
        PrtsChunkDemand.install(tap);
        CompletableFuture<String> produced = new CompletableFuture<>();

        CompletableFuture<String> returned = assertDoesNotThrow(() -> hostReturn(produced),
            "the host path still answers with its future while the watch throws");
        assertSame(produced, returned, "the future the host frame produced is the one that comes back");
        assertFalse(returned.isDone(), "it comes back outstanding, as the host made it");
        assertTrue(returned.complete(CHUNK), "the host's own future still completes");
        assertEquals(CHUNK, assertDoesNotThrow(returned::join), "the value it completed with arrives");
    }

    /** A chain that never got a stamp is not carried on: nothing is recorded for a hand-over or a
     * completion that was never observed. */
    @Test
    void aChainWithoutAStampRecordsNoCompletion() {
        ThrowingStampTap tap = new ThrowingStampTap();
        PrtsChunkDemand.install(tap);
        CompletableFuture<String> produced = new CompletableFuture<>();

        CompletableFuture<String> returned = hostReturn(produced);
        returned.complete(CHUNK);
        assertEquals(List.of(), tap.takings, "the chain stops before the hand-over it cannot stamp");
        assertEquals(List.of(), tap.completions, "no completion is recorded for a chain that never opened");
    }

    /** The control: while the watch answers, the same walk reads the stamp it kept and records
     * exactly the one hand-over and the one completion. */
    @Test
    void aStampThatIsKeptIsAnsweredAndCarriesTheChainOn() {
        KeepingStampTap tap = new KeepingStampTap();
        PrtsChunkDemand.install(tap);
        CompletableFuture<String> produced = new CompletableFuture<>();

        assertEquals(STAMP, PrtsChunkDemand.futureOpenedAt(),
            "a watch that answers keeps answering with its own stamp, unchanged");
        CompletableFuture<String> returned = hostReturn(produced);
        assertSame(produced, returned, "the observed walk hands back the same future");
        assertEquals(List.of(STATUS), tap.takings, "an observed chain is handed over once");
        assertTrue(returned.complete(CHUNK), "the observed walk still completes the same future");
        assertEquals(List.of(STATUS + " " + STAMP + " true"), tap.completions,
            "an observed chain records one completion, with the stamp the watch kept");
    }
}
