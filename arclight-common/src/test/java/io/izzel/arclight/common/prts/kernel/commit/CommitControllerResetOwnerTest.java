/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What one safe point of a reset does to the lifecycle it judges: which items it gives back, what a
 * ticket of the generation that ended is answered with, and that ending the lifecycle of the log is
 * not part of it. No host supplier takes part. */
class CommitControllerResetOwnerTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final String NODE = "a/0";
    private static final long EFFECT = 9L;

    private static final class Plans implements CommitLog.OrderSource {

        @Override
        public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
            return WORLD.equals(worldId) && DOMAIN.equals(domainId) && NODE.equals(nodeKey)
                ? new CommitLog.Resolved(7L, 0, false) : null;
        }
    }

    private static final class Fixture {

        final Plans plans = new Plans();
        final EffectStore store = new EffectStore(4096, 8);
        final CommitLog log = new CommitLog(() -> 4, plans, store);
        final CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        final CommitReservation reservations =
            new CommitReservation(log, store, plans, "owner-a", epochs);

        CommitTicket.ReserveRequest request() {
            CommitNamespaces.Namespace producer =
                new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L);
            CommitNamespaces.Namespace controller = reservations.controller();
            CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 11L, 5L, 3L,
                "published", 7L, EFFECT, producer, controller);
            EffectStore.Effect variant =
                new EffectStore.Effect(EFFECT, "publish", "PUBLISHED", List.of("a=1", "b=2"));
            return new CommitTicket.ReserveRequest(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY,
                7L, 0, 7L, 4242L, 2, identity, controller, variant, variant, variant);
        }
    }

    private static CommitTicket.Outcome published() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
            List.of("a=1", "b=2"), 2, "");
    }

    /** Every account of the generation in flight, as one reading. */
    private static String ledger(Fixture f) {
        return "log[sequence=" + f.log.sequence() + " accepted=" + f.log.accepted() + " dropped="
            + f.log.dropped() + " ticketStored=" + f.log.ticketStored() + " ringDepth="
            + f.log.ringDepth() + " heldPlans=" + f.log.heldPlans() + " reservedSlots="
            + f.log.reservedSlots() + " taken=" + f.log.reservationsTaken() + " consumed="
            + f.log.reservationsConsumed() + " released=" + f.log.reservationsReleased() + "]"
            + " reservations[reserveSuccess=" + f.reservations.reserveSuccess() + " finalized="
            + f.reservations.finalized() + " released=" + f.reservations.releasedCount()
            + " outstanding=" + f.reservations.outstanding() + " ledger="
            + f.reservations.terminalLedger().size()
            + " published=" + f.reservations.publishedTotal() + "]"
            + " store[capacitySlots=" + f.store.capacitySlots() + " capacityFinalized="
            + f.store.capacityFinalized() + " capacityReserved=" + f.store.capacityReserved() + "]"
            + " epoch=" + f.epochs.current();
    }

    @Test
    void aGrantedDecisionLeavesTheLogToItsOwnerAndOneClearKeepsTheProjection() {
        Fixture f = new Fixture();
        CommitController controller = new CommitController(f.reservations, ticket -> published());
        assertTrue(controller.submit(f.request()).admitted());
        assertEquals(1, f.log.ringDepth(), "the line of the lifecycle is still held by the ring");

        CommitController.ResetDecision decision = controller.enterReset();

        assertTrue(decision.granted());
        assertEquals(1L, decision.epoch());
        assertNull(f.log.lastResetTyped(),
            "the controller ends no lifecycle, so there is no projection to read");
        assertEquals(1L, f.log.sequence(), "and the line is still where it was written");

        f.log.reset();
        assertEquals(1, f.log.lastResetTyped().typedSteps().size(),
            "one clear folds that line into the projection of the lifecycle it ended");

        f.log.reset();
        assertEquals(0, f.log.lastResetTyped().typedSteps().size(),
            "a second clear replaces the projection with an empty one: clearing twice is not a no-op, "
                + "which is why one logical reset reaches the clear exactly once");
    }

    @Test
    void aResetGivesBackWhatNeverStartedAndTakesNoLineWithIt() {
        Fixture f = new Fixture();
        CommitController controller = new CommitController(f.reservations, ticket -> published());
        CommitTicket ticket = f.reservations.reserve(f.request()).ticket();
        assertEquals(1, f.log.reservedSlots(), "the reservation holds a slot of the ring");
        assertEquals(1, f.store.capacitySlots(), "and the room of its record");
        long sequence = f.log.sequence();

        CommitController.ResetDecision decision = controller.enterReset();

        assertTrue(decision.granted());
        assertEquals(1L, decision.epoch(), "the generation moves by one");
        assertEquals(1, decision.voided(), "the item that never started is given back");
        assertEquals(0, decision.publishing());
        assertEquals(0, f.log.reservedSlots(), "its slot of the ring is free again");
        assertEquals(0, f.store.capacitySlots(), "and its room is back");
        assertEquals(1L, f.reservations.releasedCount());
        assertEquals("VOIDED", f.reservations.terminalLedger().get(0).state());
        assertTrue(f.reservations.conservationHolds());
        assertEquals(sequence, f.log.sequence(), "and no line of the log was touched");
        assertNull(f.log.lastResetTyped());
        assertFalse(f.reservations.beginPublish(ticket),
            "the handle of the generation that ended starts nothing");
    }

    @Test
    void aTicketOfAnEndedGenerationIsRefusedOnEveryPathAndLeavesTheNewOneWhereItWas() {
        Fixture f = new Fixture();
        CommitController controller = new CommitController(f.reservations, ticket -> published());
        CommitTicket inFlight = f.reservations.reserve(f.request()).ticket();
        assertTrue(f.reservations.beginPublish(inFlight), "its side effect has run");
        assertFalse(controller.enterReset().granted(),
            "the safe point keeps a line that is publishing out of the reset");
        assertEquals("publishing-in-flight", controller.enterReset().reason());
        f.epochs.advance();
        String before = ledger(f);

        assertFalse(f.reservations.beginPublish(inFlight), "an old handle starts nothing");
        assertEquals(1L, f.reservations.crossLogRefusals());

        CommitReservation.FinalizeResult late = f.reservations.finalize(inFlight, published());
        assertFalse(late.finalized(), "and its line is not written into the new generation");
        assertEquals("stale-ticket", late.diagnostic());
        assertEquals(1L, f.reservations.staleRefusals());
        assertEquals(1, f.reservations.pendingCount(),
            "the item is carried as one waiting for a disposition, not dropped in silence");
        assertEquals(0, f.reservations.publishing(),
            "and it stops reading as a publish that is still running");

        assertFalse(f.reservations.release(inFlight, CommitTicket.ReleaseReason.CANCELLED).released());
        assertEquals(2L, f.reservations.staleRefusals());
        assertEquals(before, ledger(f), "and the generation in flight is where it was");
    }

    @Test
    void aLateTerminalFromAnotherThreadIsRefusedForItsThreadAndMovesNothing() throws Exception {
        Fixture f = new Fixture();
        CommitController controller = new CommitController(f.reservations, ticket -> published());
        CommitTicket inFlight = f.reservations.reserve(f.request()).ticket();
        assertTrue(f.reservations.beginPublish(inFlight));
        f.epochs.advance();
        String before = ledger(f);
        int pending = f.reservations.pendingCount();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<CommitReservation.FinalizeResult> late = new AtomicReference<>();
            pool.submit(() -> late.set(f.reservations.finalize(inFlight, published())))
                .get(30, TimeUnit.SECONDS);

            assertFalse(late.get().finalized());
            assertEquals("off-owner-thread", late.get().diagnostic());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, f.reservations.offThreadRefusals());
        assertEquals(0L, f.reservations.staleRefusals(), "the thread is judged before the generation");
        assertEquals(pending, f.reservations.pendingCount(), "and no phase was rewritten");
        assertEquals(before, ledger(f));
    }
}
