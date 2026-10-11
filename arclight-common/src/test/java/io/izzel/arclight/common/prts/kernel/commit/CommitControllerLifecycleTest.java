/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lifecycle of a controlled write: which thread may start the side effect, what an item whose
 * terminal line cannot be written becomes, and whether the balance of a lifecycle closes across
 * resets. */
class CommitControllerLifecycleTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final String NODE = "a/0";

    private static final class Plans implements CommitLog.OrderSource {

        long plan = 7L;
        int position;

        @Override
        public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
            return WORLD.equals(worldId) && DOMAIN.equals(domainId) && NODE.equals(nodeKey)
                ? new CommitLog.Resolved(plan, position, false) : null;
        }
    }

    private static final class Fixture {

        final Plans plans = new Plans();
        final EffectStore store;
        final CommitLog log;
        final CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        final CommitReservation reservations;

        Fixture(int ringCap, int storeBytes, int storeRecords) {
            store = new EffectStore(storeBytes, storeRecords);
            log = new CommitLog(() -> ringCap, plans, store);
            reservations = new CommitReservation(log, store, plans, "owner-a", epochs);
        }

        CommitTicket.ReserveRequest request(long effectRef, long instance) {
            CommitNamespaces.Namespace controller = reservations.controller();
            CommitNamespaces.Namespace producer =
                new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L);
            List<String> rows = List.of("a=1", "b=2");
            CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instance, 5L, 3L,
                "published", plans.plan, effectRef, producer, controller);
            EffectStore.Effect variant = new EffectStore.Effect(effectRef, "publish", "PUBLISHED", rows);
            return new CommitTicket.ReserveRequest(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY,
                plans.plan, plans.position, plans.plan, 4242L, rows.size(), identity, controller,
                variant, variant, variant);
        }
    }

    private static CommitTicket.Outcome outcome() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
            List.of("a=1", "b=2"), 2, "");
    }

    private static void assertBalance(CommitReservation reservations, String where) {
        assertEquals(reservations.reserveSuccess(),
            reservations.finalized() + reservations.releasedCount() + reservations.outstanding(),
            "reserve = finalized + released + outstanding " + where);
        assertTrue(reservations.conservationHolds(), where);
    }

    @Test
    void beginPublishFromAnotherThreadIsRefusedAndLeavesTheReservationAlone() throws Exception {
        Fixture f = new Fixture(4, 4096, 4);
        CommitTicket ticket = f.reservations.reserve(f.request(100L, 41L)).ticket();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicBoolean started = new AtomicBoolean(true);
            pool.submit(() -> started.set(f.reservations.beginPublish(ticket))).get(30, TimeUnit.SECONDS);

            assertFalse(started.get(), "a side effect does not start on a thread that is not the owner");
            assertEquals(1L, f.reservations.offThreadRefusals());
            assertEquals(1, f.reservations.outstanding(), "the reservation is where it was");
            assertTrue(f.reservations.release(ticket, CommitTicket.ReleaseReason.CANCELLED).released(),
                "so the owner can still give it back");
            assertEquals(0, f.reservations.outstanding());
            assertEquals(0, f.store.capacitySlots());
            assertBalance(f.reservations, "after an off-thread start and a give-back");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTerminalTheLogRefusesLeavesAnItemWaitingAndNotAPublishingOne() {
        Fixture f = new Fixture(4, 4096, 4);
        CommitTicket ticket = f.reservations.reserve(f.request(100L, 41L)).ticket();
        assertTrue(f.reservations.beginPublish(ticket));
        CommitTicket.Outcome grown = new CommitTicket.Outcome(CommitTicket.Terminal.PARTIAL, "publish",
            List.of("a=1", "b=2"), 1, "a-note-nobody-promised-for");

        CommitReservation.FinalizeResult refused = f.reservations.finalize(ticket, grown);

        assertFalse(refused.finalized());
        assertEquals("record-refused", refused.diagnostic());
        assertEquals(0, f.reservations.publishing(), "the side effect is over, nothing is publishing");
        assertEquals(1, f.reservations.outstanding(), "the item stays in flight");
        assertEquals(1, f.reservations.pendingCount(), "and it is readable as an item waiting");
        CommitReservation.Pending pending = f.reservations.pendingDispositions().get(0);
        assertEquals(ticket.ticketId(), pending.ticketId());
        assertEquals(ticket.identity().effectRef(), pending.effectRef());
        assertEquals(7L, pending.planSequence());
        assertEquals(0, pending.position());
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, pending.code());
        assertEquals(1, pending.attempts());
        assertBalance(f.reservations, "after a refused terminal");

        CommitReservation.ReleaseResult late = f.reservations.release(ticket,
            CommitTicket.ReleaseReason.CANCELLED);
        assertFalse(late.released());
        assertEquals("unrecorded", late.diagnostic(), "the item is not a publish that is still running");

        assertEquals("record-refused", f.reservations.finalize(ticket, grown).diagnostic());
        assertEquals(2, f.reservations.pendingDispositions().get(0).attempts(), "a second try is counted");
        assertTrue(f.reservations.pendingCount() <= f.reservations.outstanding(),
            "the table of waiting items cannot grow past the reservations that are in flight");
    }

    @Test
    void anItemThatWaitedForAnEarlierPlaceEndsOnceItsTurnCame() {
        Fixture f = new Fixture(4, 65536, 16);
        f.plans.position = 0;
        CommitTicket first = f.reservations.reserve(f.request(100L, 41L)).ticket();
        f.plans.position = 1;
        CommitTicket second = f.reservations.reserve(f.request(101L, 42L)).ticket();
        f.log.beginTick(1L);
        assertTrue(f.reservations.beginPublish(second));

        assertFalse(f.reservations.finalize(second, outcome()).finalized(),
            "the later place waits for the earlier one");
        assertEquals(1, f.reservations.pendingCount());
        assertEquals(0, f.reservations.publishing());
        assertBalance(f.reservations, "while one item waits");

        assertTrue(f.reservations.beginPublish(first));
        assertTrue(f.reservations.finalize(first, outcome()).finalized());
        assertTrue(f.reservations.finalize(second, outcome()).finalized(),
            "the item that waited ends once its turn came");
        assertEquals(0, f.reservations.pendingCount(), "an item that ended leaves the table");
        assertEquals(0, f.reservations.outstanding());
        assertBalance(f.reservations, "after both items ended");
        assertEquals(2, f.log.ringDepth());
    }

    @Test
    void conservationHoldsAtEveryBreakpointAcrossResets() {
        Fixture f = new Fixture(4, 65536, 64);
        CommitController controller = new CommitController(f.reservations, ticket -> outcome());
        for (int round = 0; round < 40; round++) {
            f.plans.position = round;
            assertTrue(controller.submit(f.request(1000L + round, 500L + round)).admitted(),
                "a terminal at round " + round);
            assertBalance(f.reservations, "after a terminal at round " + round);

            assertTrue(f.reservations.reserve(f.request(2000L + round, 900L + round)).admitted(),
                "a reservation that never publishes at round " + round);
            assertBalance(f.reservations, "with one item in flight at round " + round);

            CommitController.ResetDecision decision = controller.resetAtSafePoint();
            assertTrue(decision.granted(), "round " + round);
            assertEquals(round + 1L, decision.epoch(), "the generation moved by one at round " + round);
            assertEquals(1, decision.voided(), "the item that never published is given back");
            assertBalance(f.reservations, "after a reset at round " + round);
            assertTrue(f.reservations.terminalLedger().size() <= 64,
                "the terminal ledger stays bounded at round " + round);
            assertTrue(f.reservations.pendingCount() <= f.reservations.outstanding(),
                "and so does the table of waiting items at round " + round);
        }
        assertEquals(80L, f.reservations.reserveSuccess(), "two reservations per round");
        assertEquals(40L, f.reservations.finalized());
        assertEquals(40L, f.reservations.releasedCount(), "a voided reservation is a given-back one");
        assertEquals(0, f.reservations.outstanding());
        assertEquals(64, f.reservations.terminalLedger().size());
        assertEquals(16L, f.reservations.ledgerEvicted(), "what the bound dropped is counted");
        assertEquals(0, f.reservations.pendingCount());
        assertEquals(40, f.store.records());
        assertEquals(0, f.store.capacitySlots());
        assertEquals(0, f.store.capacityReservedBytes());
        assertEquals(0, f.store.reservedBytes());
        assertBalance(f.reservations, "after forty lifecycles");
    }
}
