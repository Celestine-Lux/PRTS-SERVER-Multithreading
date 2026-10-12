/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.config.ExecutableProductFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The protocol of the reset mailbox read back one value at a time: the depth, the sources waiting
 * and whether a reset is owed come out of one read so they cannot disagree, every call on the queue
 * path ends in exactly one of the four outcomes, and a request the owner refused once is not admitted
 * a second time when it comes back. Every count is read as the step the case moved it, because the
 * counters of the module outlive a clear. */
class KernelResetMailboxProtocolTest {

    private static final List<String> WORLDS = List.of(HostDriveFixture.WORLD, HostDriveFixture.SECOND_WORLD);
    private static final long TIMEOUT_SECONDS = 30L;

    private final HostDriveFixture fixture = new HostDriveFixture();
    private final KernelModule module = KernelModule.instance();
    private HostDriveFixture.Chain chain;
    private CommitTicket inFlight;

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
        try {
            module.markSafePoint();
            if (inFlight != null) {
                chain.reservations.finalize(inFlight, published());
                inFlight = null;
            }
            for (KernelModule.Disposition item : module.commitChainDispositions()) {
                module.disposeCommitDisposition(item.ticketId(), published());
            }
        } finally {
            module.rollbackCommitChain();
            ExecutableProductFace.restore();
        }
        assertFalse(module.commitResetPending(), "no request outlives the case that made it");
        assertTrue(module.resetQueueSources().isEmpty(), "and the queue is left empty");
    }

    private static CommitTicket.Outcome published() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
            List.of("a=1", "b=2"), 2, "");
    }

    private HostDriveFixture.Chain install() {
        fixture.tick(WORLDS);
        chain = new HostDriveFixture.Chain(module, ticket -> published());
        return chain;
    }

    private CommitTicket beginLine(long instance) {
        CommitTicket ticket = chain.reservations.reserve(chain.request(instance)).ticket();
        assertTrue(chain.reservations.beginPublish(ticket), "a line is in flight");
        inFlight = ticket;
        return ticket;
    }

    private void releaseTheLine() {
        if (inFlight != null) {
            chain.reservations.finalize(inFlight, published());
            inFlight = null;
        }
    }

    private static KernelModule.ResetRequest onAnotherThread(KernelModule module, String source)
        throws InterruptedException {
        AtomicReference<KernelModule.ResetRequest> answer = new AtomicReference<>();
        Thread thread = new Thread(() -> answer.set(module.requestReadingsReset(source)));
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(thread.isAlive(), "a request from another thread returns within its timeout");
        return answer.get();
    }

    /** Fills the queue from one thread per source, released together, so the case sees the admission
     * path on its own with no owner draining in the middle of it. */
    private void fill(int attempts, KernelModule.ResetRequest[] answers) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(attempts + 1);
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
            int slot = index;
            Thread thread = new Thread(() -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    answers[slot] = module.requestReadingsReset("host:console-" + slot);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            threads.add(thread);
            thread.start();
        }
        barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(thread.isAlive(), "every producer returns within its timeout");
        }
    }

    @Test
    void oneReadOfTheQueueExitsNeverTears() throws Exception {
        install();
        AtomicBoolean stop = new AtomicBoolean();
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            int slot = index;
            Thread thread = new Thread(() -> {
                for (int round = 0; round < 3000 && !stop.get(); round++) {
                    module.requestReadingsReset("host:console-" + slot + "-" + round);
                }
            });
            threads.add(thread);
            thread.start();
        }
        List<String> torn = new ArrayList<>();
        for (int round = 0; round < 3000; round++) {
            KernelModule.Mailbox read = module.mailbox();
            if (read.depth() != read.sources().size()) {
                torn.add("depth=" + read.depth() + " sources=" + read.sources().size());
                break;
            }
            if (read.depth() + read.inProgress() > KernelModule.resetQueueCapacity()) {
                torn.add("depth=" + read.depth() + " inProgress=" + read.inProgress());
                break;
            }
            if (!read.pending() && (read.depth() > 0 || read.inProgress() > 0)) {
                torn.add("pending=false depth=" + read.depth() + " inProgress=" + read.inProgress());
                break;
            }
            module.markSafePoint();
        }
        stop.set(true);
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(thread.isAlive(), "every producer returns within its timeout");
        }

        assertTrue(torn.isEmpty(), "the exits come out of one read and agree: " + torn);
        for (int round = 0; round < 64 && module.commitResetPending(); round++) {
            module.markSafePoint();
        }
        assertEquals(0, module.mailbox().depth());
        assertFalse(module.commitResetPending());
    }

    @Test
    void everyCallOnTheQueuePathEndsInExactlyOneOutcome() throws Exception {
        install();
        int attempts = KernelModule.resetQueueCapacity() + 4;
        KernelModule.ResetRequest[] answers = new KernelModule.ResetRequest[attempts];
        fill(attempts, answers);
        // Four calls join a source already waiting: the same request twice leaves the queue as it was.
        for (int index = 0; index < 4; index++) {
            onAnotherThread(module, "host:console-" + index);
        }
        assertEquals(KernelModule.resetQueueCapacity(), module.mailbox().depth());

        KernelModule.Mailbox before = module.mailbox();
        fixture.stop(WORLDS);
        onAnotherThread(module, "host:console-after-close");
        onAnotherThread(module, "host:console-after-close-again");

        KernelModule.Mailbox after = module.mailbox();
        long calls = after.calls() - before.calls();
        long handOffs = after.handOffs() - before.handOffs();

        assertEquals(handOffs, (after.merged() - before.merged()) + (after.admitted() - before.admitted())
            + (after.refusedFull() - before.refusedFull()) + (after.refusedClosed() - before.refusedClosed()),
            "a call that took the queue path ended in exactly one outcome, and none of them is uncounted");
        assertEquals(2L, calls, "the two calls of this step are counted once each");
        assertEquals(2L, handOffs, "and both took the path of a thread the chain does not own");
        assertEquals(0L, after.admitted() - before.admitted(),
            "a call that arrives after the close mints no item");
        assertEquals(0L, after.merged() - before.merged());
        assertEquals(0L, after.refusedFull() - before.refusedFull());
        assertEquals(2L, after.refusedClosed() - before.refusedClosed(),
            "the two calls after the close are refused at the mailbox");
        assertEquals(0, after.depth());
        assertFalse(after.pending());
    }

    @Test
    void everyOutcomeIsCountedOnceOverAFullQueueAndAMergeAndAClose() throws Exception {
        install();
        int attempts = KernelModule.resetQueueCapacity() + 4;
        KernelModule.ResetRequest[] answers = new KernelModule.ResetRequest[attempts];
        KernelModule.Mailbox before = module.mailbox();
        fill(attempts, answers);
        for (int index = 0; index < 3; index++) {
            onAnotherThread(module, "host:console-" + index);
        }
        KernelModule.Mailbox filled = module.mailbox();
        long calls = filled.calls() - before.calls();
        long handOffs = filled.handOffs() - before.handOffs();

        assertEquals((long) attempts + 3L, calls, "every call of the case is one call");
        assertEquals((long) attempts + 3L, handOffs, "and every one of them took the queue path");
        assertEquals(handOffs, (filled.merged() - before.merged()) + (filled.admitted() - before.admitted())
            + (filled.refusedFull() - before.refusedFull()) + (filled.refusedClosed() - before.refusedClosed()),
            "one outcome per call, and the four of them add up to the calls that took this path");
        assertEquals((long) KernelModule.resetQueueCapacity(), filled.admitted() - before.admitted(),
            "one item per distinct source with room");
        assertEquals((long) (attempts - KernelModule.resetQueueCapacity()),
            filled.refusedFull() - before.refusedFull(), "the rest are refused for room");
        assertEquals(3L, filled.merged() - before.merged(), "the repeated sources are merged calls");
        assertEquals(0L, filled.refusedClosed() - before.refusedClosed());
        assertEquals(KernelModule.resetQueueCapacity(), filled.depth());

        long closedBefore = HostDriveFixture.counter("product.commit.reset_closed_refusals");

        fixture.stop(WORLDS);

        KernelModule.Mailbox closed = module.mailbox();
        assertEquals(0, closed.depth(), "what the queue held is taken in one step by the close");
        assertFalse(closed.pending());
        assertEquals(0L, closed.refusedClosed() - before.refusedClosed(),
            "these are answered at the safe point the close makes, not at the mailbox: the controller refuses them");
        assertEquals((long) KernelModule.resetQueueCapacity(),
            HostDriveFixture.counter("product.commit.reset_closed_refusals") - closedBefore,
            "and the readout counts one closed refusal per waiting request");
        assertEquals(0L, chain.controller.resets(), "and none of them starts a generation");
    }

    @Test
    void anItemTheOwnerRefusedOnceIsNotAdmittedASecondTime() throws Exception {
        install();
        try {
            beginLine(51L);
            onAnotherThread(module, "host:console-a");
            onAnotherThread(module, "host:console-b");
            KernelModule.Mailbox before = module.mailbox();

            KernelModule.SafePoint first = module.markSafePoint();
            KernelModule.Mailbox afterFirst = module.mailbox();

            assertEquals(2, first.owed(), "both requests are owed");
            assertEquals(0L, afterFirst.admitted() - before.admitted(),
                "coming back for a refused request is not a second admission");
            assertEquals(2, afterFirst.depth(), "and both are still waiting, in the order they arrived");
        } finally {
            releaseTheLine();
        }

        fixture.postTick(WORLDS);

        KernelModule.Mailbox afterSecond = module.mailbox();

        assertEquals(2L, chain.controller.resets(), "the two items were answered once each");
        assertEquals(0, afterSecond.depth());
        assertFalse(afterSecond.pending());
    }

    @Test
    void thePendingFlagIsTheSumOfTheTwoDebtsItStandsFor() throws Exception {
        install();
        try {
            beginLine(61L);
            module.resetReadings();

            KernelModule.Mailbox deferredOnly = module.mailbox();

            assertEquals(0, deferredOnly.depth(), "an owner-thread request is not an entry in the queue");
            assertEquals(0, deferredOnly.inProgress(), "and it is not a batch the owner took out either");
            assertTrue(deferredOnly.pending(), "it is a debt of its own, and the flag still says one is owed");

            onAnotherThread(module, "host:console");

            KernelModule.Mailbox both = module.mailbox();

            assertEquals(1, both.depth(), "the queue holds the request from the other thread");
            assertTrue(both.pending(), "and both debts are owed at once");
        } finally {
            releaseTheLine();
        }

        fixture.postTick(WORLDS);

        KernelModule.Mailbox answered = module.mailbox();

        assertEquals(0, answered.depth());
        assertFalse(answered.pending(), "once both are answered the flag goes down with them");
        assertEquals(2L, chain.controller.resets(),
            "the deferred reset and the queued request are one generation each");
        assertEquals(2L, chain.epochs.current());
    }

    @Test
    void theCountersNeverGoBackwardsUnderProducersAndTheOwnerAtOnce() throws Exception {
        install();
        int producers = KernelModule.resetQueueCapacity();
        KernelModule.ResetRequest[] answers = new KernelModule.ResetRequest[producers];
        fill(producers, answers);

        KernelModule.Mailbox before = module.mailbox();
        fixture.postTick(WORLDS);
        KernelModule.Mailbox after = module.mailbox();

        assertTrue(after.calls() >= before.calls(), "a count of calls never goes backwards");
        assertTrue(after.handOffs() >= before.handOffs());
        assertTrue(after.admitted() >= before.admitted(), "an admission is a step that is never undone");
        assertTrue(after.merged() >= before.merged());
        assertTrue(after.refusedFull() >= before.refusedFull());
        assertTrue(after.refusedClosed() >= before.refusedClosed());
        assertTrue(after.calls() >= 0L && after.admitted() >= 0L && after.merged() >= 0L);
        assertEquals(0L, after.admitted() - before.admitted(), "the eight items were admitted before the drain");
        assertEquals((long) producers, chain.controller.resets(), "each of them is one generation");
        assertEquals((long) producers, chain.epochs.current());
        assertEquals(0, after.depth());
        assertFalse(after.pending());
    }
}
