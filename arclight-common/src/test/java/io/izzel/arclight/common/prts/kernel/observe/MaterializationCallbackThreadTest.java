/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsChunkMaterialization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which thread the completion callbacks of the chunk materialization ran on, and what the observer
 * keeps of that.
 *
 * <p>The four ways a completion can reach a callback are driven one at a time, because a callback on
 * the thread the running server owns, one on the thread that completed the future, one on the
 * registering thread because the future was already complete there, and one that carried a failure
 * must not be readable as each other. The distribution is driven with several threads and several
 * worlds at once, because a face that kept one last value would pass every single threaded test.
 * The two host facts this reading rests on - a non-async completion runs on the completing thread,
 * and runs on the registering thread when the future is already complete - are pinned here as well,
 * since the second one is what makes the thread a callback ran on an observation and not a
 * consequence of where the completion happened.
 */
class MaterializationCallbackThreadTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String THE_END = "minecraft:the_end";

    private final ChunkMaterializationObserver producer = new ChunkMaterializationObserver();

    @AfterEach
    void handTheSeamBack() {
        producer.detach();
        PrtsChunkMaterialization.install(null);
    }

    private ChunkMaterializationObserver.CallbackReading report(boolean serverOwned,
            boolean registeredLate, boolean failed) {
        producer.callbackThread(OVERWORLD, Thread.currentThread().threadId(), serverOwned,
            registeredLate, failed, false);
        return producer.callbackThreads();
    }

    @Test
    void aCallbackRegisteredBeforeTheCompletionRunsOnTheCompletingServerThread() {
        ChunkMaterializationObserver.CallbackReading reading = report(true, false, false);
        assertEquals(1L, reading.calls());
        assertEquals(1L, reading.serverOwned());
        assertEquals(0L, reading.offServer());
        assertEquals(1L, reading.byPath()
            .get(ChunkMaterializationObserver.CallbackPath.SERVER_COMPLETION));
        assertEquals(ChunkMaterializationObserver.CallbackPath.SERVER_COMPLETION,
            reading.lastPath());
    }

    @Test
    void aCallbackOnAnAlreadyCompleteFutureIsCountedAsTheRegisteringThread() {
        ChunkMaterializationObserver.CallbackReading reading = report(false, true, false);
        assertEquals(1L, reading.calls());
        assertEquals(1L, reading.offServer());
        assertEquals(1L, reading.registeredLate());
        assertEquals(1L, reading.byPath()
            .get(ChunkMaterializationObserver.CallbackPath.WORKER_REGISTRATION));
    }

    @Test
    void aCallbackOnAWorkerCompletionIsKeptApartFromOneOnTheServerThread() {
        ChunkMaterializationObserver.CallbackReading reading = report(false, false, false);
        assertEquals(1L, reading.offServer());
        assertEquals(0L, reading.registeredLate());
        assertEquals(1L, reading.byPath()
            .get(ChunkMaterializationObserver.CallbackPath.WORKER_COMPLETION));
    }

    @Test
    void aCallbackThatCarriedAFailureIsRecordedOnItsOwn() {
        ChunkMaterializationObserver.CallbackReading reading = report(true, false, true);
        assertEquals(1L, reading.calls());
        assertEquals(1L, reading.failed());
        assertEquals(1L, reading.byPath().get(ChunkMaterializationObserver.CallbackPath.FAILURE));
        assertEquals(1L, reading.serverOwned(), "the thread of a failed completion is still read");
    }

    @Test
    void aLateRegistrationThatHappenedOnTheServerThreadIsNotReadAsAWorker() {
        ChunkMaterializationObserver.CallbackReading reading = report(true, true, false);
        assertEquals(1L, reading.byPath().get(ChunkMaterializationObserver.CallbackPath.OTHER));
        assertEquals(1L, reading.registeredLate());
        assertEquals(1L, reading.serverOwned());
    }

    @Test
    void anIdentityThatCouldNotBeReadIsCountedAndNotReadAsAServerThread() {
        producer.callbackThread(OVERWORLD, 7L, true, false, false, true);
        ChunkMaterializationObserver.CallbackReading reading = producer.callbackThreads();
        assertEquals(1L, reading.calls());
        assertEquals(1L, reading.unknownIdentity());
        assertEquals(7L, reading.lastThreadId());
    }

    @Test
    void thePathsAndTheWorldsEachKeepTheirOwnCount() {
        producer.callbackThread(OVERWORLD, 1L, true, false, false, false);
        producer.callbackThread(OVERWORLD, 2L, false, false, false, false);
        producer.callbackThread(THE_END, 3L, false, true, false, false);
        producer.callbackThread(THE_END, 3L, false, false, true, false);
        ChunkMaterializationObserver.CallbackReading reading = producer.callbackThreads();
        assertEquals(4L, reading.calls());
        assertEquals(4L, reading.byPath().values().stream().mapToLong(Long::longValue).sum(),
            "every callback must land on exactly one path");
        assertEquals(2L, reading.worlds());
        Map<String, Long> worlds = producer.callbackWorldCounts();
        assertEquals(2L, worlds.get(OVERWORLD));
        assertEquals(2L, worlds.get(THE_END));
        assertEquals(THE_END, reading.lastWorldId());
        assertEquals(3L, reading.lastThreadId());
        assertEquals(ChunkMaterializationObserver.CallbackPath.FAILURE, reading.lastPath());
    }

    @Test
    void severalThreadsAreCountedApartInsteadOfBehindOneLastValue() throws Exception {
        int threads = 6;
        int each = 40;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int index = 0; index < threads; index++) {
            Thread reporter = new Thread(() -> {
                try {
                    start.await();
                    for (int count = 0; count < each; count++) {
                        producer.callbackThread(OVERWORLD, Thread.currentThread().threadId(), false,
                            false, false, false);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            reporter.start();
        }
        start.countDown();
        done.await();
        ChunkMaterializationObserver.CallbackReading reading = producer.callbackThreads();
        assertEquals((long) threads * each, reading.calls());
        Map<Long, Long> perThread = producer.callbackThreadCounts();
        assertEquals(threads, perThread.size(), "every reporter must stay visible");
        assertEquals((long) threads * each,
            perThread.values().stream().mapToLong(Long::longValue).sum());
        for (Long count : perThread.values()) {
            assertEquals((long) each, count);
        }
    }

    @Test
    void theThreadFaceIsBoundedAndSaysHowMuchItFolded() {
        for (long id = 1L; id <= 12L; id++) {
            producer.callbackThread(OVERWORLD, id, false, false, false, false);
        }
        ChunkMaterializationObserver.CallbackReading reading = producer.callbackThreads();
        assertEquals(12L, reading.calls());
        assertEquals((long) 8, reading.threads());
        assertEquals(4L, reading.foldedThreads());
        assertEquals(8, producer.callbackThreadCounts().size());
        assertEquals(12L, reading.calls());
    }

    @Test
    void aDetachedProducerIsNeverReachedByACallback() {
        producer.detach();
        PrtsChunkMaterialization.callbackThread(OVERWORLD, 5L, true, false, false, false);
        assertEquals(0L, producer.callbackThreads().calls(),
            "the shipped default must leave the callback face unreached");
        producer.attach();
        PrtsChunkMaterialization.callbackThread(OVERWORLD, 5L, true, false, false, false);
        assertEquals(1L, producer.callbackThreads().calls());
        assertEquals(5L, producer.callbackThreads().lastThreadId());
    }

    @Test
    void aReportThatThrowsIsCountedWhereItHappenedAndNotPropagated() {
        long before = PrtsChunkMaterialization.callbackFailures();
        PrtsChunkMaterialization.install(new PrtsChunkMaterialization.MaterializationTap() {
            @Override
            public void materialized(long ticket, String worldId, long chunkPos, String status,
                                     int generation) {
            }

            @Override
            public void callbackThread(String worldId, long threadId, boolean serverOwned,
                                       boolean registeredLate, boolean failed,
                                       boolean identityUnknown) {
                throw new IllegalStateException("a broken observation");
            }
        });
        PrtsChunkMaterialization.callbackThread(OVERWORLD, 1L, true, false, false, false);
        assertEquals(before + 1L, PrtsChunkMaterialization.callbackFailures(),
            "a report that throws must be counted, not lost");
    }

    @Test
    void theCallbackFaceIsClearedWithTheProducer() {
        report(true, false, false);
        producer.reset();
        ChunkMaterializationObserver.CallbackReading reading = producer.callbackThreads();
        assertEquals(0L, reading.calls());
        assertEquals(0L, reading.threads());
        assertEquals(0L, reading.worlds());
        assertFalse(reading.lastServerOwned());
        assertTrue(reading.byPath().values().stream().allMatch(count -> count == 0L));
        assertTrue(producer.callbackThreadCounts().isEmpty());
    }

    @Test
    void aCallbackOnAnAlreadyCompleteFutureRunsOnTheRegisteringThread() throws Exception {
        CompletableFuture<String> complete = CompletableFuture.completedFuture("product");
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        Thread registrar = new Thread(() -> complete.whenComplete((value, failure) ->
            ranOn.set(Thread.currentThread())));
        registrar.start();
        registrar.join();
        assertNotNull(ranOn.get());
        assertEquals(registrar, ranOn.get(),
            "a non-async completion on a future that is already complete runs on the caller");
    }

    @Test
    void aCallbackRegisteredBeforeCompletionRunsOnTheCompletingThread() throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        Thread registrar = new Thread(() -> future.whenComplete((value, failure) ->
            ranOn.set(Thread.currentThread())));
        registrar.start();
        registrar.join();
        Thread completer = new Thread(() -> future.complete("product"));
        completer.start();
        completer.join();
        assertNotNull(ranOn.get());
        assertEquals(completer, ranOn.get());
        assertFalse(registrar.equals(ranOn.get()));
    }

    @Test
    void aCallbackThatThrowsLeavesTheFutureItObservedUntouched() {
        CompletableFuture<String> observed = CompletableFuture.completedFuture("product");
        CompletableFuture<String> dependent = observed.whenComplete((value, failure) -> {
            throw new IllegalStateException("a broken observation");
        });
        assertTrue(observed.isDone());
        assertFalse(observed.isCompletedExceptionally(),
            "the observed future keeps its own result");
        assertEquals("product", observed.join());
        assertThrows(Exception.class, dependent::join,
            "the failure belongs to the dependent stage this seam discards");
    }
}
