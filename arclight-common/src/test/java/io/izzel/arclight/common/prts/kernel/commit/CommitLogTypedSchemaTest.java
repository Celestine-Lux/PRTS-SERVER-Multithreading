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

/** The versioned columns of a log line, the room a versioned report takes before it is stored, and
 * the state the running server is in: no effect store is bound there, so the versioned path answers
 * a refusal instead of writing a line whose effect nothing could resolve. */
class CommitLogTypedSchemaTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final long LIFETIME = 4242L;
    private static final long OTHER_LIFETIME = 5150L;

    private static CommitLog.OrderSource order(long plan) {
        return (worldId, domainId, nodeKey) -> {
            if (!worldId.equals(WORLD) || !domainId.equals(DOMAIN)) {
                return null;
            }
            return switch (nodeKey) {
                case "a/0" -> new CommitLog.Resolved(plan, 0, false);
                case "a/1" -> new CommitLog.Resolved(plan, 1, false);
                case "a/2" -> new CommitLog.Resolved(plan + 1L, 0, false);
                default -> null;
            };
        };
    }

    private static CommitLog.Batch batch(String key, long digest) {
        return new CommitLog.Batch(WORLD, DOMAIN, key, CommitLog.Batch.Kind.APPLY, 7L, digest, 3);
    }

    private static CommitNamespaces.Namespace namespace(String owner, long lifetime, long reset) {
        return new CommitNamespaces.Namespace(owner, lifetime, reset, 77L);
    }

    private static CommitLog.Identity identity(long instanceId, long chunk, String status,
                                               long authorization, long effectRef, long lifetime) {
        return new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instanceId, 5L, chunk, status,
            authorization, effectRef, namespace("producer", lifetime, 0L),
            namespace("controller", lifetime, 0L));
    }

    private static CommitLog.TypedBatch typed(String key, long effectRef, long lifetime,
                                              String... rows) {
        return new CommitLog.TypedBatch(batch(key, 11L),
            identity(1L, 64L, "full", 3L, effectRef, lifetime),
            new EffectStore.Effect(effectRef, "chunk-materialize", "full", List.of(rows)));
    }

    private static EffectStore store() {
        return new EffectStore(4096, 16);
    }

    private static CommitLog log(EffectStore effects) {
        return new CommitLog(() -> 8, order(1L), effects);
    }

    private static CommitLog.Entry head(CommitLog log) {
        return log.rings().get(0).peek();
    }

    @Test
    void aPlainLineKeepsItsColumnsAndDeclaresNoVersion() {
        CommitLog log = log(store());
        log.beginTick(10L);

        CommitLog.Verdict verdict = log.reach(batch("a/0", 11L));
        CommitLog.Entry entry = head(log);

        assertTrue(verdict.logged());
        assertEquals(1L, entry.sequence());
        assertEquals(10L, entry.tickIndex());
        assertEquals(1L, entry.planSequence());
        assertEquals(0, entry.position());
        assertEquals(WORLD, entry.worldId());
        assertEquals(DOMAIN, entry.domainId());
        assertEquals("a/0", entry.nodeKey());
        assertEquals(0L, entry.handle());
        assertEquals(CommitLog.Batch.Kind.APPLY, entry.kind());
        assertEquals(7L, entry.expectedVersion());
        assertEquals(11L, entry.writeSetDigest());
        assertEquals(3, entry.rows());
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, entry.disposition());
        assertNull(entry.code());
        assertFalse(entry.intent());
        assertEquals(CommitLog.SCHEMA_UNDECLARED, entry.identity().schemaVersion(),
            "the plain path declares no schema and must not read as version 0");
        assertEquals(CommitLog.NO_INSTANCE, entry.identity().instanceId());
        assertEquals(CommitLog.NO_EFFECT, entry.identity().effectRef());
        assertEquals(CommitNamespaces.Namespace.NONE, entry.identity().producer());
        assertFalse(entry.identity().declaredTyped());
    }

    @Test
    void aVersionedLineCarriesTheDeclaredColumnsAndTheRecordItPointsAt() {
        EffectStore effects = store();
        CommitLog log = log(effects);
        log.beginTick(10L);

        CommitLog.Verdict verdict = log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64", "state=full"));
        CommitLog.Entry entry = head(log);

        assertTrue(verdict.logged());
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, verdict.disposition());
        assertEquals(CommitLog.SCHEMA_TYPED, entry.identity().schemaVersion());
        assertEquals(1L, entry.identity().instanceId());
        assertEquals(100L, entry.identity().effectRef());
        assertEquals(5L, entry.identity().worldEpoch());
        assertEquals(64L, entry.identity().chunkPos());
        assertEquals("full", entry.identity().targetStatus());
        assertEquals(3L, entry.identity().authorizationGeneration());
        assertTrue(entry.identity().declaredTyped());
        assertEquals(1L, log.typedStored());
        assertEquals(1, effects.records());
        assertEquals(1, effects.pins(), "the line holds the record it points at");

        CommitLog.Replay plainReplay = log.closeTick();
        CommitLog.SchemaReplay schema = log.lastTyped();
        assertEquals(CommitLog.SCHEMA_TYPED, schema.schemaVersion());
        assertEquals(1, schema.typedSteps().size());
        assertEquals(1, plainReplay.steps().size(), "the same line is one step of the plain traversal");
        assertTrue(schema.plainSteps().isEmpty(), "a line that declared a version is not a plain step");
        EffectStore.Recorded record = schema.typedSteps().get(0).effect();
        assertNotNull(record);
        assertEquals(List.of("chunk=64", "state=full"), record.content());
        assertEquals(List.of("chunk", "state"), record.declared());
        assertEquals(0, effects.pins(), "the reference is released once the record was copied");
    }

    @Test
    void theVersionedPathRefusesWhileNoStoreIsBound() {
        CommitLog log = new CommitLog(() -> 8, order(1L));
        log.beginTick(10L);

        CommitLog.Verdict verdict = log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64"));

        assertFalse(verdict.logged());
        assertEquals(RejectCode.COUNTER_MISSING, verdict.code());
        assertEquals(1L, log.typedRefused());
        assertEquals(0L, log.typedStored());
        assertEquals(0L, log.sequence(), "a refused versioned report takes no report number");
        assertEquals(0, log.ringDepth());
        assertNull(log.effects());
    }

    @Test
    void aVersionedLineWithoutADeclaredSchemaOrIdentityIsRefused() {
        CommitLog log = log(store());
        log.beginTick(10L);
        CommitLog.TypedBatch good = typed("a/0", 100L, LIFETIME, "chunk=64");

        CommitLog.Verdict undeclared = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_UNDECLARED, 1L, 5L, 64L, "full", 3L, 100L,
                namespace("producer", LIFETIME, 0L), namespace("controller", LIFETIME, 0L)),
            good.effect()));
        CommitLog.Verdict legacy = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_LEGACY, 1L, 5L, 64L, "full", 3L, 100L,
                namespace("producer", LIFETIME, 0L), namespace("controller", LIFETIME, 0L)),
            good.effect()));
        CommitLog.Verdict noInstance = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, CommitLog.NO_INSTANCE, 5L, 64L, "full", 3L,
                100L, namespace("producer", LIFETIME, 0L), namespace("controller", LIFETIME, 0L)),
            good.effect()));
        CommitLog.Verdict noEpoch = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, CommitLog.NO_WORLD_EPOCH, 64L, "full",
                3L, 100L, namespace("producer", LIFETIME, 0L), namespace("controller", LIFETIME, 0L)),
            good.effect()));
        CommitLog.Verdict noStatus = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L, "", 3L, 100L,
                namespace("producer", LIFETIME, 0L), namespace("controller", LIFETIME, 0L)),
            good.effect()));
        CommitLog.Verdict noAuthorization = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L, "full",
                CommitLog.NO_AUTHORIZATION_GENERATION, 100L, namespace("producer", LIFETIME, 0L),
                namespace("controller", LIFETIME, 0L)), good.effect()));
        CommitLog.Verdict noNamespace = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L, "full", 3L, 100L,
                CommitNamespaces.Namespace.NONE, CommitNamespaces.Namespace.NONE), good.effect()));
        CommitLog.Verdict otherRef = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            identity(1L, 64L, "full", 3L, 999L, LIFETIME), good.effect()));

        for (CommitLog.Verdict verdict : List.of(undeclared, legacy, noInstance, noEpoch, noStatus,
            noAuthorization, noNamespace, otherRef)) {
            assertFalse(verdict.logged());
            assertEquals(RejectCode.VERSION_MISMATCH, verdict.code());
        }
        assertEquals(8L, log.typedRefused());
        assertEquals(0L, log.typedStored());
        assertEquals(0, log.ringDepth());
        assertEquals(0, log.effects().records());
        assertEquals(0, log.reservedSlots(), "a refused report leaves no reservation behind");
    }

    @Test
    void aHeldRingSlotIsNotTakenByAPlainReport() {
        CommitLog log = new CommitLog(() -> 2, order(1L), store());
        log.beginTick(10L);
        CommitLog.RingSlot held = log.reserveSlot(WORLD, DOMAIN);

        assertNotNull(held);
        assertEquals(1, log.reservedSlots());
        assertTrue(log.reach(batch("a/0", 11L)).logged());
        CommitLog.Verdict blocked = log.reach(batch("a/1", 12L));

        assertFalse(blocked.logged(), "the plain report may not take the slot the versioned one holds");
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, blocked.code());
        assertEquals(2, log.ringDepth() + log.reservedSlots());
        assertTrue(log.releaseSlot(held));
        assertTrue(log.reach(batch("a/1", 12L)).logged(),
            "once the slot is given back the plain report fits again");
        assertEquals(1L, log.reservationsTaken());
        assertEquals(1L, log.reservationsReleased());
    }

    @Test
    void aShortageOnEitherSideLeavesNeitherALineNorARecord() {
        EffectStore effects = new EffectStore(4096, 1);
        CommitLog log = new CommitLog(() -> 8, order(1L), effects);
        log.beginTick(10L);
        assertTrue(log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64")).logged());
        long afterFirst = log.sequence();

        CommitLog.Verdict full = log.reachTyped(new CommitLog.TypedBatch(batch("a/1", 12L),
            identity(2L, 65L, "full", 3L, 101L, LIFETIME),
            new EffectStore.Effect(101L, "chunk-materialize", "full", List.of("chunk=65"))));

        assertFalse(full.logged(), "the record room is short");
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, full.code());
        assertEquals(afterFirst, log.sequence(), "a refused report takes no report number");
        assertEquals(1, effects.records(), "no half record stays behind");
        assertEquals(0, effects.reservedBytes());
        assertEquals(0, log.reservedSlots());

        CommitLog tightRing = new CommitLog(() -> 1, order(1L), new EffectStore(4096, 8));
        tightRing.beginTick(10L);
        assertTrue(tightRing.reach(batch("a/0", 11L)).logged());
        CommitLog.Verdict noRoom = tightRing.reachTyped(typed("a/1", 100L, LIFETIME, "chunk=64"));

        assertFalse(noRoom.logged(), "the ring room is short");
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, noRoom.code());
        assertEquals(0, tightRing.effects().records(), "the effect is not stored for a line that did not fit");
        assertEquals(1L, tightRing.sequence(), "the refused report takes no report number");
        assertEquals(0, tightRing.reservedSlots());
    }

    @Test
    void twoLifecyclesThatCarryTheSameInstanceNumberAreRefusedInsteadOfMerged() {
        EffectStore effects = store();
        CommitLog log = log(effects);
        log.beginTick(10L);

        CommitLog.Verdict first = log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64"));
        CommitLog.Verdict second = log.reachTyped(typed("a/1", 101L, OTHER_LIFETIME, "chunk=65"));

        assertTrue(first.logged());
        assertFalse(second.logged(), "the same instance number under another lifetime is another instance");
        assertEquals(RejectCode.VERSION_MISMATCH, second.code());
        assertEquals(1L, log.identityCollisions());
        assertEquals(1L, log.typedStored());
        assertEquals(1, effects.records());
        assertEquals(1, log.ringDepth());
    }

    @Test
    void aResetClearsTheReservationsAndTheVersionedCounters() {
        EffectStore effects = store();
        CommitLog log = log(effects);
        log.beginTick(10L);
        log.reserveSlot(WORLD, DOMAIN);
        log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64"));
        log.reachTyped(typed("a/1", 101L, OTHER_LIFETIME, "chunk=65"));
        log.closeTick();

        log.reset();

        assertEquals(0, log.reservedSlots());
        assertEquals(0L, log.reservationsTaken());
        assertEquals(0L, log.reservationsReleased());
        assertEquals(0L, log.reservationsConsumed());
        assertEquals(0L, log.reservationRefusals());
        assertEquals(0L, log.typedStored());
        assertEquals(0L, log.typedRefused());
        assertEquals(0L, log.identityCollisions());
        assertEquals(0L, log.effectMissingSteps());
        assertNull(log.lastTyped());
        assertEquals(0L, log.sequence());
        assertEquals(1, effects.records(), "the store belongs to its own holder, not to the log reset");
    }

    @Test
    void aTickOfThePlainPathDeclaresNoVersionAndComparesAsItAlwaysDid() {
        CommitLog first = log(store());
        first.beginTick(10L);
        first.reach(batch("a/0", 11L));
        first.reach(batch("a/1", 12L));
        CommitLog.Replay firstRun = first.closeTick();
        CommitLog second = log(store());
        second.beginTick(10L);
        second.reach(batch("a/0", 11L));
        second.reach(batch("a/1", 12L));
        CommitLog.Replay secondRun = second.closeTick();

        assertEquals(0, CommitLog.compareRuns(firstRun, secondRun));
        assertEquals(CommitLog.SCHEMA_UNDECLARED, first.lastTyped().schemaVersion());
        assertTrue(first.lastTyped().typedSteps().isEmpty());
        assertEquals(2, first.lastTyped().plainSteps().size());
        assertEquals(0, first.lastTyped().effectMissing());
    }
}
