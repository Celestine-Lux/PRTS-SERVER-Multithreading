/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The witness core: the seat a line is judged at, the artefact its declaration carries, the
 * agreement of the three namespaces, and the canonical comparison key two runs are read by. The
 * cases that must be refused are here beside the cases that must be admitted, because a policy that
 * refuses everything would pass every negative and prove nothing. */
class IdentityWitnessTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "chunk-pipeline";
    private static final String NODE = "chunk-pipeline/world-a/mailbox-1";
    private static final String OTHER_NODE = "chunk-pipeline/world-a/mailbox-2";
    private static final long EPOCH = 5L;
    private static final long GRANT = 7L;
    private static final long EFFECT = 100L;

    /** A holder that answers with one sealed table and the generations it was sealed under, both of
     * which a test may move to drive the drift check. */
    private static final class Holder implements IdentityWitness.Source {

        private final FrozenDeclarations sealed;
        private final CommitLog.OrderSource plans;
        long worldEpoch = EPOCH;
        long authorization = GRANT;
        CommitNamespaces.Namespace current;

        Holder(FrozenDeclarations sealed, CommitLog.OrderSource plans) {
            this.sealed = sealed;
            this.plans = plans;
        }

        @Override
        public FrozenDeclarations declarations(String worldId, String domainId, long planSequence) {
            return sealed.planSequence() == planSequence ? sealed : FrozenDeclarations.none();
        }

        @Override
        public long liveWorldEpoch(String worldId) {
            return worldEpoch;
        }

        @Override
        public long liveAuthorization(String worldId, String domainId, String nodeKey) {
            return authorization;
        }

        @Override
        public CommitNamespaces.Namespace currentController() {
            return current;
        }
    }

    private static CommitLog.OrderSource plans(long plan, int position) {
        return (worldId, domainId, nodeKey) -> NODE.equals(nodeKey) || OTHER_NODE.equals(nodeKey)
            ? new CommitLog.Resolved(plan, NODE.equals(nodeKey) ? position : position + 1, false)
            : null;
    }

    /** One declared artefact: the node, the chunk that packs to this value and the state. */
    private static FrozenDeclarations.Candidate candidate(String node, long packedChunk, String status) {
        return new FrozenDeclarations.Candidate(WORLD, DOMAIN, node,
            new FrozenDeclarations.DeclaredChunk(packedChunk), status);
    }

    private static FrozenDeclarations sealed(long plan, String node, int position, long packedChunk,
                                             String status) {
        FrozenDeclarations.Sealed result = FrozenDeclarations.seal(plan,
            List.of(candidate(node, packedChunk, status)),
            List.of(new FrozenDeclarations.Assignment(WORLD, DOMAIN, node, position)));
        assertTrue(result.sealed(), result.refusal());
        return result.declarations();
    }

    private static CommitLog.Identity identity(long instance, long chunk, String status,
                                               CommitNamespaces.Namespace controller) {
        return new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instance, EPOCH, chunk, status, GRANT,
            EFFECT, new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L), controller);
    }

    private static CommitNamespaces.Namespace controller() {
        return new CommitNamespaces.Namespace("owner-a", 5150L, 0L, CommitNamespaces.NO_THREAD);
    }

    private static IdentityWitness.Verdict verify(FrozenDeclarations table, long plan, int position,
                                                  String node, CommitLog.Identity identity,
                                                  CommitNamespaces.Namespace current) {
        return IdentityWitness.verify(table, new CommitLog.Resolved(plan, position, false), WORLD,
            DOMAIN, node, identity, current);
    }

    @Test
    void theFinalSeatOfTheGraphIsWhatBindsNotTheOrderOfTheDeclarations() {
        List<FrozenDeclarations.Candidate> declared = List.of(candidate(NODE, 64L, "full"),
            candidate(OTHER_NODE, 65L, "full"));
        List<FrozenDeclarations.Assignment> graph = List.of(
            new FrozenDeclarations.Assignment(WORLD, DOMAIN, OTHER_NODE, 0),
            new FrozenDeclarations.Assignment(WORLD, DOMAIN, NODE, 1));
        FrozenDeclarations.Sealed result = FrozenDeclarations.seal(9L, declared, graph);

        assertTrue(result.sealed(), result.refusal());
        FrozenDeclarations table = result.declarations();
        assertEquals(64L, table
            .entryAt(new FrozenDeclarations.Seat(WORLD, DOMAIN, 9L, 1)).packedChunkPos(),
            "the node declared first took the seat the graph gave it, not the one it arrived with");
        assertEquals(OTHER_NODE,
            table.entryAt(new FrozenDeclarations.Seat(WORLD, DOMAIN, 9L, 0)).nodeKey());
        assertEquals(2, table.size());
    }

    @Test
    void aTableOfAnotherFreezeAndTheSeatOfAnotherPlanAreBothRefused() {
        FrozenDeclarations table = sealed(9L, NODE, 1, 64L, "full");
        CommitLog.Identity worn = identity(1L, 64L, "full", controller());

        assertEquals("declaration-plan-mismatch",
            verify(table, 10L, 1, NODE, worn, controller()).reason());
        assertEquals("declaration-missing",
            verify(FrozenDeclarations.none(), 9L, 1, NODE, worn, controller()).reason());
        assertEquals("declaration-missing",
            verify(table, 9L, 2, NODE, worn, controller()).reason());
    }

    @Test
    void theNextPlanOfANodeDoesNotReadTheTableOfTheLastOne() {
        FrozenDeclarations older = sealed(9L, NODE, 1, 64L, "full");
        FrozenDeclarations newer = sealed(10L, NODE, 1, 64L, "full");
        Holder holder = new Holder(newer, plans(10L, 1));
        holder.current = controller();
        CommitLog.Identity worn = identity(1L, 64L, "full", controller());

        assertTrue(verify(holder.declarations(WORLD, DOMAIN, 10L), 10L, 1, NODE, worn,
            controller()).accepted());
        assertEquals("declaration-missing",
            verify(holder.declarations(WORLD, DOMAIN, 9L), 9L, 1, NODE, worn, controller()).reason(),
            "the holder answers for the freeze it published and for no other");
        assertNotNull(older.entryAt(new FrozenDeclarations.Seat(WORLD, DOMAIN, 9L, 1)));
    }

    @Test
    void aRefusedSealWritesNoTableAndNothingMayBeAuthorizedThroughIt() {
        FrozenDeclarations.Sealed duplicate = FrozenDeclarations.seal(9L,
            List.of(candidate(NODE, 64L, "full"), candidate(NODE, 65L, "full")),
            List.of(new FrozenDeclarations.Assignment(WORLD, DOMAIN, NODE, 0)));

        assertFalse(duplicate.sealed(), "one node of one pair is declared once in one freeze");
        assertEquals("declaration-duplicate", duplicate.refusal());
        assertNull(duplicate.declarations());
        assertEquals("declaration-missing", verify(FrozenDeclarations.none(), 9L, 0, NODE,
            identity(1L, 64L, "full", controller()), controller()).reason());
    }

    @Test
    void aDeclarationWithoutItsArtefactTakesNoEntry() {
        FrozenDeclarations.Sealed result = FrozenDeclarations.seal(9L,
            List.of(new FrozenDeclarations.Candidate(WORLD, DOMAIN, NODE, null, "")),
            List.of(new FrozenDeclarations.Assignment(WORLD, DOMAIN, NODE, 0)));

        assertTrue(result.sealed());
        assertEquals(FrozenDeclarations.NOT_WIRED, result.declarations().state());
        assertEquals(0, result.declarations().size());
        assertEquals("declaration-missing", verify(result.declarations(), 9L, 0, NODE,
            identity(1L, 64L, "full", controller()), controller()).reason());
    }

    @Test
    void theSameLogicalDeclarationComparesEqualWhateverTheRunCounted() {
        FrozenDeclarations first = sealed(4L, NODE, 3, 64L, "full");
        FrozenDeclarations second = sealed(91L, NODE, 17, 64L, "full");
        IdentityWitness.Verdict left = verify(first, 4L, 3, NODE, identity(1L, 64L, "full", controller()),
            controller());
        IdentityWitness.Verdict right = verify(second, 91L, 17, NODE,
            identity(8848L, 64L, "full", controller()), controller());

        assertTrue(left.accepted());
        assertTrue(right.accepted());
        assertEquals(left.instance(), right.instance(),
            "the plan counter, the seat, the instance number and the lifetime are not part of it");
    }

    @Test
    void anotherLogicalDeclarationIsNeverAnsweredEqualOnAFirstAppearance() {
        FrozenDeclarations table = sealed(9L, NODE, 0, 64L, "full");
        FrozenDeclarations other = sealed(9L, NODE, 0, 65L, "full");
        String left = verify(table, 9L, 0, NODE, identity(1L, 64L, "full", controller()),
            controller()).instance();
        String right = verify(other, 9L, 0, NODE, identity(1L, 65L, "full", controller()),
            controller()).instance();

        assertNotEquals(left, right, "the first appearance of either side would have been one");
        assertNotEquals(left, verify(sealed(9L, NODE, 0, 64L, "partial"), 9L, 0, NODE,
            identity(1L, 64L, "partial", controller()), controller()).instance(), "another state");
        assertNotEquals(left, verify(sealed(9L, OTHER_NODE, 0, 64L, "full"), 9L, 0, OTHER_NODE,
            identity(1L, 64L, "full", controller()), controller()).instance(), "another node");
    }

    @Test
    void aLineWearingAnIdentityItsDeclarationDoesNotCarryIsRefused() {
        FrozenDeclarations table = sealed(9L, NODE, 1, 64L, "full");
        CommitLog.Identity worn = identity(1L, 64L, "full", controller());

        assertEquals("chunk-witness-mismatch",
            verify(table, 9L, 1, NODE, identity(1L, 999L, "full", controller()), controller()).reason());
        assertEquals("declaration-status-mismatch",
            verify(table, 9L, 1, NODE, identity(1L, 64L, "empty", controller()), controller()).reason());
        assertEquals("declaration-seat-mismatch",
            verify(table, 9L, 1, OTHER_NODE, worn, controller()).reason());
        assertEquals("identity-undeclared", verify(table, 9L, 1, NODE,
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, EPOCH, 64L, "full", GRANT, EFFECT,
                new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L),
                CommitNamespaces.Namespace.NONE), controller()).reason());
        assertEquals("controller-namespace-mismatch", verify(table, 9L, 1, NODE,
            identity(1L, 64L, "full", new CommitNamespaces.Namespace("owner-b", 5150L, 0L, 0L)),
            controller()).reason());
    }

    @Test
    void theOriginAndTheNegativeCornersAreLegalChunkWitnesses() {
        FrozenDeclarations origin = sealed(9L, NODE, 0, 0L, "full");
        FrozenDeclarations corner = sealed(9L, NODE, 0, -1099511627776L, "full");

        assertTrue(verify(origin, 9L, 0, NODE, identity(1L, 0L, "full", controller()), controller())
            .accepted(), "the chunk at the origin packs to the same value a missing one would");
        assertTrue(verify(corner, 9L, 0, NODE, identity(1L, -1099511627776L, "full", controller()),
            controller()).accepted(), "a negative corner packs to a negative value and is legal");
        assertEquals("declaration-missing", verify(FrozenDeclarations.of(9L, List.of()), 9L, 0, NODE,
            identity(1L, 0L, "full", controller()), controller()).reason(),
            "a table with no entry is not a table that declared the origin");
    }

    @Test
    void aNumberThatHappensToMatchIsNeitherAProofNorAnError() {
        FrozenDeclarations table = sealed(9L, NODE, 0, 64L, "full");
        IdentityWitness.Verdict first = verify(table, 9L, 0, NODE, identity(1L, 64L, "full", controller()),
            controller());
        IdentityWitness.Verdict second = verify(table, 9L, 0, NODE,
            identity(1L, 64L, "full", controller()), controller());

        assertTrue(first.accepted());
        assertTrue(second.accepted(), "two lines may carry the same number and both be backed");
        assertEquals(first.instance(), second.instance());
    }

    @Test
    void theCanonicalFormRoundTripsAndNoSpliceCanBeReadTwoWays() {
        IdentityWitness.LogicalKey key = new IdentityWitness.LogicalKey("a/b", "c", "d;e", 3, -7L,
            "f/g");
        IdentityWitness.LogicalKey back = IdentityWitness.parse(key.canonical());

        assertEquals(key, back);
        assertNotEquals(new IdentityWitness.LogicalKey("a", "b/c", "d;e", 3, -7L, "f/g").canonical(),
            key.canonical(), "an unescaped splice would read these two as one");
        assertNotEquals(new IdentityWitness.LogicalKey("a/b", "c", "d;e", 3, -7L, "f").canonical(),
            key.canonical());
        assertNull(IdentityWitness.parse(key.canonical() + "x"));
        assertNull(IdentityWitness.parse("I1"));
        assertNull(IdentityWitness.parse(null));
    }

    @Test
    void noCanonicalFormOfTwoDifferentKeysCollides() {
        List<String> values = List.of("", "a", "a/b", "0", "-7", "#", ":", "ab");
        List<String> forms = new ArrayList<>();
        for (String world : values) {
            for (String domain : values) {
                for (String status : values) {
                    for (long chunk : new long[] {0L, 1L, -1L}) {
                        forms.add(new IdentityWitness.LogicalKey(world, domain, "n", 1, chunk, status)
                            .canonical());
                    }
                }
            }
        }
        assertEquals(forms.size(), List.copyOf(new java.util.LinkedHashSet<>(forms)).size(),
            "every distinct key carries a distinct form and every form parses back to its key");
        for (String form : forms) {
            assertNotNull(IdentityWitness.parse(form), form);
        }
    }

    @Test
    void theReservationRefusesBeforeARingSlotOrARoomIsTakenAndCallsNoAction() {
        AtomicLong calls = new AtomicLong();
        EffectStore store = new EffectStore(65536, 16);
        CommitLog log = new CommitLog(() -> 4, plans(9L, 0), store);
        CommitReservation reservations = new CommitReservation(log, store, plans(9L, 0), "owner-a",
            new CommitNamespaces.ResetEpochs());
        CommitController controller = new CommitController(reservations, line -> {
            calls.incrementAndGet();
            return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
                List.of("a=1", "b=2"), 2, "");
        });
        log.beginTick(1L);

        CommitController.SubmitResult refused = controller.submit(request(reservations, 1L));

        assertFalse(refused.admitted());
        assertEquals("declaration-missing", refused.diagnostic(), "the freeze backs no such line");
        assertEquals(0L, calls.get(), "no publish action is reached");
        assertEquals(0, log.ringDepth(), "the ring holds nothing");
        assertEquals(0, log.reservedSlots(), "no slot was handed out");
        assertEquals(0, store.capacitySlots(), "no room was taken");
        assertEquals(0, reservations.outstanding(), "the reservation was never taken");
        assertEquals(0L, reservations.reserveSuccess());
        assertEquals(1L, reservations.witnessRefusals());
    }

    @Test
    void aLineThatMovesAfterItsReservationIsNotStartedAndIsGivenBackUnstarted() {
        EffectStore store = new EffectStore(65536, 16);
        Holder holder = new Holder(sealed(9L, NODE, 0, 64L, "full"), plans(9L, 0));
        CommitLog log = new CommitLog(() -> 4, plans(9L, 0), store, holder);
        CommitReservation reservations = new CommitReservation(log, store, plans(9L, 0), "owner-a",
            new CommitNamespaces.ResetEpochs());
        holder.current = reservations.controller();

        CommitTicket moved = reservations.reserve(request(reservations, 1L)).ticket();
        assertNotNull(moved, "the freeze backs this line while its generations still hold");

        holder.worldEpoch = EPOCH + 1L;
        assertFalse(reservations.beginPublish(moved), "the world generation moved on");
        assertEquals(1L, reservations.driftRefusals());

        holder.worldEpoch = EPOCH;
        holder.authorization = GRANT + 1L;
        assertFalse(reservations.beginPublish(moved), "the grant was retired");
        assertEquals(2L, reservations.driftRefusals());

        assertEquals("released",
            reservations.release(moved, CommitTicket.ReleaseReason.CANCELLED).diagnostic(),
            "a line that never started is given back as an unstarted ticket");
        assertEquals(0, log.ringDepth(), "and it wrote no line");
        assertEquals(0, store.capacitySlots());

        holder.authorization = GRANT;
        CommitTicket still = reservations.reserve(request(reservations, 2L)).ticket();
        assertNotNull(still);
        assertTrue(reservations.beginPublish(still), "with nothing moved the line may run");
    }

    @Test
    void theThreeNamespacesHaveToAgreeAndTheHoldersAnswerIsTheOneThatCounts() {
        CommitNamespaces.Namespace holder = controller();
        FrozenDeclarations table = sealed(9L, NODE, 0, 64L, "full");
        CommitLog.Identity worn = identity(1L, 64L, "full", holder);

        assertTrue(verify(table, 9L, 0, NODE, worn, holder).accepted());
        assertEquals("controller-namespace-mismatch",
            verify(table, 9L, 0, NODE, worn, new CommitNamespaces.Namespace("owner-a", 5151L, 0L, 0L))
                .reason(), "the holder judges by the lifecycle it holds now");
    }

    @Test
    void aStrictReplayNeedsItsClosureAndRefusesWithoutOne() {
        CommitLog.SchemaReplay side = typedSide();
        ReplayComparator comparator = new ReplayComparator();
        FrozenDeclarations table = sealed(1L, NODE, 0, 64L, "full");
        ReplayComparator.Evidence good = new ReplayComparator.Evidence("85bcdea", 
            ReplayComparator.recordsDigest(side), table, side);

        assertTrue(comparator.compare(good, good).equal(), "one run against itself is equal");
        assertTrue(comparator.compare(new ReplayComparator.Evidence("85bcdea",
                ReplayComparator.recordsDigest(side), table, side),
            new ReplayComparator.Evidence("85bcdea", ReplayComparator.recordsDigest(side), table, side))
            .equal());
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, comparator.compare(
            new ReplayComparator.Evidence("85bcdea", ReplayComparator.recordsDigest(side), null, side),
            good).reason(), "no table, no strict conclusion");
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, comparator.compare(
            new ReplayComparator.Evidence("", ReplayComparator.recordsDigest(side), table, side),
            good).reason(), "a closure without the commit it came from is refused");
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, comparator.compare(
            new ReplayComparator.Evidence("85bcdea", "deadbee", table, side),
            good).reason(), "a summary that is not this side's own is another run's closure");
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, comparator.compare(
            new ReplayComparator.Evidence("85bcdea", ReplayComparator.recordsDigest(side), sealed(2L,
                NODE, 0, 64L, "full"), side), good).reason(),
            "a table of another freeze is not the freeze of these records");
    }

    @Test
    void theHistoricalReadingStillParsesAndSaysItsProvenanceIsNotVerified() {
        CommitLog.SchemaReplay side = typedSide();
        ReplayComparator comparator = new ReplayComparator();
        ReplayComparator.Verdict typed = comparator.compare(side, side);

        assertTrue(typed.equal(), "a version 1 side still reads under its own declared version");
        assertTrue(typed.note().contains("provenance"),
            "and says the instance column of that reading is not backed by a seal");
        CommitLog legacy = new CommitLog(() -> 8, (worldId, domainId, nodeKey) ->
            new CommitLog.Resolved(1L, 0, false), null);
        legacy.beginTick(1L);
        legacy.reach(new CommitLog.Batch(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY, GRANT, 11L,
            1));
        legacy.closeTick();
        ReplayComparator.Verdict plain = comparator.compare(comparator.legacyDeclared(legacy.last()),
            comparator.legacyDeclared(legacy.last()));
        assertTrue(plain.equal());
        assertTrue(plain.note().contains("version 0"), "version 0 stays readable and unchanged");
    }

    @Test
    void aPolicyThatRefusesEverythingCannotPassThisGate() {
        FrozenDeclarations table = sealed(9L, NODE, 0, 64L, "full");

        assertTrue(verify(table, 9L, 0, NODE, identity(1L, 64L, "full", controller()), controller())
            .accepted(), "a legal line is admitted, so refusing all of them is not a policy");
        assertEquals(ReplayComparator.Decision.EQUAL, new ReplayComparator().compare(typedSide(),
            typedSide()).decision(), "and a legal pair is still answered");
    }

    private static CommitTicket.ReserveRequest request(CommitReservation reservations, long instance) {
        CommitNamespaces.Namespace held = reservations.controller();
        CommitLog.Identity identity = identity(instance, 64L, "full", held);
        EffectStore.Effect variant = new EffectStore.Effect(EFFECT, "publish", "PUBLISHED",
            List.of("a=1", "b=2"));
        return new CommitTicket.ReserveRequest(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY, 9L, 0,
            GRANT, 4242L, 2, identity, held, variant, variant, variant);
    }

    /** One run of the versioned path on the real storage chain, sealed by the table of its freeze. */
    private static CommitLog.SchemaReplay typedSide() {
        CommitLog.OrderSource plans = plans(1L, 0);
        FrozenDeclarations table = sealed(1L, NODE, 0, 64L, "full");
        CommitLog log = new CommitLog(() -> 8, plans, new EffectStore(1 << 16, 16),
            new WitnessSource(plans, WORLD, DOMAIN, 64L, "full", EPOCH, GRANT, NODE));
        log.beginTick(1L);
        CommitLog.Identity identity = identity(1L, 64L, "full", controller());
        assertTrue(log.reachTyped(new CommitLog.TypedBatch(
            new CommitLog.Batch(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY, GRANT, 4242L, 2),
            identity, new EffectStore.Effect(EFFECT, "publish", "PUBLISHED", List.of("a=1", "b=2"))))
            .logged());
        log.closeTick();
        assertNotNull(table);
        return log.lastTyped();
    }
}
