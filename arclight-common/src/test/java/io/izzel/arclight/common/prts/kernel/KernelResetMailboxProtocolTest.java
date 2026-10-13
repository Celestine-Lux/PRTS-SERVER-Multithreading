/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.config.ExecutableProductFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        return requestFromThread(module, source, new AtomicReference<>());
    }

    /** One request from its own thread, with the identity of that thread. What the worker threw is kept
     * and asserted instead of leaving the caller with an answer that was never set. */
    private static KernelModule.ResetRequest requestFromThread(KernelModule module, String source,
                                                              AtomicReference<Long> threadId)
        throws InterruptedException {
        List<Throwable> failures = workerFailures();
        AtomicReference<KernelModule.ResetRequest> answer = new AtomicReference<>();
        Thread thread = worker(failures, () -> {
            threadId.set(Thread.currentThread().threadId());
            answer.set(module.requestReadingsReset(source));
        });
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(thread.isAlive(), "a request from another thread returns within its timeout");
        assertNoWorkerFailed(failures);
        return answer.get();
    }

    /** Where the throw of a worker is kept, so a case fails for what a worker hit instead of passing
     * because the throw died with the thread that ran it. */
    private static List<Throwable> workerFailures() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    private static Thread worker(List<Throwable> failures, Runnable body) {
        return new Thread(() -> {
            try {
                body.run();
            } catch (Throwable failure) {
                failures.add(failure);
            }
        });
    }

    private static void assertNoWorkerFailed(List<Throwable> failures) {
        assertTrue(failures.isEmpty(), "no worker of this case failed: " + failures);
    }

    private static String reading(String key) {
        return HostDriveFixture.reading(key);
    }

    private static long counter(String key) {
        return HostDriveFixture.counter(key);
    }

    /** The trace line the readout publishes, split: the source, the thread that asked, the thread that
     * answered, the generation it ran, and the result. */
    private static String[] traceFields() {
        return reading("product.commit.reset_trace").split("\\|", -1);
    }

    private static String traceResult() {
        return traceFields()[4];
    }

    /** Fills the queue from one thread per source, released together, so the case sees the admission
     * path on its own with no owner draining in the middle of it. */
    private void fill(int attempts, KernelModule.ResetRequest[] answers, List<Throwable> failures)
        throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(attempts + 1);
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
            int slot = index;
            Thread thread = worker(failures, () -> {
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
        assertNoWorkerFailed(failures);
    }

    @Test
    void oneReadOfTheQueueExitsNeverTears() throws Exception {
        install();
        AtomicBoolean stop = new AtomicBoolean();
        List<Throwable> failures = workerFailures();
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            int slot = index;
            Thread thread = worker(failures, () -> {
                for (int round = 0; round < 3000 && !stop.get(); round++) {
                    module.requestReadingsReset("host:console-" + slot + "-" + round);
                }
            });
            threads.add(thread);
            thread.start();
        }
        List<String> torn = new ArrayList<>();
        try {
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
        } finally {
            stop.set(true);
            for (Thread thread : threads) {
                thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                assertFalse(thread.isAlive(), "every producer returns within its timeout");
            }
        }
        assertNoWorkerFailed(failures);

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
        fill(attempts, answers, workerFailures());
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
        fill(attempts, answers, workerFailures());
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
        fill(producers, answers, workerFailures());

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

    // ---- the owner request: one item, one line, and the request that merges into it ----------------

    @Test
    void anOwnerRequestRefusedWhileALineIsInFlightIsAnsweredAtTheSafePoint() {
        install();
        long requests = counter("product.commit.reset_requests");
        try {
            beginLine(71L);
            KernelModule.ResetRequest answer = module.requestReadingsReset("host:console-owner");

            assertFalse(answer.carriedOut(), "a line in flight refuses the reset where the owner asked");
            assertEquals("deferred", answer.reason());
            assertFalse(answer.handedOff(), "and the request is not an entry of the queue");
            assertTrue(module.commitResetPending(), "it is one owed reset, held apart from the queue");
            assertEquals(0, module.resetQueueDepth());
            assertEquals(1L, counter("product.commit.reset_requests") - requests,
                "one call through the request entry is counted once");

            String[] owed = traceFields();
            assertEquals("host:console-owner", owed[0], "the line names the request that is owed");
            assertEquals(Long.toString(Thread.currentThread().threadId()), owed[1],
                "made on the thread the host named");
            assertEquals("-", owed[2], "and answered by no thread yet");
            assertEquals("none", owed[3], "with no generation behind it");
            assertEquals("deferred", owed[4]);
        } finally {
            releaseTheLine();
        }

        fixture.postTick(WORLDS);

        String[] answered = traceFields();
        assertEquals("host:console-owner", answered[0],
            "the answer lands in the line of the item that was owed, not in a line of its own");
        assertEquals(Long.toString(Thread.currentThread().threadId()), answered[1],
            "and the request is still read as the one made on this thread");
        assertEquals(Long.toString(Thread.currentThread().threadId()), answered[2],
            "the safe point answered it on the owner thread");
        assertEquals("1", answered[3], "naming the generation that carried it");
        assertEquals("reset-at-safe-point", answered[4]);
        assertEquals(1L, chain.controller.resets(), "one owed reset is one generation");
        assertFalse(module.commitResetPending(), "and nothing is left owed");
    }

    @Test
    void aDeferredOwnerRequestWithNothingBehindItTurnsItsOwnTraceComplete() {
        install();
        long requests = counter("product.commit.reset_requests");
        try {
            beginLine(81L);
            module.requestReadingsReset("host:console-owner");
        } finally {
            releaseTheLine();
        }

        KernelModule.SafePoint point = module.markSafePoint();

        assertEquals(1, point.carried(), "the owed reset is the one thing this safe point carried");
        assertEquals(0, point.queued());
        String[] answered = traceFields();

        assertEquals("reset-at-safe-point", answered[4],
            "the line turns complete without a second request having to be made");
        assertEquals("1", answered[3], "and it names the generation the reset ran");
        assertEquals(1L, chain.epochs.current());
        assertEquals(1L, counter("product.commit.reset_requests") - requests,
            "one call, one item, one line: the request entry counted nothing twice");
        assertEquals(1L, chain.controller.resets());
        assertFalse(module.commitResetPending());
    }

    @Test
    void aRequestThatTakesTheTraceOverDoesNotMixTheTwoItems() throws Exception {
        install();
        try {
            beginLine(91L);
            module.requestReadingsReset("host:console-owner");
            assertTrue(module.commitResetPending());
        } finally {
            releaseTheLine();
        }

        AtomicReference<Long> otherThread = new AtomicReference<>();
        requestFromThread(module, "host:console-other", otherThread);

        String[] other = traceFields();

        assertEquals("host:console-other", other[0], "the newer request owns the line now");
        assertEquals(Long.toString(otherThread.get()), other[1]);

        fixture.postTick(WORLDS);

        String[] after = traceFields();

        assertEquals("host:console-other", after[0],
            "the answer of the owed item is not written into the line of another request");
        assertEquals(Long.toString(otherThread.get()), after[1]);
        assertEquals(Long.toString(Thread.currentThread().threadId()), after[2],
            "the line is answered once, by the thread the safe point ran on");
        assertEquals("reset-at-safe-point", after[4]);
        assertEquals(2L, chain.controller.resets(), "each of the two items is answered once");
        assertEquals(2L, chain.epochs.current());
        assertFalse(module.commitResetPending());
    }

    @Test
    void aSecondOwnerRequestMergesIntoTheOwedResetAndDoesNotReplaceIt() {
        install();
        long requests = counter("product.commit.reset_requests");
        try {
            beginLine(101L);
            module.requestReadingsReset("host:console-first");
            String owed = reading("product.commit.reset_trace");

            KernelModule.ResetRequest second = module.requestReadingsReset("host:console-second");

            assertEquals("deferred", second.reason(), "the second call is the same owed reset");
            assertEquals(owed, reading("product.commit.reset_trace"),
                "so it mints nothing and does not move the line of the item it joined");
            assertEquals(2L, counter("product.commit.reset_requests") - requests,
                "and both calls are counted once each");
        } finally {
            releaseTheLine();
        }

        fixture.postTick(WORLDS);

        String[] answered = traceFields();

        assertEquals("host:console-first", answered[0],
            "the item keeps the source it was minted with: the call that merged does not rename it");
        assertEquals("reset-at-safe-point", answered[4]);
        assertEquals(1L, chain.controller.resets(), "two calls on one owed reset are one generation");
        assertEquals(1L, chain.epochs.current());
        assertFalse(module.commitResetPending());
    }

    // ---- the budget of one safe point, and a reset that throws -------------------------------------

    @Test
    void aDeferredResetAndAFullQueueAreTakenWithinOneBudget() throws Exception {
        install();
        try {
            beginLine(111L);
            module.requestReadingsReset("host:console-owner");
            assertTrue(module.commitResetPending(), "one owner reset is owed");
            int capacity = KernelModule.resetQueueCapacity();
            KernelModule.ResetRequest[] answers = new KernelModule.ResetRequest[capacity];
            fill(capacity, answers, workerFailures());
            assertEquals(capacity, module.resetQueueDepth(),
                "the queue holds its own bound while the owner reset is owed");
        } finally {
            releaseTheLine();
        }

        KernelModule.SafePoint first = module.markSafePoint();
        int capacity = KernelModule.resetQueueCapacity();

        assertEquals(capacity, first.carried(),
            "the owed reset holds one place of the budget, so seven waiters are taken with it");
        assertEquals(1, module.resetQueueDepth(), "the last waiter keeps its place for the next point");

        KernelModule.SafePoint second = module.markSafePoint();

        assertEquals(1, second.carried(), "and the next safe point answers it");
        assertEquals(0, module.resetQueueDepth());
        assertEquals((long) capacity + 1L, chain.controller.resets(),
            "one generation per logical request: the owed reset and the waiters");
        assertEquals((long) capacity + 1L, chain.epochs.current());
        assertFalse(module.commitResetPending());
    }

    @Test
    void aResetThatThrowsOnTheOwedItemIsAnsweredWithItsFaultAndNotTriedAgain() {
        install();
        try {
            beginLine(121L);
            module.requestReadingsReset("host:console-owner");
            assertTrue(module.commitResetPending());
        } finally {
            releaseTheLine();
        }
        TestDomain domain = new TestDomain("fixture-fault").throwingOnCall(1);
        module.installDomain(domain);
        try {
            KernelModule.SafePoint point = module.markSafePoint();

            assertEquals(0, point.carried(), "the reset threw, so nothing was carried out");
            String[] faulted = traceFields();

            assertEquals("host:console-owner", faulted[0]);
            assertEquals(Long.toString(Thread.currentThread().threadId()), faulted[2],
                "the item is answered with the fault it hit, not with a completion");
            assertEquals("1", faulted[3],
                "and the generation it had already entered when it threw is the one it names");
            assertEquals("reset-faulted:RuntimeException", faulted[4]);
            assertEquals(1L, chain.controller.resets(),
                "the chain entered the reset: a throw does not give the generation back");
            assertEquals(0, module.mailbox().inProgress(), "and the batch is not left half open");
            assertTrue(module.commitResetPending(), "the mailbox says it is not settled");

            KernelModule.SafePoint again = module.markSafePoint();

            assertEquals(0, again.carried(), "the item that threw is not tried a second time");
            assertEquals(1, domain.calls(), "the reset ran exactly once");
            assertEquals(1L, chain.controller.resets(), "and no second generation is invented for it");
            assertEquals(1L, chain.epochs.current());
            assertEquals("reset-faulted:RuntimeException", traceResult(),
                "the fault it was answered with stands");
        } finally {
            module.installDomain(new TestDomain("fixture-fault"));
        }

        assertTrue(module.rollbackCommitChain(), "the chain that saw the throw is given back");
        assertFalse(module.commitResetPending(), "and that is what ends the unfinished mark");
    }

    @Test
    void aResetThatThrowsInsideABatchKeepsTheTailAndWhatAlreadyRan() throws Exception {
        install();
        for (int index = 0; index < 3; index++) {
            onAnotherThread(module, "host:console-" + index);
        }
        assertEquals(3, module.resetQueueDepth());
        TestDomain domain = new TestDomain("fixture-fault").throwingOnCall(2);
        TestDomain idle = new TestDomain("fixture-fault");
        module.installDomain(domain);
        try {
            KernelModule.SafePoint point = module.markSafePoint();

            assertEquals(1, point.carried(), "the item before the throw ran and is not run again");
            assertEquals(2, domain.calls(), "the reset of the item that threw was entered, once");
            assertEquals(2L, chain.controller.resets(),
                "the item that threw had already entered the chain");
            assertEquals(2L, chain.epochs.current(), "and its generation is not given back");
            assertEquals(List.of("host:console-2"), module.resetQueueSources(),
                "the item behind it never started and keeps its own source and place");
            assertEquals(0, module.mailbox().inProgress(), "the batch is not left half open");
            assertTrue(module.commitResetPending(), "and the mailbox says it is not settled");

            String[] faulted = traceFields();

            assertEquals("host:console-1", faulted[0], "the item that threw is answered with its fault");
            assertEquals(Long.toString(Thread.currentThread().threadId()), faulted[2]);
            assertEquals("2", faulted[3]);
            assertEquals("reset-faulted:RuntimeException", faulted[4]);

            KernelModule.SafePoint again = module.markSafePoint();

            assertEquals(0, again.carried(), "the item that threw is not tried again");
            assertEquals(2, domain.calls(), "and its reset is not entered a second time");
            assertEquals(2L, chain.controller.resets(), "and nothing advances the chain further");
            assertEquals(List.of("host:console-2"), module.resetQueueSources(),
                "the item behind it is still there to be answered");
        } finally {
            module.installDomain(idle);
        }

        assertTrue(module.rollbackCommitChain(), "the chain that saw the throw is given back");
        module.markSafePoint();

        assertEquals(0, module.resetQueueDepth(),
            "and only then is the item that never started answered");
        assertEquals(2, domain.calls(),
            "the item that threw was entered once and is not entered again");
        assertEquals(1, idle.calls(), "the item that never started is the one that runs after it");
        assertEquals(2L, chain.controller.resets(), "the chain that saw the throw is not asked again");
        assertFalse(module.commitResetPending());
    }

    @Test
    void aSafePointReachedFromInsideAResetDoesNotTakeTheBatchThatIsRunning() throws Exception {
        install();
        for (int index = 0; index < 3; index++) {
            onAnotherThread(module, "host:console-" + index);
        }
        TestDomain domain = new TestDomain("fixture-reentry").reenteringOnCall(1);
        module.installDomain(domain);
        try {
            KernelModule.SafePoint point = module.markSafePoint();

            assertEquals(3, point.carried(), "every item of the batch is answered once");
            assertEquals(3L, chain.controller.resets());
            assertTrue(domain.reentered(), "the reset did reach the safe point from inside the batch");
            assertEquals(0, domain.inner().carried(), "and the call from inside it carries nothing out");
            assertEquals(0, domain.inner().queued(), "and takes nothing out of the queue");
            assertEquals(3, domain.inProgressSeen(),
                "the batch that is running keeps its own places instead of being taken over");
            assertEquals(0, module.mailbox().inProgress(), "and it closes when it is done");
            assertFalse(module.commitResetPending());
        } finally {
            module.installDomain(new TestDomain("fixture-reentry"));
        }
    }


    // ---- the one gate over the reset: a fault, and every entry that reaches it ----------------------

    @Test
    void aFaultFreezesTheOwnerRequestAndTheLegacyClearUntilTheChainIsGivenBack() throws Exception {
        install();
        for (int index = 0; index < 3; index++) {
            onAnotherThread(module, "host:console-" + index);
        }
        TestDomain domain = new TestDomain("fixture-fault").throwingOnCall(2);
        TestDomain idle = new TestDomain("fixture-fault");
        module.installDomain(domain);
        try {
            KernelModule.SafePoint point = module.markSafePoint();

            assertEquals(1, point.carried(), "the item before the throw ran and is not run again");
            assertEquals(2, domain.calls(), "the reset of the item that threw was entered once");
            assertEquals(2L, chain.controller.resets(), "and that entry is not given back");
            assertEquals(2L, chain.epochs.current());
            assertEquals(List.of("host:console-2"), module.resetQueueSources(),
                "the item behind the one that threw keeps its own place");

            KernelModule.ResetRequest owner = module.requestReadingsReset("host:console-owner");
            module.resetReadings();

            assertFalse(owner.carriedOut(), "the owner entry is refused while the fault stands");
            assertEquals("deferred", owner.reason(), "and the request is held, not dropped");
            assertEquals(2, domain.calls(), "no entry enters a domain while the fault stands");
            assertEquals(2L, chain.controller.resets(), "and none of them starts a generation");
            assertEquals(2L, chain.epochs.current(), "nor advances the lifecycle");
            assertEquals(List.of("host:console-2"), module.resetQueueSources(),
                "the item that never started is still where it was");
            assertEquals(0, module.mailbox().inProgress(), "and no attempt is left in flight");
            assertEquals("reset-faulted:RuntimeException", traceResult(),
                "the fault is still what reads back after both entries were asked");
            assertTrue(module.commitResetPending(), "the mailbox says it still owes a reset");
        } finally {
            module.installDomain(idle);
        }

        assertTrue(module.rollbackCommitChain(), "the chain that saw the throw is given back");
        KernelModule.SafePoint after = module.markSafePoint();

        assertEquals(2, after.carried(), "only then are the requests that never started answered");
        assertEquals(0, module.resetQueueDepth());
        assertEquals(2, domain.calls(), "the item that threw is not entered a second time");
        assertEquals(2, idle.calls(), "the un-started item and the request held behind it are what runs");
        assertEquals(2L, chain.controller.resets(), "and the chain that saw the throw is not asked again");
        assertEquals("reset-at-safe-point", traceResult(), "the fault is over in the readout too");
        assertFalse(module.commitResetPending());
    }

    @Test
    void reinstallingTheSameChainDoesNotClearTheFaultAndOnlyTheUnstartedItemRunsAfterAHandBack()
        throws Exception {
        install();
        onAnotherThread(module, "host:console-0");
        onAnotherThread(module, "host:console-1");
        TestDomain domain = new TestDomain("fixture-fault").throwingOnCall(1);
        TestDomain idle = new TestDomain("fixture-fault");
        module.installDomain(domain);
        try {
            KernelModule.SafePoint point = module.markSafePoint();

            assertEquals(0, point.carried(), "the first item threw, so nothing was carried out");
            assertEquals(1, domain.calls());
            assertEquals(1L, chain.controller.resets(), "the reset had entered the chain when it threw");
            assertEquals("host:console-0", traceFields()[0]);

            KernelModule.CommitBinding again = module.installCommitChain(chain.log, chain.controller);

            assertTrue(again.bound(), "the chain that is already bound may be installed again");
            assertEquals("reset-faulted:RuntimeException", traceResult(),
                "and that is not a recovery: the fault is still what reads back");
            KernelModule.SafePoint refused = module.markSafePoint();

            assertEquals(0, refused.carried(), "the reset that threw still refuses every attempt");
            assertEquals(1, domain.calls(), "so the domain is not entered again");
            assertEquals(1L, chain.controller.resets(), "and no generation starts after it");
            assertEquals(List.of("host:console-1"), module.resetQueueSources());
            assertTrue(module.commitResetPending());
        } finally {
            module.installDomain(idle);
        }

        assertTrue(module.rollbackCommitChain(), "the owner hands the chain back");
        KernelModule.SafePoint after = module.markSafePoint();

        assertEquals(1, after.carried(), "and only the item that never started is answered");
        assertEquals(1, domain.calls(), "the item that threw is not entered again");
        assertEquals(1, idle.calls(), "the item behind it runs on the domain of this step");
        assertEquals(1L, chain.controller.resets(), "the chain that saw the throw is not asked again");
        assertEquals("host:console-1", traceFields()[0], "and the readout leaves the fault behind");
        assertEquals("reset-at-safe-point", traceResult());
        assertFalse(module.commitResetPending());
    }

    @Test
    void aResetThatReentersTheRequestEntryOrTheLegacyClearIsHeldUntilTheNextSafePoint()
        throws Exception {
        install();
        for (int index = 0; index < 3; index++) {
            onAnotherThread(module, "host:console-" + index);
        }
        TestDomain requesting = new TestDomain("fixture-reentry").requestingOnCall(1,
            "host:console-inside");
        module.installDomain(requesting);
        try {
            KernelModule.SafePoint point = module.markSafePoint();

            assertEquals(3, point.carried(), "every item of the batch is answered once");
            assertEquals(3, requesting.calls(), "the call from inside the batch enters no domain");
            assertEquals(3L, chain.controller.resets(), "and starts no generation of its own");
            assertNotNull(requesting.innerRequest(), "the reset did reach the request entry from inside");
            assertFalse(requesting.innerRequest().carriedOut(), "and that call is not carried out there");
            assertEquals("deferred", requesting.innerRequest().reason(), "it is held for the next point");
            assertEquals(3, requesting.inProgressSeen(), "the batch keeps its own places while it runs");
            assertEquals(0, module.mailbox().inProgress(), "and it closes when it is done");
            assertTrue(module.commitResetPending(), "the request made from inside is still owed");

            KernelModule.SafePoint next = module.markSafePoint();

            assertEquals(1, next.carried(), "the next safe point answers the request that was held");
            assertEquals(4, requesting.calls(), "and that is the fourth reset the domain sees");
            assertEquals(4L, chain.controller.resets());
            assertFalse(module.commitResetPending());
        } finally {
            module.installDomain(new TestDomain("fixture-reentry"));
        }

        for (int index = 0; index < 3; index++) {
            onAnotherThread(module, "host:console-legacy-" + index);
        }
        TestDomain clearing = new TestDomain("fixture-reentry").clearingOnCall(1);
        module.installDomain(clearing);
        try {
            KernelModule.SafePoint point = module.markSafePoint();

            assertEquals(3, point.carried());
            assertEquals(3, clearing.calls(), "the legacy clear reached from inside enters no domain");
            assertEquals(7L, chain.controller.resets(), "and starts no generation of its own");
            assertEquals(3, clearing.inProgressSeen(), "the batch keeps its own places while it runs");
            assertEquals(0, module.mailbox().inProgress());
            assertTrue(module.commitResetPending(), "the legacy clear is held for the next safe point");

            KernelModule.SafePoint next = module.markSafePoint();

            assertEquals(1, next.carried(), "and the next safe point carries it out");
            assertEquals(4, clearing.calls());
            assertEquals(8L, chain.controller.resets());
            assertFalse(module.commitResetPending());
        } finally {
            module.installDomain(new TestDomain("fixture-reentry"));
        }
    }

    @Test
    void anOwnerResetThatLetsANewerRequestInKeepsThatRequestsLine() {
        install();
        TestDomain domain = new TestDomain("fixture-order").enqueueingOnCall(1, "host:console-later");
        module.installDomain(domain);
        try {
            KernelModule.ResetRequest owner = module.requestReadingsReset("host:console-owner");

            assertTrue(owner.carriedOut(), "the owner reset runs where it was asked");
            assertEquals(1L, chain.controller.resets(), "and it is one generation");
            assertEquals(1L, chain.epochs.current());
            assertEquals("queued-for-owner-safe-point", domain.innerRequest().reason(),
                "the request that arrived while it ran was admitted to the queue");
            String[] during = traceFields();

            assertEquals("host:console-later", during[0],
                "the line belongs to the request that arrived while the reset was running");
            assertEquals(Long.toString(domain.innerThreadId()), during[1]);
            assertEquals("-", during[2], "and that request is not answered yet");
            assertEquals("queued-for-owner-safe-point", during[4],
                "the answer of the reset that let it in is not written over it");

            KernelModule.SafePoint point = module.markSafePoint();

            assertEquals(1, point.carried(), "the request that arrived during the reset is answered next");
            String[] after = traceFields();

            assertEquals("host:console-later", after[0], "and it updates its own line");
            assertEquals(Long.toString(domain.innerThreadId()), after[1]);
            assertEquals(Long.toString(Thread.currentThread().threadId()), after[2],
                "answered on the thread the safe point ran on");
            assertEquals("2", after[3], "naming the generation it carried");
            assertEquals("reset-at-safe-point", after[4]);
            assertEquals(2L, chain.controller.resets(), "each of the two requests is one generation");
            assertEquals(2L, chain.epochs.current());
            assertFalse(module.commitResetPending());
        } finally {
            module.installDomain(new TestDomain("fixture-order"));
        }
    }

    @Test
    void aFaultStaysReadableAfterANewerRequestAndEndsWithTheHandBack() throws Exception {
        install();
        onAnotherThread(module, "host:console-0");
        onAnotherThread(module, "host:console-1");
        TestDomain domain = new TestDomain("fixture-fault").throwingOnCall(1);
        TestDomain idle = new TestDomain("fixture-fault");
        module.installDomain(domain);
        try {
            module.markSafePoint();

            String[] fault = traceFields();

            assertEquals("host:console-0", fault[0]);
            assertEquals("reset-faulted:RuntimeException", fault[4]);
            KernelModule.Mailbox before = module.mailbox();

            HostDriveFixture.Asked asked = HostDriveFixture.requestFromAnotherThread(module,
                "host:console-after-fault");
            String[] held = traceFields();

            assertEquals("queued-for-owner-safe-point", asked.request().reason(),
                "the request made after the fault is admitted, not dropped");
            assertEquals(before.admitted() + 1L, module.mailbox().admitted(), "and it is counted once");
            assertEquals(fault[0], held[0], "and the fault is still the identity that reads back");
            assertEquals(fault[2], held[2]);
            assertEquals(fault[3], held[3]);
            assertEquals("reset-faulted:RuntimeException", held[4],
                "a queued line does not take the fault out of the readout");
            assertEquals(List.of("host:console-1", "host:console-after-fault"),
                module.resetQueueSources(), "and both requests are still held");
            assertTrue(module.commitResetPending());
        } finally {
            module.installDomain(idle);
        }

        assertTrue(module.rollbackCommitChain(), "the hand-back is what ends the fault");
        KernelModule.SafePoint after = module.markSafePoint();

        assertEquals(2, after.carried(), "both requests that never started are answered");
        assertEquals(1, domain.calls(), "the reset that threw is not entered again");
        assertEquals(2, idle.calls());
        assertEquals(1L, chain.controller.resets(), "and the chain that saw the throw is not asked again");
        assertEquals("host:console-after-fault", traceFields()[0], "the readout moves on");
        assertEquals("reset-at-safe-point", traceResult());
        assertEquals(0, module.resetQueueDepth());
        assertFalse(module.commitResetPending());
    }

    /** A domain the reset of the module reaches on the real carry-out path: it counts the resets it is
     * given and, on the call it was told to, throws, calls the safe point back from inside the reset
     * that is running, asks for a whole reset through one of the two entries, or asks from another
     * thread while the reset runs. No world data and no fault seam in the code under test are needed. */
    private static final class TestDomain implements KernelDomain {

        private final String id;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger inProgressSeen = new AtomicInteger(-1);
        private final AtomicReference<KernelModule.SafePoint> inner = new AtomicReference<>();
        private final AtomicReference<KernelModule.ResetRequest> innerRequest = new AtomicReference<>();
        private final AtomicReference<Long> innerThread = new AtomicReference<>();
        private int throwingOnCall;
        private int reenteringOnCall;
        private int requestingOnCall;
        private int clearingOnCall;
        private int enqueueingOnCall;
        private String innerSource = "";

        TestDomain(String id) {
            this.id = id;
        }

        TestDomain throwingOnCall(int call) {
            this.throwingOnCall = call;
            return this;
        }

        TestDomain reenteringOnCall(int call) {
            this.reenteringOnCall = call;
            return this;
        }

        /** The call on which the reset that is running asks for a whole reset through the request entry. */
        TestDomain requestingOnCall(int call, String source) {
            this.requestingOnCall = call;
            this.innerSource = source;
            return this;
        }

        /** The call on which the reset that is running asks for a whole reset through the legacy clear. */
        TestDomain clearingOnCall(int call) {
            this.clearingOnCall = call;
            return this;
        }

        /** The call on which a request from another thread arrives while this reset is still running. */
        TestDomain enqueueingOnCall(int call, String source) {
            this.enqueueingOnCall = call;
            this.innerSource = source;
            return this;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void tick(long tickIndex) {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public void readings(DomainReadings readings) {
        }

        @Override
        public List<String> selfCheck(List<String> failures, long tick) {
            return failures;
        }

        @Override
        public void reset() {
            int call = calls.incrementAndGet();
            if (call == throwingOnCall) {
                throw new RuntimeException("the reset was refused where it ran");
            }
            if (call == reenteringOnCall) {
                KernelModule module = KernelModule.instance();
                inner.set(module.markSafePoint());
                inProgressSeen.set(module.mailbox().inProgress());
            }
            if (call == requestingOnCall) {
                KernelModule module = KernelModule.instance();
                innerRequest.set(module.requestReadingsReset(innerSource));
                inProgressSeen.set(module.mailbox().inProgress());
            }
            if (call == clearingOnCall) {
                KernelModule module = KernelModule.instance();
                module.resetReadings();
                inProgressSeen.set(module.mailbox().inProgress());
            }
            if (call == enqueueingOnCall) {
                HostDriveFixture.Asked asked = HostDriveFixture.requestFromAnotherThread(
                    KernelModule.instance(), innerSource);
                innerRequest.set(asked.request());
                innerThread.set(asked.threadId());
            }
        }

        int calls() {
            return calls.get();
        }

        boolean reentered() {
            return inner.get() != null;
        }

        KernelModule.SafePoint inner() {
            return inner.get();
        }

        KernelModule.ResetRequest innerRequest() {
            return innerRequest.get();
        }

        long innerThreadId() {
            return innerThread.get();
        }

        int inProgressSeen() {
            return inProgressSeen.get();
        }
    }
}
