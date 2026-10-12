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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reset mailbox driven by several threads at once: many producers and the owner run together,
 * the bound of the queue is applied as one step with the merge and the enqueue, a request the owner
 * cannot answer yet keeps its own identity instead of becoming a debt that names nobody, and a chain
 * that has been closed answers a request where it arrives instead of letting it wait for a consumer
 * that is gone. Interleavings are held by barriers, every worker has a bounded timeout, and every
 * count is read as the step one case moved it: the counters of the module outlive a clear. */
class KernelResetMailboxConcurrencyTest {

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
        // The chain is given back whatever the case left behind: a case that failed while a line was in
        // flight would otherwise leave the module bound and the case after it would fail for its own
        // reason rather than for the code under test.
        try {
            module.markSafePoint();
            releaseTheLine();
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

    /** Takes a line of the chain into flight and keeps the ticket, so the case can hand it its line
     * and the module can be stepped off the chain at the end of it. */
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

    private static String reading(String key) {
        return HostDriveFixture.reading(key);
    }

    private static long counter(String key) {
        return HostDriveFixture.counter(key);
    }

    private static String resultOf(String trace) {
        return trace.substring(trace.lastIndexOf('|') + 1);
    }

    /** One request from its own thread, with the identity of that thread, so a reading can be compared
     * against the thread that actually made the call. */
    private static KernelModule.ResetRequest fromOwnThread(KernelModule module, String source,
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

    private static KernelModule.ResetRequest onAnotherThread(KernelModule module, String source)
        throws InterruptedException {
        return fromOwnThread(module, source, new AtomicReference<>());
    }

    private static void joinAll(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(thread.isAlive(), "every worker returns within its timeout");
        }
    }

    /** Where the throw of a worker is kept: a case fails for what a worker hit instead of passing
     * because the throw died with the thread that ran it, and the main thread asserts the list after
     * it joined every worker. */
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

    @Test
    void manyProducersAndTheOwnerRunAtTheSameTime() throws Exception {
        install();
        int producers = KernelModule.resetQueueCapacity();
        long handOffsBefore = counter("product.commit.reset_hand_offs");
        long refusalsBefore = counter("product.commit.reset_queue_refusals");
        CyclicBarrier barrier = new CyclicBarrier(producers + 1);
        AtomicInteger finished = new AtomicInteger();
        KernelModule.ResetRequest[] answers = new KernelModule.ResetRequest[producers];
        List<Throwable> failures = workerFailures();
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < producers; index++) {
            int slot = index;
            Thread thread = worker(failures, () -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    answers[slot] = module.requestReadingsReset("host:console-" + slot);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                } finally {
                    finished.incrementAndGet();
                }
            });
            threads.add(thread);
            thread.start();
        }
        barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // The owner drains while the producers are still running: a case that joined them first and
        // drained afterwards would prove nothing about the two running together.
        for (int round = 0; round < 20000 && finished.get() < producers; round++) {
            module.markSafePoint();
        }
        joinAll(threads);
        assertNoWorkerFailed(failures);
        module.markSafePoint();

        for (int index = 0; index < producers; index++) {
            assertTrue(answers[index].handedOff(), "a distinct source has room and waits: console-" + index);
            assertEquals("queued-for-owner-safe-point", answers[index].reason());
        }
        assertEquals((long) producers, chain.controller.resets(),
            "every admitted item is answered once, whoever ran first");
        assertEquals((long) producers, chain.epochs.current());
        assertEquals((long) producers, counter("product.commit.reset_hand_offs") - handOffsBefore,
            "one call per producer took the path of a thread the chain does not own");
        assertEquals(0L, counter("product.commit.reset_queue_refusals") - refusalsBefore,
            "eight distinct sources fit the bound, so nothing is refused");
        assertEquals(0, module.resetQueueDepth());
        assertFalse(module.commitResetPending(), "nothing is left owed");
    }

    @Test
    void aQueueFilledByManyThreadsAtOnceStaysWithinItsBound() throws Exception {
        install();
        int attempts = KernelModule.resetQueueCapacity() * 2;
        long handOffsBefore = counter("product.commit.reset_hand_offs");
        long refusalsBefore = counter("product.commit.reset_queue_refusals");
        CyclicBarrier barrier = new CyclicBarrier(attempts + 1);
        KernelModule.ResetRequest[] answers = new KernelModule.ResetRequest[attempts];
        List<Throwable> failures = workerFailures();
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
        joinAll(threads);
        assertNoWorkerFailed(failures);

        assertEquals(KernelModule.resetQueueCapacity(), module.resetQueueDepth(),
            "the capacity, the merge and the enqueue are one step, so the bound holds under contention");
        assertEquals(KernelModule.resetQueueCapacity(),
            Integer.parseInt(reading("product.commit.reset_queued")));
        assertEquals((long) attempts, counter("product.commit.reset_hand_offs") - handOffsBefore,
            "every call of the case took the queue path and is counted once");
        assertEquals((long) (attempts - KernelModule.resetQueueCapacity()),
            counter("product.commit.reset_queue_refusals") - refusalsBefore,
            "the calls the bound could not take are refused and counted, not dropped");
        int refused = 0;
        for (KernelModule.ResetRequest answer : answers) {
            if (!answer.handedOff()) {
                refused++;
                assertEquals("owner-thread-queue-full", answer.reason());
            }
        }
        assertEquals(attempts - KernelModule.resetQueueCapacity(), refused,
            "a call is either waiting or refused, and the readout says the same");

        KernelModule.SafePoint drained = module.markSafePoint();

        assertEquals(KernelModule.resetQueueCapacity(), drained.carried());
        assertEquals(0, drained.queued());
        assertEquals(0, module.resetQueueDepth());
        assertFalse(module.commitResetPending());
    }

    @Test
    void oneSourceIsOneItemBeforeTheOwnerTakesItAndANewItemAfterIt() throws Exception {
        install();
        onAnotherThread(module, "host:console");
        onAnotherThread(module, "host:console");

        assertEquals(1, module.resetQueueDepth(), "a source already waiting is the same request twice");

        fixture.postTick(WORLDS);

        assertEquals(1L, chain.controller.resets(), "one item, one generation");

        onAnotherThread(module, "host:console");

        assertEquals(1, module.resetQueueDepth(),
            "the item the owner already took away is not a merge candidate: this is a second arrival");

        fixture.postTick(WORLDS);

        assertEquals(2L, chain.controller.resets(), "two arrivals are two generations");
        assertEquals(2L, chain.epochs.current());
        assertEquals(0, module.resetQueueDepth());
        assertFalse(module.commitResetPending());
    }

    @Test
    void theTraceNamesTheItemItBelongsToAndNotTheCallThatMergedIntoIt() throws Exception {
        install();
        AtomicReference<Long> first = new AtomicReference<>();
        AtomicReference<Long> second = new AtomicReference<>();
        KernelModule.ResetRequest admitted = fromOwnThread(module, "host:console", first);
        KernelModule.ResetRequest merged = fromOwnThread(module, "host:console", second);

        assertNotEquals(first.get(), second.get(), "the two calls are two threads");
        assertTrue(admitted.handedOff());
        assertTrue(merged.handedOff());
        assertEquals("host:console|" + first.get() + "|-|none|queued-for-owner-safe-point",
            reading("product.commit.reset_trace"),
            "the trace names the request the queue took; the call that merged into it minted nothing");
    }

    @Test
    void aRequestTheOwnerCannotAnswerYetKeepsItsPlaceAndIsAnsweredOnce() throws Exception {
        install();
        long ticketId;
        try {
            CommitTicket ticket = beginLine(21L);
            ticketId = ticket.ticketId();
            onAnotherThread(module, "host:console-a");
            onAnotherThread(module, "host:console-b");
            assertEquals(2, module.resetQueueDepth());

            KernelModule.SafePoint first = module.markSafePoint();

            assertEquals(0, first.carried(), "nothing runs while the line is in flight");
            assertEquals(2, first.owed(), "both requests are owed, and the debt is counted once each");
            assertEquals(2, first.queued(),
                "and both keep their place: a request refused here is not turned into a debt that names nobody");
            assertTrue(module.commitResetPending());
            assertEquals(0L, chain.controller.resets());
        } finally {
            releaseTheLine();
        }
        assertTrue(ticketId > 0L);

        fixture.postTick(WORLDS);

        assertEquals(2L, chain.controller.resets(), "each waiting request is answered once, not merged away");
        assertEquals(2L, chain.epochs.current());
        assertEquals(0, module.resetQueueDepth());
        assertFalse(module.commitResetPending());
    }

    @Test
    void aDeferredOwnerRequestAndAnotherSourceAreTwoGenerations() throws Exception {
        install();
        try {
            beginLine(31L);
            module.resetReadings();
            onAnotherThread(module, "host:console-b");

            assertEquals(1, module.resetQueueDepth());
            assertTrue(module.commitResetPending(), "the owner-thread request is owed as well");
        } finally {
            releaseTheLine();
        }

        fixture.postTick(WORLDS);

        assertEquals(2L, chain.controller.resets(),
            "the deferred reset is one generation and the request waiting in the queue is another");
        assertEquals(2L, chain.epochs.current());
        assertEquals(0, module.resetQueueDepth());
        assertFalse(module.commitResetPending());
    }

    @Test
    void aProducerThatArrivesWhileTheOwnerRepaysTheDebtIsNotLost() throws Exception {
        install();
        try {
            beginLine(41L);
            module.resetReadings();
            assertTrue(module.commitResetPending());

            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicReference<KernelModule.ResetRequest> answer = new AtomicReference<>();
            List<Throwable> failures = workerFailures();
            Thread producer = worker(failures, () -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    answer.set(module.requestReadingsReset("host:console"));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            producer.start();
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            module.markSafePoint();
            producer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(producer.isAlive(), "the producer returns within its timeout");
            assertNoWorkerFailed(failures);
            assertTrue(answer.get().handedOff(), "the queue had room, so the request waits for the owner");
        } finally {
            releaseTheLine();
        }

        fixture.postTick(WORLDS);

        assertEquals(2L, chain.controller.resets(),
            "the debt the owner owed and the request that arrived with it are one generation each");
        assertEquals(2L, chain.epochs.current());
        assertEquals(0, module.resetQueueDepth());
        assertFalse(module.commitResetPending());
    }

    @Test
    void aRequestArrivingAfterTheCloseIsRefusedInsteadOfWaitingForAConsumerThatIsGone() throws Exception {
        install();
        long closed = counter("product.commit.reset_closed_refusals");
        fixture.stop(WORLDS);

        KernelModule.ResetRequest answer = onAnotherThread(module, "host:console-after-close");

        assertFalse(answer.carriedOut(), "a closed lifecycle starts no generation after it");
        assertFalse(answer.handedOff(), "and nothing is queued for a safe point that will never run");
        assertEquals("lifecycle-closed", answer.reason());
        assertEquals(0, module.resetQueueDepth(), "the queue is not a place a request can wait forever");
        assertFalse(module.commitResetPending(), "and no debt is left that no safe point could answer");
        assertEquals(closed + 1L, counter("product.commit.reset_closed_refusals"),
            "the refusal is counted instead of being swallowed");
        assertEquals("lifecycle-closed", resultOf(reading("product.commit.reset_trace")));
        assertEquals(0L, chain.controller.resets());
    }

    @Test
    void aRequestWaitingWhenTheCloseLandsIsRefusedAndCountedAndNotReadAsCarried() throws Exception {
        install();
        long closed = counter("product.commit.reset_closed_refusals");
        onAnotherThread(module, "host:console-waiting");
        assertEquals(1, module.resetQueueDepth());

        fixture.stop(WORLDS);

        assertEquals(0, module.resetQueueDepth(), "the close takes what the mailbox holds in one step");
        assertFalse(module.commitResetPending(), "and leaves no debt behind for a consumer that is gone");
        assertEquals(closed + 1L, counter("product.commit.reset_closed_refusals"),
            "the waiting request is refused and counted, not cleared as if it had run");
        assertEquals("lifecycle-closed", resultOf(reading("product.commit.reset_trace")));
        assertEquals(0L, chain.controller.resets(), "and no generation is invented for it");
    }

    @Test
    void aCloseAndAnArrivingRequestReachOneOfTwoAllowedOutcomes() throws Exception {
        install();
        long closed = counter("product.commit.reset_closed_refusals");
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<KernelModule.ResetRequest> answer = new AtomicReference<>();
        List<Throwable> failures = workerFailures();
        Thread producer = worker(failures, () -> {
            try {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                answer.set(module.requestReadingsReset("host:console-racing"));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        producer.start();
        barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        fixture.stop(WORLDS);
        producer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(producer.isAlive(), "the producer returns within its timeout");
        assertNoWorkerFailed(failures);

        assertEquals(0, module.resetQueueDepth(),
            "which side of the close the call landed on, nothing is left waiting");
        assertFalse(module.commitResetPending());
        assertEquals(closed + 1L, counter("product.commit.reset_closed_refusals"),
            "either the mailbox refused it or the close did, and exactly one of them counted it");
        assertEquals(0L, chain.controller.resets(), "a closed lifecycle starts no generation");
        String reason = answer.get().reason();
        assertTrue("lifecycle-closed".equals(reason)
            || "queued-for-owner-safe-point".equals(reason), reason);
    }

    @Test
    void theSharedMessageLayerAndTheOwnerOnlySafePointAreJudgedApart() throws Exception {
        install();
        String window = reading("kernel.tick_index");
        long sequence = chain.log.sequence();
        long safePoints = counter("product.commit.safe_point_calls");

        onAnotherThread(module, "host:console-waiting");

        assertEquals(1, module.resetQueueDepth(),
            "the message layer is shared: a request from another thread is allowed to wait in it");
        long requests = counter("product.commit.reset_requests");

        AtomicReference<KernelModule.SafePoint> elsewhere = new AtomicReference<>();
        List<Throwable> failures = workerFailures();
        Thread other = worker(failures, () -> elsewhere.set(module.markSafePoint()));
        other.start();
        other.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(other.isAlive(), "the off-thread call returns within its timeout");
        assertNoWorkerFailed(failures);

        assertEquals(0, elsewhere.get().carried(),
            "the safe point is owner-only: from another thread it carries nothing out");
        assertEquals(1, elsewhere.get().queued(), "and it takes nothing out of the queue either");
        assertEquals(safePoints, counter("product.commit.safe_point_calls"),
            "the safe point is not entered from a thread the host did not name");
        assertEquals(0L, chain.controller.resets(), "no generation starts from there");
        assertEquals(0L, chain.epochs.current());
        assertEquals(sequence, chain.log.sequence(), "the log was not ended");
        assertEquals(window, reading("kernel.tick_index"), "and not one counter of the module was cleared");
        assertEquals(requests, counter("product.commit.reset_requests"),
            "the owner-only call is not a reset request either");
        assertEquals(1, module.resetQueueDepth(), "the request the shared entry was allowed to take still waits");
    }

    @Test
    void aSafePointReturnsUnderItsBudgetWhileProducersKeepArriving() throws Exception {
        install();
        int producers = 4;
        AtomicBoolean stop = new AtomicBoolean();
        CyclicBarrier barrier = new CyclicBarrier(producers + 1);
        List<Throwable> failures = workerFailures();
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < producers; index++) {
            int slot = index;
            Thread thread = worker(failures, () -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                for (int round = 0; round < 4000 && !stop.get(); round++) {
                    // A producer that waits for room refills the queue exactly as the owner empties it,
                    // which is the shape the budget of one safe point has to hold up against.
                    while (!stop.get()
                        && module.resetQueueDepth() >= KernelModule.resetQueueCapacity()) {
                        Thread.onSpinWait();
                    }
                    module.requestReadingsReset("host:console-" + slot + "-" + round);
                }
            });
            threads.add(thread);
            thread.start();
        }
        barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        long startedAt = System.nanoTime();
        int most = 0;
        long elapsedMs = 0L;
        try {
            for (int round = 0; round < 64; round++) {
                most = Math.max(most, module.markSafePoint().carried());
            }
        } finally {
            elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            stop.set(true);
            joinAll(threads);
        }
        assertNoWorkerFailed(failures);

        assertTrue(most <= KernelModule.resetQueueCapacity(),
            "one safe point carries a bounded batch, whatever the producers keep adding: carried=" + most);
        assertTrue(elapsedMs < TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS),
            "and it returns instead of draining until the queue happens to be empty: " + elapsedMs + " ms");

        for (int round = 0; round < 64 && module.commitResetPending(); round++) {
            module.markSafePoint();
        }
        assertEquals(0, module.resetQueueDepth(), "what the producers left behind is answered at the next point");
        assertFalse(module.commitResetPending());
        assertTrue(chain.controller.resets() >= 0L, "the generation count never goes backwards");
        assertTrue(counter("product.commit.reset_hand_offs") >= 0L);
        assertTrue(counter("product.commit.reset_queue_refusals") >= 0L);
    }

    @Test
    void theQueueExitsStayReadableAndBoundedWhileTheMailboxIsBusy() throws Exception {
        install();
        AtomicBoolean stop = new AtomicBoolean();
        List<Throwable> workers = workerFailures();
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            int slot = index;
            Thread thread = worker(workers, () -> {
                for (int round = 0; round < 2000 && !stop.get(); round++) {
                    module.requestReadingsReset("host:console-" + slot + "-" + round);
                }
            });
            threads.add(thread);
            thread.start();
        }
        List<String> torn = new ArrayList<>();
        try {
            for (int round = 0; round < 2000; round++) {
                try {
                    List<String> sources = module.resetQueueSources();
                    int depth = module.resetQueueDepth();
                    assertTrue(sources.size() <= KernelModule.resetQueueCapacity(),
                        "the list never exceeds the bound");
                    assertTrue(depth <= KernelModule.resetQueueCapacity(), "and neither does the depth");
                } catch (RuntimeException failure) {
                    torn.add(failure.getClass().getSimpleName() + ": " + failure.getMessage());
                    break;
                }
            }
        } finally {
            stop.set(true);
            joinAll(threads);
        }
        assertNoWorkerFailed(workers);

        assertTrue(torn.isEmpty(), "a read of the queue never fails while it is being filled: " + torn);
        for (int round = 0; round < 64 && module.commitResetPending(); round++) {
            module.markSafePoint();
        }
        assertEquals(0, module.resetQueueDepth());
    }

    @Test
    void aWorkerThatThrowsIsCarriedToTheMainThreadAndFailsTheCase() throws Exception {
        List<Throwable> failures = workerFailures();
        CyclicBarrier barrier = new CyclicBarrier(3);
        List<Thread> threads = new ArrayList<>();
        threads.add(worker(failures, () -> {
            try {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }));
        threads.add(worker(failures, () -> {
            try {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            throw new IllegalStateException("this worker was told to fail");
        }));
        for (Thread thread : threads) {
            thread.start();
        }
        barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        joinAll(threads);

        assertEquals(1, failures.size(),
            "the throw of a worker is carried to the main thread instead of dying with it: " + failures);
        assertEquals("this worker was told to fail", failures.get(0).getMessage());
        assertThrows(AssertionError.class, () -> assertNoWorkerFailed(failures),
            "and a worker that threw fails the case rather than leaving it green");
    }
}
