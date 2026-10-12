/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

        final WitnessSource witness;

        Fixture(int ringCap, int storeBytes, int storeRecords) {
            store = new EffectStore(storeBytes, storeRecords);
            witness = new WitnessSource(plans, WORLD, DOMAIN, 3L, "published", 5L,
                () -> plans.plan, NODE);
            log = new CommitLog(() -> ringCap, plans, store, witness);
            reservations = new CommitReservation(log, store, plans, "owner-a", epochs);
        }

        /** The declaration of this fixture mints the mapping of the line it is about to report. */
        CommitTicket.ReserveRequest request(long effectRef, long instance) {
            witness.bindNode(NODE, instance);
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
    void anItemThatWaitedForAnEarlierPlaceStartsOnlyWhenItsTurnCame() {
        Fixture f = new Fixture(4, 65536, 16);
        f.plans.position = 0;
        CommitTicket first = f.reservations.reserve(f.request(100L, 41L)).ticket();
        f.plans.position = 1;
        CommitTicket second = f.reservations.reserve(f.request(101L, 42L)).ticket();
        f.log.beginTick(1L);

        assertFalse(f.reservations.beginPublish(second),
            "the side effect of a later place does not start before the earlier one ends");
        assertEquals(1L, f.reservations.publishRefusals());
        assertEquals(0, f.reservations.publishing(), "nothing of it is running");
        assertEquals(0, f.log.ringDepth(), "and no line was written");
        assertBalance(f.reservations, "while one item waits for its turn");

        assertTrue(f.reservations.beginPublish(first));
        assertTrue(f.reservations.finalize(first, outcome()).finalized());
        CommitReservation.FinalizeResult again = f.reservations.finalize(first, outcome());
        assertFalse(again.finalized(), "a terminal is never written twice");
        assertEquals("already-finalized", again.diagnostic(),
            "the id of an ended ticket is remembered, and only recently");
        assertEquals(1L, f.reservations.doubleFinalize());
        assertTrue(f.reservations.beginPublish(second), "its turn came once the earlier place ended");
        assertTrue(f.reservations.finalize(second, outcome()).finalized());
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

            assertTrue(f.reservations.reserve(f.request(2000L + round, 500L + round)).admitted(),
                "a reservation that never publishes at round " + round);
            assertBalance(f.reservations, "with one item in flight at round " + round);

            CommitController.ResetDecision decision = controller.enterReset();
            assertTrue(decision.granted(), "round " + round);
            assertEquals(round + 1L, decision.epoch(), "the generation moved by one at round " + round);
            assertEquals(1, decision.voided(), "the item that never published is given back");
            // The decision is the controller's; ending the lifecycle of the log is the work of the
            // holder that owns it and happens after a granted decision, never inside one.
            f.log.reset();
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

    @Test
    void aPublisherThatReportsNothingEndsTheItemItHeld() {
        Fixture f = new Fixture(4, 4096, 4);
        CommitController silent = new CommitController(f.reservations, ticket -> null);

        CommitController.SubmitResult result = silent.submit(f.request(100L, 41L));

        assertTrue(result.admitted(), "the terminal line was written");
        assertEquals(CommitTicket.Terminal.FAILED, result.terminal(),
            "an action that reported nothing failed, and says so");
        assertEquals("publisher-silent", f.reservations.terminalLedger().get(0).note());
        assertEquals(0, f.reservations.publishing(), "the item does not stay publishing");
        assertEquals(0, f.reservations.outstanding());
        assertBalance(f.reservations, "after a silent publisher");
    }

    @Test
    void aNestedSubmitDoesNotRunAheadOfTheOuterPlace() {
        Fixture f = new Fixture(4, 65536, 16);
        f.plans.position = 0;
        CommitController[] holder = new CommitController[1];
        AtomicReference<CommitController.SubmitResult> nested = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();
        holder[0] = new CommitController(f.reservations, ticket -> {
            if (starts.incrementAndGet() == 1) {
                f.plans.position = 1;
                nested.set(holder[0].submit(f.request(101L, 42L)));
            }
            return outcome();
        });

        CommitController.SubmitResult outer = holder[0].submit(f.request(100L, 41L));

        assertTrue(outer.admitted());
        assertNotNull(nested.get(), "the outer publish action asked for the next place");
        assertFalse(nested.get().admitted(), "the nested place does not run ahead of the outer one");
        assertEquals("publish-not-started", nested.get().diagnostic());
        assertEquals(1, starts.get(), "the publisher ran once: the nested side effect never ran");
        assertEquals(1L, holder[0].refusedStarts());
        assertEquals(1L, holder[0].supplierCalls());
        assertEquals(0, f.reservations.outstanding(), "the start that was refused took nothing with it");
        assertBalance(f.reservations, "after a refused nested start");
        CommitLog.Replay replay = f.log.closeTick();
        assertEquals(List.of(new CommitLog.Entry.Step(7L, 0, 4242L)), replay.steps(),
            "the ring carries the outer place and nothing of the nested one");
        assertTrue(replay.orderMatches());
    }

    @Test
    void aTerminalThatCannotBeReadIsNotWrittenAndSaysWhy() {
        assertMalformed(new CommitTicket.Outcome(null, "publish", List.of("a=1", "b=2"), 2, ""),
            "terminal-undeclared");
        assertMalformed(new CommitTicket.Outcome(CommitTicket.Terminal.FAILED, "publish",
            List.of("a=1", "b=2"), -1, ""), "unit-count-negative");
        assertMalformed(new CommitTicket.Outcome(CommitTicket.Terminal.PARTIAL, "",
            List.of("a=1", "b=2"), 1, ""), "operation-undeclared");
        assertMalformed(new CommitTicket.Outcome(CommitTicket.Terminal.PARTIAL, "publish",
            List.of("a=1"), 1, ""), "row-count-mismatch");
    }

    private static void assertMalformed(CommitTicket.Outcome malformed, String diagnostic) {
        Fixture f = new Fixture(4, 4096, 4);
        CommitTicket ticket = f.reservations.reserve(f.request(100L, 41L)).ticket();
        assertTrue(f.reservations.beginPublish(ticket));

        CommitReservation.FinalizeResult refused = f.reservations.finalize(ticket, malformed);

        assertFalse(refused.finalized(), diagnostic + " is not written");
        assertEquals(diagnostic, refused.diagnostic());
        assertEquals(0, f.reservations.publishing(), "the item does not stay publishing");
        assertEquals(1, f.reservations.pendingCount(), "it waits for a disposition");
        assertEquals(diagnostic, f.reservations.pendingDispositions().get(0).diagnostic());
        assertEquals(0, f.log.ringDepth(), "no line was written");
        assertBalance(f.reservations, "after " + diagnostic);
    }

    @Test
    void theStateTablesOfALifecycleAreBounded() {
        Fixture f = new Fixture(4, 65536, 200);
        CommitController controller = new CommitController(f.reservations, ticket -> outcome());
        for (int index = 0; index < 70; index++) {
            f.plans.position = index;
            assertTrue(controller.submit(f.request(100L + index, 500L + index)).admitted(),
                "line " + index);
            assertTrue(f.reservations.conservationHolds(), "conservation at line " + index);
            f.log.closeTick();
        }
        assertEquals(0, f.reservations.outstanding());
        assertEquals(0, f.reservations.stateEntries(),
            "an ended ticket leaves no entry behind: the table does not grow with the lines taken");
        assertEquals(64, f.reservations.finishedEntries(), "only the recent ids are kept");
        assertEquals(64, f.reservations.terminalLedger().size());
        assertEquals(6L, f.reservations.ledgerEvicted(), "what the bound dropped is counted");
        assertEquals(140L, f.reservations.unitTotal(), "seventy lines of two units each");
        assertBalance(f.reservations, "after seventy lines");
    }

    @Test
    void closingGivesBackWhatNeverPublishedAndKeepsWhatMayHaveRun() {
        Fixture f = new Fixture(4, 4096, 8);
        f.plans.position = 1;
        CommitTicket quarantined = f.reservations.reserve(f.request(100L, 41L)).ticket();
        assertTrue(f.reservations.beginPublish(quarantined));
        f.plans.position = 0;
        CommitTicket unstarted = f.reservations.reserve(f.request(101L, 42L)).ticket();
        assertNotNull(unstarted, "an earlier place of the pair is held, and it never starts");
        assertFalse(f.reservations.finalize(quarantined, outcome()).finalized(),
            "the terminal of the later place waits for the earlier one that is held");

        CommitReservation.CloseResult closed = f.reservations.close();

        assertEquals(1, closed.givenBack(), "the one that never started goes back");
        assertEquals(1, closed.unrecorded(), "the one whose side effect ran stays readable");
        assertEquals(0, closed.publishing());
        assertTrue(f.reservations.closed());
        assertEquals(1, f.reservations.outstanding(), "the item that may have run is not thrown away");
        assertEquals(1, f.store.capacitySlots(), "and its room is kept with it");
        assertEquals("CLOSED", f.reservations.terminalLedger().get(0).state());
        assertBalance(f.reservations, "after a close");
        assertFalse(f.reservations.release(unstarted, CommitTicket.ReleaseReason.CANCELLED).released());
        CommitReservation.ReserveResult after = f.reservations.reserve(f.request(102L, 43L));
        assertFalse(after.admitted(), "a closed holder takes nothing new");
        assertEquals("lifecycle-closed", after.diagnostic());
        assertEquals(3, f.reservations.stateEntries(),
            "the item that may have run is what is left: one reservation in three tables");
    }

    @Test
    void theOwnerThreadIsNotMovedWhileAReservationIsInFlight() {
        Fixture f = new Fixture(4, 4096, 4);
        CommitTicket ticket = f.reservations.reserve(f.request(100L, 41L)).ticket();

        assertFalse(f.reservations.bindOwnerThread(new Thread(() -> { })),
            "the owner does not move under a live reservation");
        assertSame(Thread.currentThread(), f.reservations.ownerThread());

        assertTrue(f.reservations.release(ticket, CommitTicket.ReleaseReason.CANCELLED).released());
        assertTrue(f.reservations.bindOwnerThread(Thread.currentThread()),
            "with nothing in flight the owner may be bound again");
    }

    @Test
    void aHandleThisHolderNeverMintedMovesNoPhase() {
        Fixture f = new Fixture(4, 4096, 4);
        CommitTicket real = f.reservations.reserve(f.request(100L, 41L)).ticket();
        CommitTicket fake = new CommitTicket(real.ticketId() + 100L, real.logLifetime(), real.batch(),
            real.identity(), real.planSequence(), real.position(), real.ringSlot(), real.effectSlot(),
            real.controller());

        assertFalse(f.reservations.beginPublish(fake),
            "a handle this holder did not mint starts nothing");
        assertEquals(1L, f.reservations.unknownTicket());
        assertTrue(f.reservations.beginPublish(real), "and the real handle is where it was");
    }

    @Test
    void theUnitsOfATerminalAreCountedWithTheTerminalThatReportedThem() {
        Fixture f = new Fixture(4, 65536, 8);
        CommitController published = new CommitController(f.reservations, ticket -> outcome());
        assertTrue(published.submit(f.request(100L, 41L)).admitted());
        assertEquals(2L, f.reservations.unitTotal());

        f.plans.position = 1;
        CommitController partial = new CommitController(f.reservations,
            ticket -> new CommitTicket.Outcome(CommitTicket.Terminal.PARTIAL, "publish",
                List.of("a=1", "b=2"), 5, ""));
        assertTrue(partial.submit(f.request(101L, 42L)).admitted());
        assertEquals(2L, f.reservations.unitTotal(), "a partial terminal is not a published one");
        assertEquals(5L, f.reservations.partialUnits());

        f.plans.position = 2;
        CommitController failed = new CommitController(f.reservations,
            ticket -> new CommitTicket.Outcome(CommitTicket.Terminal.FAILED, "publish",
                List.of("a=1", "b=2"), 7, ""));
        assertTrue(failed.submit(f.request(102L, 43L)).admitted());
        assertEquals(2L, f.reservations.unitTotal());
        assertEquals(5L, f.reservations.partialUnits());
        assertEquals(7L, f.reservations.failedUnits());
    }
}
