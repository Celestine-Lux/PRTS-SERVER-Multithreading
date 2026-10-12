/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order of a pair while a reservation of it is in flight. A plain line, a versioned line and the
 * terminal of a ticket are judged against the same memory, and a line that is refused leaves that
 * memory, the ring and the counters of the pair exactly where the refused line found them. */
class CommitLogOrderGuardTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";

    /** A plan whose node name carries the place it ordered: "7/2" is sequence 7, position 2. */
    private static final class Places implements CommitLog.OrderSource {

        @Override
        public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
            if (!WORLD.equals(worldId) || !DOMAIN.equals(domainId) || nodeKey == null) {
                return null;
            }
            int slash = nodeKey.indexOf('/');
            if (slash < 1 || slash == nodeKey.length() - 1) {
                return null;
            }
            return new CommitLog.Resolved(Long.parseLong(nodeKey.substring(0, slash)),
                Integer.parseInt(nodeKey.substring(slash + 1)), false);
        }
    }

    /** The holder stand-in of this file: it declares every place the order source of a test
     * answers for, at the artefact the identities of this file carry. */
    private static WitnessSource declared(CommitLog.OrderSource plans) {
        return new WitnessSource(plans, WORLD, DOMAIN, 3L, "published", 5L, 7L, "7/0", "7/1",
            "7/2", "7/3", "7/4", "7/5", "6/9");
    }

    private static CommitLog.Batch batch(String nodeKey) {
        return new CommitLog.Batch(WORLD, DOMAIN, nodeKey, CommitLog.Batch.Kind.APPLY, 7L, 4242L, 2);
    }

    private static CommitLog.Identity identity(long instance, long effectRef,
                                               CommitNamespaces.Namespace controller) {
        return new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instance, 5L, 3L, "published", 7L,
            effectRef, new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L), controller);
    }

    private static CommitLog.TypedBatch typed(String nodeKey, long effectRef, long instance) {
        return new CommitLog.TypedBatch(batch(nodeKey),
            identity(instance, effectRef, new CommitNamespaces.Namespace("controller", 5150L, 0L, 91L)),
            new EffectStore.Effect(effectRef, "publish", "PUBLISHED", List.of("a=1", "b=2")));
    }

    private static CommitTicket.ReserveRequest request(CommitReservation reservations, String nodeKey,
                                                       long plan, int position, long effectRef,
                                                       long instance) {
        CommitNamespaces.Namespace controller = reservations.controller();
        List<String> rows = List.of("a=1", "b=2");
        EffectStore.Effect variant = new EffectStore.Effect(effectRef, "publish", "PUBLISHED", rows);
        return new CommitTicket.ReserveRequest(WORLD, DOMAIN, nodeKey, CommitLog.Batch.Kind.APPLY,
            plan, position, plan, 4242L, rows.size(), identity(instance, effectRef, controller),
            controller, variant, variant, variant);
    }

    private static CommitTicket.Outcome outcome() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
            List.of("a=1", "b=2"), 2, "");
    }

    /** What a caller may read of the log and of the ring of its pair, as one value: a refusal must
     * leave it where it was. The counters of the refusal itself are not part of it. */
    private static String state(CommitLog log) {
        return log.accepted() + "/" + log.merged() + "/" + log.intents() + "/" + log.retried() + "/"
            + log.ringDepth() + "/" + log.watermarks() + "/" + log.heldPlans() + "/"
            + log.reservedSlots() + "/" + log.reservationsTaken() + "/" + log.reservationsConsumed();
    }

    @Test
    void aTerminalOfALaterPlaceWaitsForTheEarlierOne() {
        EffectStore store = new EffectStore(65536, 16);
        Places plans = new Places();
        CommitLog log = new CommitLog(() -> 4, plans, store, declared(plans));
        CommitReservation reservations = new CommitReservation(log, store, plans, "owner-a",
            new CommitNamespaces.ResetEpochs());
        // The later place is taken and started first; only then does an earlier place of the pair
        // arrive, which is what a plan built around the first reservation looks like.
        CommitTicket second = reservations.reserve(request(reservations, "7/1", 7L, 1, 101L, 42L)).ticket();
        assertNotNull(second);
        log.beginTick(1L);
        assertTrue(reservations.beginPublish(second), "it is the earliest place the pair holds so far");
        CommitTicket first = reservations.reserve(request(reservations, "7/0", 7L, 0, 100L, 41L)).ticket();
        assertNotNull(first);
        assertTrue(reservations.beginPublish(first), "and now the earlier place may run too");

        CommitReservation.FinalizeResult reversed = reservations.finalize(second, outcome());

        assertFalse(reversed.finalized(), "the later place does not end before the earlier one");
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, reversed.code());
        assertEquals("record-refused", reversed.diagnostic());
        assertEquals(0, log.ringDepth(), "no line was written for it");
        assertEquals(2, reservations.outstanding(), "the ticket stays where it was");
        assertEquals(1L, log.ticketRefused());
        assertEquals(1L, log.orderViolations());

        assertTrue(reservations.finalize(first, outcome()).finalized(), "the earlier place ends");
        assertTrue(reservations.finalize(second, outcome()).finalized(), "and then the later one does");
        CommitLog.Replay replay = log.closeTick();
        assertEquals(List.of(new CommitLog.Entry.Step(7L, 0, 4242L),
            new CommitLog.Entry.Step(7L, 1, 4242L)), replay.steps(),
            "the ring carries the order the plan gave, never the order the terminals arrived in");
        assertEquals(2, replay.loggedSteps());
        assertEquals(1L, replay.orderViolations(), "the reversal is counted, not quietly accepted");
        assertFalse(replay.orderMatches());
        assertEquals(0, reservations.outstanding());
        assertEquals(0, reservations.pendingCount(), "both items ended");
        assertTrue(reservations.conservationHolds());
    }

    @Test
    void aRefusedLineLeavesTheOrderOfItsPairWhereItWas() {
        EffectStore store = new EffectStore(4096, 8);
        Places plans = new Places();
        CommitLog log = new CommitLog(() -> 4, plans, store, declared(plans));
        log.beginTick(1L);
        assertNotNull(log.reserveSlot(WORLD, DOMAIN, 7L, 3), "a reservation holds place 3 of the pair");
        assertTrue(log.reach(batch("7/0")).logged(), "the place before it is free to the plain path");

        long sequence = log.sequence();
        int depth = log.ringDepth();
        int watermarks = log.watermarks();
        CommitLog.Verdict refused = log.reach(batch("7/3"));

        assertFalse(refused.logged());
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, refused.code());
        assertEquals(1L, log.hybridOrderRefusals());
        assertEquals(depth, log.ringDepth(), "the refused line took no slot");
        assertEquals(watermarks, log.watermarks());
        assertEquals(1L, log.accepted(), "and it did not count as a line that was taken");
        assertEquals(1, log.heldPlans());
        assertEquals(sequence + 1L, log.sequence(), "a plain refusal still takes its report number");

        CommitLog.Verdict between = log.reach(batch("7/1"));
        assertTrue(between.logged(), "the pair is where the refused line found it");
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, between.disposition(),
            "the line is taken, not only logged as one that contradicted the order");
        assertNull(between.code());
        assertTrue(log.reach(batch("7/2")).logged());
        assertEquals(3, log.ringDepth());
        assertFalse(log.reach(batch("7/3")).logged(), "the place the reservation holds stays closed");
        assertEquals(2L, log.hybridOrderRefusals());
    }

    @Test
    void aRefusedLineLeavesTheRingAndTheStateWhereTheyWere() {
        EffectStore store = new EffectStore(4096, 8);
        Places plans = new Places();
        CommitLog log = new CommitLog(() -> 2, plans, store, declared(plans));
        log.beginTick(1L);
        CommitLog.RingSlot held = log.reserveSlot(WORLD, DOMAIN, 7L, 5);
        assertTrue(log.reach(batch("7/0")).logged(), "the remaining slot is used by a line");

        String before = state(log);
        CommitLog.Verdict refused = log.reach(batch("7/2"));

        assertFalse(refused.logged(), "the ring has no room left for it");
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, refused.code());
        assertEquals(1L, log.refusedFull());
        assertEquals(before, state(log), "a refused line leaves the state of the log where it was");

        assertTrue(log.releaseSlot(held));
        CommitLog.Verdict after = log.reach(batch("7/1"));
        assertTrue(after.logged(), "the pair is where the refused line found it");
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, after.disposition());
        assertNull(after.code());
    }

    @Test
    void aVersionedLineDoesNotWritePastThePlaceAReservationHolds() {
        EffectStore store = new EffectStore(4096, 8);
        Places plans = new Places();
        CommitLog log = new CommitLog(() -> 4, plans, store,
            new WitnessSource(plans, WORLD, DOMAIN, 3L, "published", 5L, 7L, "7/0", "7/1", "7/2", "7/3",
                "7/4", "7/5", "6/9"));
        log.beginTick(1L);
        assertNotNull(log.reserveSlot(WORLD, DOMAIN, 7L, 0), "a reservation holds place 0 of the pair");

        CommitLog.Verdict blocked = log.reachTyped(typed("7/1", 100L, 42L));

        assertFalse(blocked.logged(), "the place the reservation holds is not written past");
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, blocked.code());
        assertEquals(1L, log.orderViolations());
        assertEquals(0, log.ringDepth(), "no line was written");
        assertEquals(0, store.records(), "and no record was taken for it");
        assertEquals(0, store.capacityReservedBytes(), "the store was not asked for room either");
        assertEquals(1, log.reservedSlots(), "the slot of the reservation is where it was");
        assertEquals(1L, log.typedRefused());

        assertTrue(log.reachTyped(typed("6/9", 101L, 43L)).logged(),
            "a place before the reservation is still free to the versioned path");
        assertEquals(1, log.ringDepth());
    }

    @Test
    void aPlaceThePairHasPassedIsNotReserved() {
        EffectStore store = new EffectStore(4096, 8);
        Places plans = new Places();
        CommitLog log = new CommitLog(() -> 4, plans, store, declared(plans));
        CommitReservation reservations = new CommitReservation(log, store, plans, "owner-a",
            new CommitNamespaces.ResetEpochs());
        log.beginTick(1L);
        assertTrue(log.reach(batch("7/2")).logged());

        assertFalse(log.placeAscending(WORLD, DOMAIN, 7L, 1), "the pair has passed this place");
        assertTrue(log.placeAscending(WORLD, DOMAIN, 7L, 3));
        assertNull(log.reserveSlot(WORLD, DOMAIN, 7L, 1), "so no slot is handed out for it");
        assertEquals(1L, log.reservationRefusals());
        assertEquals(0, log.reservedSlots());

        CommitReservation.ReserveResult passed =
            reservations.reserve(request(reservations, "7/1", 7L, 1, 100L, 42L));

        assertFalse(passed.admitted(), "a reservation for a place that is gone could only be refused");
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, passed.code());
        assertEquals("place-passed", passed.diagnostic());
        assertEquals(0, log.reservedSlots(), "the refusal took no slot");
        assertEquals(0, store.capacitySlots(), "and no room");
        assertNotNull(log.reserveSlot(WORLD, DOMAIN, 7L, 3), "a place after the line is still free");
    }

    @Test
    void aTicketWhosePlaceIsNotHeldAsAPlanEndsNothing() {
        EffectStore store = new EffectStore(4096, 8);
        Places plans = new Places();
        CommitLog log = new CommitLog(() -> 4, plans, store, declared(plans));
        log.beginTick(1L);
        CommitLog.RingSlot slot = log.reserveSlot(WORLD, DOMAIN);
        EffectStore.CapacitySlot room = store.reserveCapacity(100L, 2, 512).slot();
        CommitTicket ticket = new CommitTicket(1L, log.logLifetime(), batch("7/0"),
            identity(42L, 100L, new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L)),
            7L, 0, slot, room, new CommitNamespaces.Namespace("controller", log.logLifetime(), 0L, -1L));

        CommitLog.Verdict verdict = log.recordTicket(ticket, outcome());

        assertFalse(verdict.logged(), "a ticket is written with the place the plan froze for it");
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, verdict.code());
        assertEquals(0, log.ringDepth());
        assertEquals(0, store.records(), "no record was written for it");
        assertEquals(1, store.capacitySlots(), "and the room it holds is still held");
        assertTrue(store.capacityReservedBytes() > 0);
    }
}
