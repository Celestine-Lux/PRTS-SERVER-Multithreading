/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A watch that is full drops the diagnostic and leaves the event alone: the per-thread pending
 * bound and the long-call ring are declared values, and neither of them fails an ask or a future. */
class ChunkDemandBoundedExitTest {

    /** The per-thread bound the demand face declares for the stamps of the futures it opened. */
    private static final int PENDING_CAP = 32;

    private ChunkDemandObserver observer;

    @AfterEach
    void detach() {
        if (observer != null) {
            observer.detach();
        }
        PrtsChunkDemand.install(null);
    }

    @Test
    void aFutureStackThatIsFullDropsTheStampAndLeavesTheOpenAlone() {
        observer = new ChunkDemandObserver();
        observer.attach();
        int opens = PENDING_CAP + 8;
        for (int index = 0; index < opens; index++) {
            assertDoesNotThrow(() -> PrtsChunkDemand.futureOpened("minecraft:overworld", "full"),
                "an open past the bound is still an open");
        }

        int kept = 0;
        for (int index = 0; index < opens; index++) {
            if (PrtsChunkDemand.futureOpenedAt() >= 0L) {
                kept++;
            }
        }
        assertEquals(PENDING_CAP, kept,
            "the bound keeps the stamps that fit and drops the rest without failing the caller");
    }

    @Test
    void aBlockingStackThatIsFullDropsTheEntryAndLeavesTheAskAlone() {
        observer = new ChunkDemandObserver();
        observer.attach();
        int asks = PENDING_CAP + 8;
        for (int index = 0; index < asks; index++) {
            assertDoesNotThrow(() -> PrtsChunkDemand.demandStarted("minecraft:overworld", "full", true),
                "a blocking ask past the bound is still a blocking ask");
        }
        for (int index = 0; index < asks; index++) {
            assertDoesNotThrow(() -> PrtsChunkDemand.demandFinished("minecraft:overworld", "full", true, true),
                "the answer of a blocking ask past the bound is still an answer");
        }

        assertEquals(asks, observer.blockingRequests(),
            "every ask is counted once even when its stack entry was dropped");
        assertEquals(asks, observer.satisfied(), "every answer is counted once");
        assertEquals(0L, observer.missed(), "no answer was turned into a miss by a full stack");
    }

    @Test
    void aLongCallRingThatIsFullDropsTheOldestAndLeavesTheCountAlone() {
        observer = new ChunkDemandObserver();
        observer.attach();
        long started = System.nanoTime() - ChunkDemandObserver.LONG_DEMAND_NANOS - 1_000_000L;
        int futures = ChunkDemandObserver.LONG_RING + 4;
        for (int index = 0; index < futures; index++) {
            assertDoesNotThrow(() -> PrtsChunkDemand.futureTaken("minecraft:overworld", "full"),
                "a handover past the bound is still a handover");
            assertDoesNotThrow(() -> PrtsChunkDemand.futureCompleted("minecraft:overworld", "full", started, true),
                "a completion past the bound is still a completion");
        }

        assertEquals(ChunkDemandObserver.LONG_RING, observer.longCalls().size(),
            "the ring keeps its declared length and drops the oldest entry");
        assertEquals(futures, observer.futuresTaken(), "every handover is counted once");
        assertEquals(futures, observer.futuresCompleted(), "every completion is counted once, kept in the ring or not");
        assertEquals(0L, observer.futuresInFlight(), "the completions balance the handovers");
    }
}
