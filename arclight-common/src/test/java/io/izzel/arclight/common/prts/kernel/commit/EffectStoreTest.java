/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bounded record store: the room a record takes, the reference a line holds on it, and what a
 * shortage does and does not leave behind. */
class EffectStoreTest {

    private static EffectStore.Effect effect(long ref, String... rows) {
        return new EffectStore.Effect(ref, "chunk-materialize", "full", List.of(rows));
    }

    @Test
    void aStoreWithNoRoomIsRefusedInsteadOfAnsweringLikeAnEmptyTable() {
        assertThrows(IllegalArgumentException.class, () -> new EffectStore(0, 8));
        assertThrows(IllegalArgumentException.class, () -> new EffectStore(4096, 0));
        assertThrows(IllegalArgumentException.class, () -> new EffectStore(-1, -1));
    }

    @Test
    void theRowsAreNormalizedWhenTheRecordIsStored() {
        EffectStore store = new EffectStore(4096, 4);
        EffectStore.Result charged = store.reserve(effect(1L, "state=full", "chunk=64", "chunk=64",
            "  ", "state=full"));
        assertTrue(charged.admitted());

        EffectStore.Result stored = store.commit(charged.slot(), effect(1L, "state=full", "chunk=64",
            "chunk=64", "  ", "state=full"));

        assertTrue(stored.admitted());
        assertEquals(List.of("chunk=64", "state=full"), stored.record().content());
        assertEquals(List.of("chunk", "state"), stored.record().declared());
        assertEquals(1, store.records());
        assertEquals(stored.record().bytes(), store.bytes());
        assertTrue(stored.record().contentDigest().matches("[0-9a-f]+"), "the digest is a byte fold");
    }

    @Test
    void anotherOrderOfTheSameRowsIsTheSameRecord() {
        EffectStore first = new EffectStore(4096, 4);
        EffectStore.Result a = first.reserve(effect(1L, "chunk=64", "state=full"));
        first.commit(a.slot(), effect(1L, "chunk=64", "state=full"));

        EffectStore second = new EffectStore(4096, 4);
        EffectStore.Result b = second.reserve(effect(1L, "state=full", "chunk=64"));
        second.commit(b.slot(), effect(1L, "state=full", "chunk=64"));

        assertEquals(first.record(1L).content(), second.record(1L).content());
        assertEquals(first.record(1L).contentDigest(), second.record(1L).contentDigest());
        assertEquals(first.record(1L).bytes(), second.record(1L).bytes());
    }

    @Test
    void theRecordCountAndTheBytesAreBothBounds() {
        EffectStore byRecords = new EffectStore(1 << 20, 2);
        EffectStore.Result first = byRecords.reserve(effect(1L, "chunk=1"));
        assertTrue(byRecords.commit(first.slot(), effect(1L, "chunk=1")).admitted());
        EffectStore.Result second = byRecords.reserve(effect(2L, "chunk=2"));
        assertTrue(byRecords.commit(second.slot(), effect(2L, "chunk=2")).admitted());

        EffectStore.Result third = byRecords.reserve(effect(3L, "chunk=3"));

        assertFalse(third.admitted());
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, third.code());
        assertEquals(2, byRecords.records());
        assertEquals(0, byRecords.reservedRecords(), "a refused reservation charges nothing");
        assertEquals(1L, byRecords.refused());

        EffectStore byBytes = new EffectStore(100, 16);
        EffectStore.Result roomy = byBytes.reserve(effect(1L, "x".repeat(64)));
        assertFalse(roomy.admitted(), "the record alone is larger than the whole store");
        assertEquals(0, byBytes.bytes());
        assertEquals(0, byBytes.reservedBytes());
    }

    @Test
    void aReservationChargesTheRoomBeforeAnythingIsStored() {
        EffectStore store = new EffectStore(4096, 1);
        EffectStore.Result held = store.reserve(effect(1L, "chunk=64"));

        assertEquals(0, store.records());
        assertEquals(held.slot().bytes(), store.reservedBytes());
        assertEquals(1, store.reservedRecords());
        assertFalse(store.reserve(effect(2L, "chunk=65")).admitted(),
            "the room one reservation holds is not handed to another");
        assertTrue(store.release(held.slot()));
        assertEquals(0, store.reservedBytes());
        assertEquals(1L, store.released());
        assertTrue(store.reserve(effect(2L, "chunk=65")).admitted());
    }

    @Test
    void aReservationWhoseRecordChangedIsRefusedAndItsRoomGivenBack() {
        EffectStore store = new EffectStore(4096, 4);
        EffectStore.Result held = store.reserve(effect(1L, "chunk=64"));

        EffectStore.Result changed = store.commit(held.slot(), effect(1L, "chunk=64", "state=full"));

        assertFalse(changed.admitted());
        assertEquals(RejectCode.VERSION_MISMATCH, changed.code());
        assertEquals(0, store.records());
        assertEquals(0, store.reservedBytes());
        assertEquals(1L, store.released());
    }

    @Test
    void aRecordALineStillHoldsIsNotEvicted() {
        EffectStore store = new EffectStore(4096, 4);
        EffectStore.Result held = store.reserve(effect(1L, "chunk=64"));
        store.commit(held.slot(), effect(1L, "chunk=64"));

        assertFalse(store.evict(1L), "the line that points at the record still holds it");
        assertEquals(1L, store.evictionRefusals());
        assertEquals(1, store.pins());
        assertTrue(store.unpin(1L));
        assertEquals(0, store.pins());
        assertTrue(store.evict(1L));
        assertEquals(1L, store.evicted());
        assertEquals(0, store.bytes());
        assertNull(store.record(1L), "an evicted record is missing, never empty");
        assertEquals(1L, store.missing());
    }

    @Test
    void aReferenceNothingHoldsIsAnsweredAsMissing() {
        EffectStore store = new EffectStore(4096, 4);

        assertNull(store.record(9L));
        assertFalse(store.pin(9L));
        assertEquals(2L, store.missing());
        assertFalse(store.evict(9L));
        assertEquals(3L, store.missing());
    }

    @Test
    void aReferenceOfZeroIsNotARecord() {
        EffectStore store = new EffectStore(4096, 4);

        EffectStore.Result refused = store.reserve(effect(0L, "chunk=64"));

        assertFalse(refused.admitted());
        assertEquals(RejectCode.COUNTER_MISSING, refused.code());
        assertEquals(0, store.records());
    }

    @Test
    void theStoreStaysInsideBothBoundsWhileItIsFilled() {
        EffectStore store = new EffectStore(4096, 8);
        long admitted = 0L;

        for (long ref = 1L; ref <= 64L; ref++) {
            EffectStore.Result held = store.reserve(effect(ref, "chunk=" + ref, "state=full"));
            if (held.admitted() && store.commit(held.slot(), effect(ref, "chunk=" + ref,
                "state=full")).admitted()) {
                admitted++;
            }
            assertTrue(store.records() <= store.capacityRecords());
            assertTrue(store.bytes() <= store.capacityBytes());
            assertEquals(0, store.reservedBytes(), "nothing stays charged once the answer is given");
        }

        assertEquals(8L, admitted);
        assertEquals(8, store.records());
        assertEquals(8L, store.stored());
        assertEquals(56L, store.refused());
        assertEquals(8, store.references().size());
    }

    @Test
    void aReservationWhoseContentChangedButNotItsLengthIsRefused() {
        EffectStore store = new EffectStore(4096, 4);
        EffectStore.Result held = store.reserve(effect(1L, "x=1"));

        EffectStore.Result changed = store.commit(held.slot(), effect(1L, "x=2"));

        assertFalse(changed.admitted(), "two records of one length are not one record");
        assertEquals(RejectCode.VERSION_MISMATCH, changed.code());
        assertEquals(0, store.records());
        assertEquals(0, store.reservedBytes());
        assertEquals(1L, store.released());
    }

    @Test
    void aReservationFreezesTheOperationAndTheOutcomeAsWell() {
        EffectStore store = new EffectStore(4096, 4);
        EffectStore.Result held = store.reserve(effect(1L, "chunk=64"));

        EffectStore.Result anotherOperation = store.commit(held.slot(),
            new EffectStore.Effect(1L, "chunk-erase", "full", List.of("chunk=64")));

        assertFalse(anotherOperation.admitted());
        assertEquals(RejectCode.VERSION_MISMATCH, anotherOperation.code());
        assertEquals(0, store.records());

        EffectStore.Result kept = store.reserve(effect(2L, "chunk=64"));
        EffectStore.Result changedOutcome = store.commit(kept.slot(),
            new EffectStore.Effect(2L, "chunk-materialize", "partial", List.of("chunk=64")));

        assertFalse(changedOutcome.admitted());
        assertEquals(0, store.records());

        EffectStore.Result same = store.reserve(effect(3L, "chunk=64"));
        assertTrue(store.commit(same.slot(), effect(3L, "chunk=64")).admitted());
        assertEquals(1, store.records());
    }

    @Test
    void theFrozenSlotCarriesWhatItChargedFor() {
        EffectStore store = new EffectStore(4096, 4);

        EffectStore.Result held = store.reserve(effect(1L, "state=full", "chunk=64"));

        assertEquals(List.of("chunk=64", "state=full"), held.slot().content());
        assertEquals("chunk-materialize", held.slot().operationKind());
        assertEquals("full", held.slot().outcome());
        assertTrue(held.slot().bytes() > 0);
        assertEquals(held.slot().bytes(), store.reservedBytes());
    }

    @Test
    void theOperationAndTheOutcomeAreChargedLikeTheRows() {
        EffectStore small = new EffectStore(100, 4);

        EffectStore.Result tooLarge = small.reserve(new EffectStore.Effect(1L, "chunk-materialize",
            "f".repeat(64), List.of("chunk=64")));

        assertFalse(tooLarge.admitted(), "an outcome is paid for like a row is");
        assertEquals(0, small.reservedBytes());

        EffectStore roomy = new EffectStore(4096, 4);
        EffectStore.Result admitted = roomy.reserve(new EffectStore.Effect(1L, "chunk-materialize",
            "f".repeat(64), List.of("chunk=64")));

        assertTrue(admitted.admitted());
        assertTrue(admitted.slot().bytes() > 64, "the charged room carries the outcome");
    }

    @Test
    void theTwoBudgetsAreComparedWithoutWrapping() {
        assertFalse(EffectStore.fitsBytes(Long.MAX_VALUE, 0L, 0L, 4096));
        assertFalse(EffectStore.fitsBytes(0L, Long.MAX_VALUE, 0L, 4096));
        assertFalse(EffectStore.fitsBytes(0L, 0L, Long.MAX_VALUE, 4096));
        assertFalse(EffectStore.fitsBytes(-1L, 0L, 1L, 4096));
        assertFalse(EffectStore.fitsBytes(4000L, 200L, 100L, 4096));
        assertTrue(EffectStore.fitsBytes(4000L, 0L, 96L, 4096));
        assertFalse(EffectStore.fitsBytes(4000L, 0L, 97L, 4096));
        assertFalse(EffectStore.fitsRecords(Long.MAX_VALUE, 0L, 4));
        assertFalse(EffectStore.fitsRecords(0L, Long.MAX_VALUE, 4));
        assertTrue(EffectStore.fitsRecords(2L, 2L, 4));
        assertFalse(EffectStore.fitsRecords(2L, 3L, 4));
        assertFalse(EffectStore.fitsRecords(-1L, 0L, 4));
    }

    @Test
    void aReferenceThatIsAlreadyTakenIsNotReservedTwice() {
        EffectStore store = new EffectStore(4096, 4);
        EffectStore.Result held = store.reserve(effect(1L, "chunk=64"));
        assertTrue(store.commit(held.slot(), effect(1L, "chunk=64")).admitted());

        EffectStore.Result again = store.reserve(effect(1L, "chunk=65"));

        assertFalse(again.admitted(), "one reference names one content in one run");
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, again.code());
        assertEquals(1, store.records());

        EffectStore.Result pending = store.reserve(effect(2L, "chunk=65"));
        EffectStore.Result taken = store.reserve(effect(2L, "chunk=66"));

        assertTrue(pending.admitted());
        assertFalse(taken.admitted(), "a reference a reservation already holds is taken");
    }
}
