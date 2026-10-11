/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The normal request path of a controller that has no publish action at all. Such a request is
 * refused where it is asked and before anything is moved, so a build that cannot run an action
 * reports that instead of reporting a run that never happened. No host supplier takes part. */
class CommitControllerNotWiredTest {

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

    /** Every account a request could move, as one reading: the ledger is what has to stay put. */
    private static String ledger(Fixture f) {
        return "log[sequence=" + f.log.sequence() + " accepted=" + f.log.accepted() + " dropped="
            + f.log.dropped() + " ticketStored=" + f.log.ticketStored() + " ringDepth="
            + f.log.ringDepth() + " heldPlans=" + f.log.heldPlans() + " reservedSlots="
            + f.log.reservedSlots() + " taken=" + f.log.reservationsTaken() + " consumed="
            + f.log.reservationsConsumed() + " released=" + f.log.reservationsReleased() + "]"
            + " reservations[reserveSuccess=" + f.reservations.reserveSuccess() + " finalized="
            + f.reservations.finalized() + " released=" + f.reservations.releasedCount()
            + " outstanding=" + f.reservations.outstanding() + " publishing="
            + f.reservations.publishing() + " ledger=" + f.reservations.terminalLedger().size()
            + " failed=" + f.reservations.failedTotal() + " published="
            + f.reservations.publishedTotal() + " conservation="
            + f.reservations.conservationHolds() + "]"
            + " store[records=" + f.store.records() + " capacitySlots=" + f.store.capacitySlots()
            + " capacityFinalized=" + f.store.capacityFinalized() + " capacityReserved="
            + f.store.capacityReserved() + " capacityReleased=" + f.store.capacityReleased() + "]"
            + " epoch=" + f.epochs.current();
    }

    @Test
    void aRequestForAnActionThisBuildDoesNotHaveIsRefusedAndMovesNothing() {
        Fixture f = new Fixture();
        CommitController controller = new CommitController(f.reservations, null);

        CommitController.SubmitResult result = controller.submit(f.request());

        assertFalse(result.admitted(), "nothing ran, and the result says so");
        assertNull(result.terminal(), "an action that does not exist reports no terminal");
        assertEquals(RejectCode.NATIVE_UNDECLARED, result.code(), "it reports what is undeclared");
        assertEquals("not-wired", result.diagnostic());
        assertNull(result.ticket(), "no ticket is minted");
        assertEquals(0, result.successBatch());
        assertEquals(0L, result.unitCount());
        assertEquals(1L, controller.notWiredRefusals(), "and it is counted where it was refused");
        assertEquals(0L, controller.supplierCalls(), "the action was never called");
        assertEquals(0L, controller.publishFailures(), "no failure was built in its place");
        assertEquals(0L, controller.submits(), "the request never entered the controlled path");
        assertEquals(0L, f.reservations.reserveSuccess(), "no reservation was taken");
        assertEquals(0L, f.reservations.finalized(), "no terminal line was written");
        assertEquals(0L, f.reservations.failedTotal(), "the failure column is where it was");
        assertEquals(0, f.reservations.terminalLedger().size(), "and the terminal ledger is empty");
        assertEquals(0L, f.reservations.releasedCount());
        assertEquals(0, f.reservations.outstanding());
        assertEquals(0L, f.log.sequence(), "no report number was taken");
        assertEquals(0L, f.log.dropped());
        assertEquals(0L, f.log.ticketStored());
        assertEquals(0, f.log.ringDepth(), "no ring slot was taken");
        assertEquals(0L, f.log.reservationsTaken());
        assertEquals(0L, f.log.reservationsConsumed());
        assertEquals(0, f.log.heldPlans(), "and the frozen places are where they were");
        assertEquals(0L, f.store.capacityFinalized(), "no room was consumed");
        assertEquals(0, f.store.capacitySlots(), "none is held and none leaked");
        assertEquals(0L, f.epochs.current(), "no generation moved");
        assertEquals(0L, controller.epoch());
        assertTrue(f.reservations.conservationHolds());
        assertFalse(controller.executable());
        assertEquals("NONE", controller.publisherWiring());
    }

    @Test
    void threeRequestsInARowAreThreeRefusalsAndMoveNothing() {
        Fixture f = new Fixture();
        CommitController controller = new CommitController(f.reservations, null);
        String before = ledger(f);
        int slotsBefore = f.store.capacitySlots();

        for (int index = 0; index < 3; index++) {
            assertFalse(controller.submit(f.request()).admitted(), "request " + index);
        }

        assertEquals(3L, controller.notWiredRefusals(), "each refusal is counted once");
        assertEquals(before, ledger(f), "and three refused requests moved nothing at all");
        assertEquals(slotsBefore, f.store.capacitySlots());
    }

    @Test
    void aRequestFromAnotherThreadIsRefusedForItsThreadWithAnActionBoundOrNot() throws Exception {
        Fixture f = new Fixture();
        CommitController controller = new CommitController(f.reservations, null);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<CommitController.SubmitResult> other = new AtomicReference<>();
            pool.submit(() -> other.set(controller.submit(f.request()))).get(30, TimeUnit.SECONDS);

            assertFalse(other.get().admitted());
            assertEquals("off-owner-thread", other.get().diagnostic(),
                "the thread of the caller is judged before anything else");
            assertEquals(RejectCode.COUNTER_MISSING, other.get().code());
            assertEquals(0L, controller.notWiredRefusals(),
                "the owner constraint is not short-circuited by the missing action");
            assertEquals(1L, controller.refusals());
            assertEquals("log[sequence=0 accepted=0 dropped=0 ticketStored=0 ringDepth=0 heldPlans=0"
                + " reservedSlots=0 taken=0 consumed=0 released=0]"
                + " reservations[reserveSuccess=0 finalized=0 released=0 outstanding=0 publishing=0"
                + " ledger=0 failed=0 published=0 conservation=true]"
                + " store[records=0 capacitySlots=0 capacityFinalized=0 capacityReserved=0"
                + " capacityReleased=0] epoch=0", ledger(f));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aBoundActionIsNotRefusedForAnUndeclaredOne() {
        Fixture f = new Fixture();
        CommitController controller = new CommitController(f.reservations, ticket -> published());

        CommitController.SubmitResult result = controller.submit(f.request());

        assertTrue(result.admitted());
        assertEquals(CommitTicket.Terminal.PUBLISHED, result.terminal());
        assertEquals(0L, controller.notWiredRefusals(),
            "the question is about this instance, not about a setting read somewhere else");
        assertEquals(1L, controller.supplierCalls());
        assertEquals(1L, controller.submits());
        assertTrue(controller.executable());
        assertEquals("SYNTHETIC", controller.publisherWiring());
    }

    @Test
    void aRefusalToRunAndARefusalToRecordReadApart() {
        Fixture unwired = new Fixture();
        CommitController none = new CommitController(unwired.reservations, null);
        CommitController.SubmitResult neverRan = none.submit(unwired.request());
        assertFalse(neverRan.admitted());
        assertNull(neverRan.terminal(), "nothing ran, so there is no terminal to report");

        Fixture wired = new Fixture();
        CommitController ran = new CommitController(wired.reservations, ticket ->
            new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
                List.of("a=1", "b=2"), 2, "a-note-the-promise-never-covered-".repeat(20)));
        CommitController.SubmitResult notRecorded = ran.submit(wired.request());

        assertFalse(notRecorded.admitted());
        assertNotNull(notRecorded.terminal(),
            "the action ran and reported its terminal, and the line of it was not written");
        assertEquals(CommitTicket.Terminal.PUBLISHED, notRecorded.terminal());
        assertEquals("record-refused", notRecorded.diagnostic());
        assertEquals(1, wired.reservations.pendingCount(), "and the item waits for a disposition");
        assertEquals(1L, ran.supplierCalls(), "the action of this one was called");
    }
}
