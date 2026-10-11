/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The versioned comparison: what it refuses instead of answering, and the logical columns it reads
 * so that two runs which assigned other runtime numbers still compare equal. */
class ReplayComparatorTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";

    private static CommitLog.OrderSource order(long plan) {
        return (worldId, domainId, nodeKey) -> {
            if (!worldId.equals(WORLD) || !domainId.equals(DOMAIN)) {
                return null;
            }
            return switch (nodeKey) {
                case "a/0" -> new CommitLog.Resolved(plan, 0, false);
                case "a/1" -> new CommitLog.Resolved(plan, 1, false);
                case "a/2" -> new CommitLog.Resolved(plan, 2, false);
                default -> null;
            };
        };
    }

    /** One report as the store would carry it: the declared columns and the rows of the effect. */
    private static CommitLog.TypedBatch report(String key, long instanceId, long epoch, long chunk,
                                               String status, long authorization, long effectRef,
                                               long lifetime, String... rows) {
        CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instanceId, epoch,
            chunk, status, authorization, effectRef,
            new CommitNamespaces.Namespace("producer", lifetime, 0L, 11L),
            new CommitNamespaces.Namespace("controller", lifetime, 0L, 11L));
        return new CommitLog.TypedBatch(new CommitLog.Batch(WORLD, DOMAIN, key, CommitLog.Batch.Kind.APPLY,
            authorization, 11L, 2), identity,
            new EffectStore.Effect(effectRef, "chunk-materialize", "full", List.of(rows)));
    }

    /** One run: the reports of one tick, driven through the log and read back as its versioned
     * projection. Every side of every test is built this way, on the real storage chain. */
    private static CommitLog.SchemaReplay run(CommitLog.OrderSource source,
                                              CommitLog.TypedBatch... reports) {
        CommitLog log = new CommitLog(() -> 16, source, new EffectStore(1 << 20, 64));
        log.beginTick(1L);
        for (CommitLog.TypedBatch report : reports) {
            assertTrue(log.reachTyped(report).logged(), "the driver stores what it reports");
        }
        log.closeTick();
        assertEquals(0, log.lastTyped().effectMissing());
        return log.lastTyped();
    }

    @Test
    void theSameInputTwiceComparesEqual() {
        CommitLog.SchemaReplay first = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=full"));
        CommitLog.SchemaReplay second = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=full"));
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(first, second);

        assertTrue(verdict.equal());
        assertEquals(0, verdict.differences());
        assertEquals(1L, comparator.equal());
        assertEquals(0L, comparator.refused());
    }

    @Test
    void otherRuntimeNumbersAndAnotherNamespaceCompareEqual() {
        CommitLog.SchemaReplay first = run(order(4L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=full"));
        CommitLog.SchemaReplay second = run(order(9L), report("a/0", 7L, 21L, 64L, "full", 44L, 900L,
            5150L, "chunk=64", "state=full"));
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(first, second);

        assertTrue(verdict.equal(), "another plan number, instance number, epoch, grant and reference");
        assertEquals(0, verdict.differences());
        assertTrue(first.typedSteps().get(0).identity().producer().declared(),
            "the namespace is recorded even though it is not compared");
    }

    @Test
    void theComparedColumnsLeaveTheRuntimeNamespaceOut() {
        assertTrue(ReplayComparator.CANONICAL_FIELDS.containsAll(List.of("plan", "instance",
            "authorization", "epoch", "effect", "chunk", "status", "declared", "content",
            "contentDigest", "writeSet", "disposition")));
        for (String column : List.of("thread", "lifetime", "owner", "namespace", "resetEpoch")) {
            assertFalse(ReplayComparator.CANONICAL_FIELDS.contains(column), column);
        }
    }

    @Test
    void aRunWithNoDeclaredVersionIsRefusedInsteadOfReadAsLegacy() {
        CommitLog log = new CommitLog(() -> 16, order(1L), new EffectStore(1 << 20, 8));
        log.beginTick(1L);
        log.closeTick();
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(log.lastTyped(), log.lastTyped());

        assertTrue(verdict.refused());
        assertEquals(ReplayComparator.REFUSED_UNDECLARED_SCHEMA, verdict.reason());
        assertTrue(comparator.refused() > 0L);
    }

    @Test
    void twoDeclaredLegacySidesAreComparedOnTheirThreeFieldsAndSaySo() {
        CommitLog first = plain(5L);
        CommitLog second = plain(5L);
        CommitLog changed = plain(6L);
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict same = comparator.compare(comparator.legacyDeclared(first.last()),
            comparator.legacyDeclared(second.last()));
        ReplayComparator.Verdict other = comparator.compare(comparator.legacyDeclared(first.last()),
            comparator.legacyDeclared(changed.last()));

        assertTrue(same.equal());
        assertTrue(same.note().contains("version 0"), "the three-field reading says what it read");
        assertFalse(other.equal());
        assertEquals(1, other.differences());
        assertTrue(other.fields().contains("writeSet"));
    }

    private static CommitLog plain(long digest) {
        CommitLog log = new CommitLog(() -> 16, order(1L), null);
        log.beginTick(1L);
        log.reach(new CommitLog.Batch(WORLD, DOMAIN, "a/0", CommitLog.Batch.Kind.APPLY, 1L, digest, 1));
        log.closeTick();
        return log;
    }

    @Test
    void aLegacySideAgainstAVersionedOneIsRefused() {
        ReplayComparator comparator = new ReplayComparator();
        CommitLog.SchemaReplay typed = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64"));

        ReplayComparator.Verdict verdict = comparator.compare(comparator.legacyDeclared(plain(5L).last()),
            typed);

        assertTrue(verdict.refused());
        assertEquals(ReplayComparator.REFUSED_MIXED_PROTOCOL, verdict.reason());
        assertEquals(1L, comparator.refusals(ReplayComparator.REFUSED_MIXED_PROTOCOL));
    }

    @Test
    void aVersionThisBuildDoesNotKnowIsRefusedInsteadOfAnsweredEqual() {
        CommitLog.SchemaReplay known = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64"));
        CommitLog.SchemaReplay unknown = new CommitLog.SchemaReplay(7, List.of(),
            known.typedSteps(), 0);
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(unknown, unknown);

        assertTrue(verdict.refused());
        assertEquals(ReplayComparator.REFUSED_UNKNOWN_SCHEMA, verdict.reason());
        assertEquals(1L, comparator.refusals(ReplayComparator.REFUSED_UNKNOWN_SCHEMA));
    }

    @Test
    void aLineWhoseEffectIsNotThereIsRefused() {
        CommitLog.SchemaReplay known = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64"));
        CommitLog.TypedStep step = known.typedSteps().get(0);
        CommitLog.SchemaReplay dropped = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED, List.of(),
            List.of(new CommitLog.TypedStep(step.planSequence(), step.position(), step.writeSetDigest(),
                step.worldId(), step.domainId(), step.identity(), step.disposition(), null)), 1);
        CommitLog.Identity orphanIdentity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L,
            "full", 3L, CommitLog.NO_EFFECT, step.identity().producer(), step.identity().controller());
        CommitLog.SchemaReplay orphan = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED, List.of(),
            List.of(new CommitLog.TypedStep(step.planSequence(), step.position(), step.writeSetDigest(),
                step.worldId(), step.domainId(), orphanIdentity, step.disposition(), step.effect())), 0);
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict missing = comparator.compare(known, dropped);
        ReplayComparator.Verdict noReference = comparator.compare(known, orphan);

        assertTrue(missing.refused(), "a side as an import would carry it when the record did not survive");
        assertEquals(ReplayComparator.REFUSED_EFFECT_MISSING, missing.reason());
        assertTrue(noReference.refused());
        assertEquals(ReplayComparator.REFUSED_ORPHAN_EFFECT, noReference.reason());
    }

    @Test
    void twoLifecyclesWearingOneInstanceNumberAreRefused() {
        CommitLog.SchemaReplay known = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64"));
        CommitLog.TypedStep step = known.typedSteps().get(0);
        CommitLog.Identity other = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L, "full", 3L,
            100L, new CommitNamespaces.Namespace("producer", 999L, 1L, 12L), step.identity().controller());
        CommitLog.SchemaReplay collided = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED, List.of(),
            List.of(step, new CommitLog.TypedStep(step.planSequence(), step.position(),
                step.writeSetDigest(), step.worldId(), step.domainId(), other, step.disposition(),
                step.effect())), 0);

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(known, collided);

        assertTrue(verdict.refused());
        assertEquals(ReplayComparator.REFUSED_INSTANCE_COLLISION, verdict.reason());
    }

    @Test
    void oneGrantVersionOverTwoChunksIsNotOneProduct() {
        CommitLog.SchemaReplay first = run(order(1L),
            report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L, "chunk=64", "state=full"),
            report("a/1", 2L, 5L, 65L, "full", 3L, 101L, 4242L, "chunk=65", "state=full"));
        CommitLog.SchemaReplay second = run(order(1L),
            report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L, "chunk=64", "state=full"),
            report("a/1", 2L, 5L, 64L, "full", 3L, 101L, 4242L, "chunk=64", "state=full"));
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(first, second);

        assertFalse(verdict.equal(), "sharing the authorization version does not make two chunks one");
        assertTrue(verdict.fields().contains("chunk"));
        assertEquals(1, comparator.different());
    }

    @Test
    void aRebuiltInstanceIsCaughtByTheStepsItAdded() {
        CommitLog.SchemaReplay first = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=full"));
        CommitLog.SchemaReplay rebuilt = run(order(1L),
            report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L, "chunk=64", "state=full"),
            report("a/1", 2L, 5L, 64L, "full", 3L, 101L, 4242L, "chunk=64", "state=full"));

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(first, rebuilt);

        assertFalse(verdict.equal(), "the rebuilt chunk is a second instance at the same place");
        assertTrue(verdict.differences() >= 1);
    }

    @Test
    void oneInstanceNameWithAnotherContentIsCaught() {
        CommitLog.SchemaReplay first = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=full"));
        CommitLog.SchemaReplay other = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=partial"));

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(first, other);

        assertFalse(verdict.equal(), "the name is the same and the content is not");
        assertTrue(verdict.fields().contains("content"));
        assertTrue(verdict.fields().contains("contentDigest"));
    }

    @Test
    void theDeclaredFieldSetIsComparedApartFromTheContent() {
        CommitLog.SchemaReplay first = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=full"));
        CommitLog.SchemaReplay other = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "phase=full"));

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(first, other);

        assertFalse(verdict.equal());
        assertTrue(verdict.fields().contains("declared"));
        assertTrue(verdict.fields().contains("content"));
    }

    @Test
    void anotherTargetStatusIsCaught() {
        CommitLog.SchemaReplay first = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64"));
        CommitLog.SchemaReplay other = run(order(1L), report("a/0", 1L, 5L, 64L, "empty", 3L, 100L, 4242L,
            "chunk=64"));

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(first, other);

        assertFalse(verdict.equal());
        assertTrue(verdict.fields().contains("status"));
    }

    @Test
    void aMissingSideIsRefusedAndTheCountersSayWhatHappened() {
        ReplayComparator comparator = new ReplayComparator();
        CommitLog.SchemaReplay known = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64"));
        CommitLog.SchemaReplay changed = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=other"));

        ReplayComparator.Verdict missing = comparator.compare(known, null);
        ReplayComparator.Verdict other = comparator.compare(known, changed);

        assertTrue(missing.refused());
        assertEquals(ReplayComparator.REFUSED_SIDE_MISSING, missing.reason());
        assertTrue(other.differences() >= 1);
        assertEquals(2L, comparator.compared());
        assertEquals(1L, comparator.refused());
        assertEquals(1L, comparator.different());
        assertEquals(1, comparator.refusalReasons().size());
        assertNotNull(comparator.refusalReasons().get(ReplayComparator.REFUSED_SIDE_MISSING));
    }
}
