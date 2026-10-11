/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One room-only handle ended by several threads at once, and a store that faulted. Of the terminals
 * that arrive with the same handle exactly one is written; every other arrival leaves the ledgers
 * where they were, and no ledger of the store goes backwards. The cases here are the ones the
 * positive examples of the capacity protocol do not reach: they are about two threads on one handle,
 * not about one thread on one handle. */
class EffectStoreAtomicsTest {

    private static EffectStore.Effect effect(long ref, String outcome, String... rows) {
        return new EffectStore.Effect(ref, "publish", outcome, List.of(rows));
    }

    @Test
    void twoThreadsEndingTheSameRoomHandleWriteOneTerminal() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 100; round++) {
                EffectStore store = new EffectStore(4096, 8);
                EffectStore.CapacitySlot slot = store.reserveCapacity(7L, 2, 512).slot();
                CyclicBarrier gate = new CyclicBarrier(8);
                AtomicInteger written = new AtomicInteger();
                List<Future<?>> arrivals = new ArrayList<>();
                for (int index = 0; index < 8; index++) {
                    arrivals.add(pool.submit(() -> {
                        gate.await(30, TimeUnit.SECONDS);
                        if (store.finalizeCapacity(slot, effect(7L, "PUBLISHED", "a=1", "b=2")).admitted()) {
                            written.incrementAndGet();
                        }
                        return null;
                    }));
                }
                for (Future<?> arrival : arrivals) {
                    arrival.get(30, TimeUnit.SECONDS);
                }
                String at = "round " + round;
                assertEquals(1, written.get(), "one handle ends one terminal, " + at);
                assertEquals(1, store.records(), at);
                assertEquals(1L, store.stored(), at);
                assertEquals(1L, store.committed(), at);
                assertEquals(1L, store.capacityFinalized(), at);
                assertEquals(7L, store.foreignHandles(), "the other seven arrivals move nothing, " + at);
                assertEquals(0, store.capacitySlots(), at);
                assertEquals(0, store.capacityReservedBytes(), "no promise is left open, " + at);
                assertEquals(0, store.capacityReservedRecords(), "and none went negative, " + at);
                assertEquals(store.record(7L).bytes(), store.bytes(), at);
                assertTrue(store.bytes() > 0, at);
                assertTrue(store.reservedBytes() >= 0 && store.reservedRecords() >= 0, at);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTerminalAndAGiveBackOfTheSameHandleDoNotBothHappen() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 100; round++) {
                EffectStore store = new EffectStore(4096, 8);
                EffectStore.CapacitySlot slot = store.reserveCapacity(7L, 2, 512).slot();
                CyclicBarrier gate = new CyclicBarrier(2);
                Future<Boolean> terminal = pool.submit(() -> {
                    gate.await(30, TimeUnit.SECONDS);
                    return store.finalizeCapacity(slot, effect(7L, "PUBLISHED", "a=1")).admitted();
                });
                Future<Boolean> givenBack = pool.submit(() -> {
                    gate.await(30, TimeUnit.SECONDS);
                    return store.releaseCapacity(slot);
                });
                boolean finished = terminal.get(30, TimeUnit.SECONDS);
                boolean released = givenBack.get(30, TimeUnit.SECONDS);
                String at = "round " + round;
                assertNotEquals(finished, released, "the handle ends once, " + at);
                assertEquals(1L, store.foreignHandles(), at);
                assertEquals(0, store.capacitySlots(), at);
                assertEquals(0, store.capacityReservedBytes(), at);
                assertEquals(0, store.capacityReservedRecords(), at);
                assertEquals(finished ? 1L : 0L, store.capacityFinalized(), at);
                assertEquals(released ? 1L : 0L, store.capacityReleased(), at);
                assertEquals(finished ? 1 : 0, store.records(), at);
                assertTrue(store.bytes() >= 0 && store.reservedBytes() >= 0, at);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aCopyOfAHandleOfThisStoreEndsNothing() {
        EffectStore store = new EffectStore(4096, 8);
        EffectStore.CapacitySlot slot = store.reserveCapacity(7L, 2, 512).slot();
        EffectStore.CapacitySlot copy = new EffectStore.CapacitySlot(slot.ticket(), slot.effectRef(),
            slot.maxRecords(), slot.maxBytes(), slot.storeId());

        assertEquals(slot, copy, "the whole value of the handle is the same");
        assertFalse(store.finalizeCapacity(copy, effect(7L, "PUBLISHED", "a=1")).admitted());
        assertFalse(store.releaseCapacity(copy));
        assertEquals(2L, store.foreignHandles(), "both arrivals of the copy are answered as unknown");
        assertEquals(512, store.capacityReservedBytes(), "the promise of the real handle is untouched");
        assertEquals(1, store.capacitySlots());
        assertTrue(store.finalizeCapacity(slot, effect(7L, "PUBLISHED", "a=1")).admitted());
    }

    @Test
    void aStoreThatFaultedRefusesEveryReservationPath() {
        EffectStore store = new EffectStore(4096, 4);
        EffectStore.CapacitySlot promised = store.reserveCapacity(7L, 1, 64).slot();
        assertFalse(store.finalizeCapacity(promised, effect(7L, "FAILED", "a=1", "b=2")).admitted());
        assertTrue(store.halted(), "a record past its promise halts the store");

        record Path(String name, BooleanSupplier call) {
        }
        List<Path> paths = List.of(
            new Path("freeze room", () -> store.reserve(effect(8L, "PUBLISHED", "a=1")).admitted()),
            new Path("freeze a reference of zero", () -> store.reserve(effect(0L, "PUBLISHED")).admitted()),
            new Path("freeze nothing", () -> store.reserve(null).admitted()),
            new Path("promise room", () -> store.reserveCapacity(9L, 1, 64).admitted()),
            new Path("promise room for a reference of zero",
                () -> store.reserveCapacity(0L, 1, 64).admitted()),
            new Path("promise room that cannot fit",
                () -> store.reserveCapacity(9L, 1, Integer.MAX_VALUE).admitted()));
        long tickets = store.tickets();
        long refused = store.refused();
        int records = store.records();
        int reservedBytes = store.reservedBytes();
        int reservedRecords = store.reservedRecords();
        int capacityBytes = store.capacityReservedBytes();
        int capacityRecords = store.capacityReservedRecords();
        int capacitySlots = store.capacitySlots();

        for (Path path : paths) {
            assertFalse(path.call().getAsBoolean(), path.name() + " is refused while the store is halted");
        }

        assertEquals(refused + paths.size(), store.refused(), "every refused path is counted");
        assertEquals(tickets, store.tickets(), "a refused path takes no ticket");
        assertEquals(records, store.records());
        assertEquals(reservedBytes, store.reservedBytes());
        assertEquals(reservedRecords, store.reservedRecords());
        assertEquals(capacityBytes, store.capacityReservedBytes());
        assertEquals(capacityRecords, store.capacityReservedRecords());
        assertEquals(capacitySlots, store.capacitySlots(), "the item that faulted is still readable");
        assertEquals(1, store.unresolvedTerminals().size());
    }

    @Test
    void aFloodOfReservationsStaysInsideBothBounds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            EffectStore store = new EffectStore(8192, 16);
            List<Future<?>> workers = new ArrayList<>();
            for (int worker = 0; worker < 6; worker++) {
                final int id = worker;
                workers.add(pool.submit(() -> {
                    for (int round = 0; round < 200; round++) {
                        long ref = id * 1000L + round + 1L;
                        EffectStore.Result charged = store.reserve(effect(ref, "PUBLISHED", "a=" + ref));
                        if (charged.admitted()) {
                            if (round % 2 == 0) {
                                store.commit(charged.slot(), effect(ref, "PUBLISHED", "a=" + ref));
                            } else {
                                store.release(charged.slot());
                            }
                        }
                        EffectStore.CapacityResult promised =
                            store.reserveCapacity(100000L + ref, 1, 128);
                        if (promised.admitted()) {
                            store.releaseCapacity(promised.slot());
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> worker : workers) {
                worker.get(60, TimeUnit.SECONDS);
            }
            assertTrue(store.records() <= store.capacityRecords(), "the record bound held");
            assertTrue(store.bytes() <= store.capacityBytes(), "the byte bound held");
            assertEquals(0, store.reservedBytes(), "no freeze is left charged");
            assertEquals(0, store.reservedRecords(), "no freeze is left charged");
            assertEquals(0, store.capacityReservedBytes(), "no promise is left open");
            assertEquals(0, store.capacityReservedRecords(), "no promise is left open");
            assertTrue(store.bytes() >= 0, "the stored bytes never went backwards");
        } finally {
            pool.shutdownNow();
        }
    }
}
