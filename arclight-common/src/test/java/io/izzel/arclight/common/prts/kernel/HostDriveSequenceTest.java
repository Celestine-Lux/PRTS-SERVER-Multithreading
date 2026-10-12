/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.config.ExecutableProductFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The host sequence as a controlled drive: the tick is opened with the thread the host names, the
 * whole tick runs, and the safe point after it answers what other threads queued. A request from
 * another thread is not a completed hand-off, a queue that is full refuses where it was asked, and a
 * chain that has been closed starts no generation after it. */
class HostDriveSequenceTest {

    private static final List<String> WORLDS = List.of(HostDriveFixture.WORLD, HostDriveFixture.SECOND_WORLD);

    private final HostDriveFixture fixture = new HostDriveFixture();
    private final KernelModule module = KernelModule.instance();

    @BeforeEach
    void switchTheKernelOn() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        ExecutableProductFace.declare();
    }

    @AfterEach
    void leaveTheModuleAsItWas() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.markSafePoint();
        // An item whose line was not written keeps the chain it waits on: it is disposed of here, or
        // the case after this one would meet a chain this one left behind and could not step off.
        disposeWaitingItems();
        assertFalse(module.commitResetPending(), "no request outlives the case that made it");
        assertTrue(module.resetQueueSources().isEmpty(), "and the queue is left empty");
        module.rollbackCommitChain();
        ExecutableProductFace.restore();
    }

    /** One tick of the host sequence, and then the chain the cases ask the module to adopt. */
    private HostDriveFixture.Chain install() {
        fixture.tick(WORLDS);
        return new HostDriveFixture.Chain(module, ticket -> new CommitTicket.Outcome(
            CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1", "b=2"), 2, ""));
    }

    /** Hands every item that waits for a disposition its line, so the chain it holds can be stepped
     * off at the end of a case. */
    private void disposeWaitingItems() {
        for (KernelModule.Disposition item : module.commitChainDispositions()) {
            module.disposeCommitDisposition(item.ticketId(), new CommitTicket.Outcome(
                CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1", "b=2"), 2, ""));
        }
    }

    @Test
    void theTickSequenceNamesTheOwnerAndAnswersWhatAnotherThreadQueued() {
        HostDriveFixture.Chain chain = install();
        assertEquals("server.getRunningThread",
            HostDriveFixture.reading("product.commit.owner_thread_source"));
        assertEquals("1", HostDriveFixture.reading("product.commit.owner_thread_bound"));
        assertEquals("0", HostDriveFixture.reading("product.commit.owner_thread_mismatches"),
            "the tick arrived on the thread the host named");
        assertEquals(Thread.currentThread().threadId(),
            HostDriveFixture.counter("product.commit.owner_thread_id"));
        long calls = HostDriveFixture.counter("product.commit.safe_point_calls");

        HostDriveFixture.Asked asked =
            HostDriveFixture.requestFromAnotherThread(module, HostDriveFixture.FIXTURE_SOURCE);

        assertFalse(asked.request().carriedOut(), "another thread does not carry a reset out");
        assertTrue(asked.request().handedOff(), "it is handed to the owner safe point");
        assertEquals("queued-for-owner-safe-point", asked.request().reason());
        assertEquals(0L, chain.controller.resets(), "nothing was carried out where it arrived");
        assertEquals(0L, chain.epochs.current());
        assertEquals("1", HostDriveFixture.reading("product.commit.reset_queued"));
        assertEquals(HostDriveFixture.FIXTURE_SOURCE,
            HostDriveFixture.reading("product.commit.reset_queue_sources"));
        assertEquals("0", HostDriveFixture.reading("product.commit.reset_trace_cross_thread"),
            "a request in the queue has been answered by no thread yet");

        fixture.postTick(WORLDS);

        assertEquals("0", HostDriveFixture.reading("product.commit.reset_queued"),
            "the safe point of the tick after it took it");
        assertEquals(1L, chain.controller.resets(), "one request is one generation");
        assertEquals(1L, chain.epochs.current());
        assertEquals("1", HostDriveFixture.reading("product.commit.reset_trace_cross_thread"),
            "the request was made on one thread and answered on another");
        assertEquals(calls + 1L, HostDriveFixture.counter("product.commit.safe_point_calls"));
        String wiring = HostDriveFixture.reading("product.commit.safe_point_wiring");
        assertNotEquals("NOT_WIRED", wiring,
            "the column reports what arrived instead of the constant it used to be");
        assertTrue(wiring.contains("HOST_SAFE_POINT"), wiring);
        assertEquals(HostDriveFixture.FIXTURE_SOURCE + "|" + asked.threadId() + "|"
            + Thread.currentThread().threadId() + "|1|reset-at-safe-point",
            HostDriveFixture.reading("product.commit.reset_trace"),
            "source, the thread that asked, the thread that answered, the generation, the result");
    }

    @Test
    void aRequestQueuedBetweenTwoTicksWaitsForTheNextSafePoint() {
        HostDriveFixture.Chain chain = install();

        HostDriveFixture.Asked asked =
            HostDriveFixture.requestFromAnotherThread(module, HostDriveFixture.FIXTURE_SOURCE);

        assertEquals("1", HostDriveFixture.reading("product.commit.reset_queued"),
            "the queue is state of the module and survives the boundary between two ticks");
        assertEquals(0L, chain.controller.resets(), "and nothing is carried out in the gap");

        fixture.tick(WORLDS);

        assertEquals("0", HostDriveFixture.reading("product.commit.reset_queued"));
        assertEquals(1L, chain.controller.resets());
        assertEquals(1L, chain.epochs.current());
        assertEquals(asked.threadId() + "|" + Thread.currentThread().threadId() + "|1|reset-at-safe-point",
            HostDriveFixture.reading("product.commit.reset_trace")
                .substring(HostDriveFixture.reading("product.commit.reset_trace").indexOf('|') + 1));
    }

    @Test
    void aFullQueueRefusesWhereItWasAskedAndKeepsEveryEntryThatWaits() {
        fixture.tick(WORLDS);
        int capacity = KernelModule.resetQueueCapacity();
        for (int index = 0; index < capacity; index++) {
            assertTrue(HostDriveFixture.requestFromAnotherThread(module, "fixture-" + index)
                .request().handedOff(), "the queue takes one entry up to its bound");
        }
        long refusals = HostDriveFixture.counter("product.commit.reset_queue_refusals");

        HostDriveFixture.Asked refused =
            HostDriveFixture.requestFromAnotherThread(module, "fixture-over-the-bound");

        assertFalse(refused.request().handedOff(), "a full queue refuses instead of growing");
        assertFalse(refused.request().carriedOut());
        assertEquals("owner-thread-queue-full", refused.request().reason());
        assertEquals(refusals + 1L, HostDriveFixture.counter("product.commit.reset_queue_refusals"));
        assertEquals(capacity, module.resetQueueDepth(), "not one entry that waited was dropped");
        assertEquals(capacity, Integer.parseInt(HostDriveFixture.reading("product.commit.reset_queued")));
        assertEquals("fixture-0,fixture-1", HostDriveFixture.reading("product.commit.reset_queue_sources")
            .substring(0, "fixture-0,fixture-1".length()), "the oldest entry is still first");
    }

    @Test
    void theSameSourceTwiceIsOneRequestAndTwoSourcesAreTwoGenerations() {
        HostDriveFixture.Chain chain = install();
        HostDriveFixture.requestFromAnotherThread(module, "fixture-a");
        HostDriveFixture.requestFromAnotherThread(module, "fixture-a");

        assertEquals(1, module.resetQueueDepth(), "a source already waiting is the same request twice");
        assertEquals("1", HostDriveFixture.reading("product.commit.reset_queued"));

        fixture.postTick(WORLDS);

        assertEquals(1L, chain.controller.resets());
        assertEquals(1L, chain.epochs.current());

        fixture.tick(WORLDS);
        HostDriveFixture.requestFromAnotherThread(module, "fixture-b");
        HostDriveFixture.requestFromAnotherThread(module, "fixture-c");

        assertEquals(2, module.resetQueueDepth(), "two sources are two requests");

        fixture.postTick(WORLDS);

        assertEquals(3L, chain.controller.resets(), "every entry of the queue is carried out once");
        assertEquals(3L, chain.epochs.current(), "and advances one generation");
    }

    @Test
    void aRequestAfterTheChainClosedIsRefusedAndLeavesNothingOwed() {
        HostDriveFixture.Chain chain = install();
        fixture.stop(WORLDS);
        long resets = chain.controller.resets();

        KernelModule.ResetRequest here = module.requestReadingsReset(HostDriveFixture.FIXTURE_SOURCE);

        assertFalse(here.carriedOut(), "a closed chain starts no generation");
        assertEquals("lifecycle-closed", here.reason());
        assertEquals(resets, chain.controller.resets());
        assertEquals("0", HostDriveFixture.reading("product.commit.controller_reset_pending"),
            "and it leaves no debt that could never be answered");

        HostDriveFixture.Asked after =
            HostDriveFixture.requestFromAnotherThread(module, "fixture-after-the-close");

        assertFalse(after.request().handedOff(),
            "the close is what the mailbox decides an arriving request against, so it is refused there");
        assertEquals("lifecycle-closed", after.request().reason());
        assertEquals("0", HostDriveFixture.reading("product.commit.reset_queued"),
            "and the queue is not a place a request can wait for a consumer that is gone");

        fixture.postTick(WORLDS);

        assertEquals("0", HostDriveFixture.reading("product.commit.reset_queued"),
            "the safe point of the tick after it finds nothing to refuse and nothing to keep owed");
        assertEquals("0", HostDriveFixture.reading("product.commit.controller_reset_pending"));
        assertEquals(resets, chain.controller.resets());
        assertTrue(chain.controller.resetClosedRefusals() > 0L, "and the refusal is counted");
        assertEquals("lifecycle-closed",
            HostDriveFixture.reading("product.commit.reset_trace")
                .substring(HostDriveFixture.reading("product.commit.reset_trace").lastIndexOf('|') + 1));
    }

    @Test
    void aRequestAgainstALineInFlightStaysOwedAtTheSafePoint() {
        HostDriveFixture.Chain chain = install();
        CommitTicket ticket = chain.reservations.reserve(chain.request(21L)).ticket();
        assertTrue(chain.reservations.beginPublish(ticket), "a line is in flight");

        HostDriveFixture.requestFromAnotherThread(module, HostDriveFixture.FIXTURE_SOURCE);
        fixture.postTick(WORLDS);

        assertEquals(0L, chain.controller.resets(), "the window is not closed over a line in flight");
        assertEquals(0L, chain.epochs.current());
        assertEquals("1", HostDriveFixture.reading("product.commit.controller_reset_pending"),
            "the module owes the whole reset and says so");
        assertTrue(module.commitResetPending());
        assertEquals(1, chain.reservations.publishing(), "and the line keeps its room");

        assertTrue(chain.reservations.finalize(ticket, new CommitTicket.Outcome(
            CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1", "b=2"), 2, "")).finalized());

        fixture.postTick(WORLDS);

        assertEquals(1L, chain.controller.resets(), "and then the request it owes is carried out");
        assertEquals(1L, chain.epochs.current());
        assertFalse(module.commitResetPending());
    }
}
