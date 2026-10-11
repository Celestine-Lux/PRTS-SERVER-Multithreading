/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The room of a typed record: the promise written before the side effect, and the handle that
 * promise is tied to. A handle of another store, of another lifecycle or of no reservation at all
 * moves nothing here. */
class EffectStoreCapacityTest {

    private static EffectStore.Effect effect(long ref, String outcome, String... rows) {
        return new EffectStore.Effect(ref, "publish", outcome, List.of(rows));
    }

    @Test
    void aFrozenReservationStillFreezesTheRecordItChargedFor() {
        EffectStore store = new EffectStore(4096, 4);
        EffectStore.Slot slot = store.reserve(effect(7L, "PUBLISHED", "a=1")).slot();
        assertEquals(1, store.reservedRecords());
        assertTrue(store.reservedBytes() > 0);

        EffectStore.Result other = store.commit(slot, effect(7L, "PUBLISHED", "a=2"));
        assertFalse(other.admitted(), "a reservation is not the room for another record");
        assertEquals(0, store.reservedRecords(), "and it is given back when the record changed");

        EffectStore.Slot again = store.reserve(effect(7L, "PUBLISHED", "a=1")).slot();
        assertTrue(store.commit(again, effect(7L, "PUBLISHED", "a=1")).admitted());
        assertEquals("a=1", store.record(7L).content().get(0));
    }

    @Test
    void roomAloneIsPromisedAndTheTerminalRecordIsWrittenInsideIt() {
        EffectStore store = new EffectStore(4096, 4);
        EffectStore.CapacityResult reserved = store.reserveCapacity(7L, 2, 512);
        assertTrue(reserved.admitted());
        assertEquals(512, store.capacityReservedBytes());
        assertEquals(2, store.capacityReservedRecords());
        assertEquals(1, store.capacitySlots());
        assertEquals(0, store.reservedBytes(), "room alone is its own protocol, not a relaxed freeze");
        assertEquals(0, store.reservedRecords());

        EffectStore.CapacityResult stored = store.finalizeCapacity(reserved.slot(),
            effect(7L, "PARTIAL", "a=1", "b=2"));
        assertTrue(stored.admitted());
        assertEquals(0, store.capacityReservedBytes());
        assertEquals(0, store.capacityReservedRecords());
        assertEquals(1, store.records());
        assertEquals("PARTIAL", store.record(7L).outcome());
        assertEquals(1, store.pins());
        assertTrue(store.bytes() <= 512, "the record stayed inside the room that was promised");
        assertEquals(1L, store.capacityFinalized());
    }

    @Test
    void aHandleThatIsOnlyEqualMovesNothing() {
        EffectStore store = new EffectStore(4096, 4);
        EffectStore.Slot held = store.reserve(effect(7L, "PUBLISHED", "a=1")).slot();
        EffectStore.Slot copy = new EffectStore.Slot(held.ticket(), held.effectRef(), held.bytes(),
            held.operationKind(), held.outcome(), held.contentDigest(), held.content());
        assertEquals(held, copy, "the full value of the handle is the same");
        assertFalse(store.release(copy), "and the store still refuses it: the value alone is not the handle");
        assertEquals(1, store.reservedRecords(), "the room of the real reservation is untouched");
        assertTrue(store.release(held));
    }

    @Test
    void aHandleOfAnotherStoreMovesNothingThere() {
        EffectStore a = new EffectStore(4096, 4);
        EffectStore b = new EffectStore(4096, 4);
        assertNotEquals(a.storeId(), b.storeId());

        EffectStore.Slot slotA = a.reserve(effect(7L, "PUBLISHED", "a=1")).slot();
        assertFalse(b.release(slotA), "the first ticket of another store is not this store's");
        EffectStore.Slot slotB = b.reserve(effect(7L, "PUBLISHED", "a=1")).slot();
        assertEquals(slotA.ticket(), slotB.ticket(), "the two stores number their tickets alike");
        assertFalse(b.commit(slotA, effect(7L, "PUBLISHED", "a=1")).admitted());
        assertEquals(1, b.reservedRecords(), "the reservation of the other store is untouched");

        EffectStore.CapacitySlot roomA = a.reserveCapacity(9L, 1, 256).slot();
        EffectStore.CapacitySlot roomB = b.reserveCapacity(9L, 1, 256).slot();
        assertEquals(roomA.ticket(), roomB.ticket());
        assertFalse(b.releaseCapacity(roomA));
        assertFalse(a.releaseCapacity(roomB));
        assertEquals(1, b.capacitySlots());
        assertTrue(a.releaseCapacity(roomA));
        assertEquals(0, a.capacitySlots());
        assertTrue(b.releaseCapacity(roomB));
        assertTrue(a.foreignHandles() + b.foreignHandles() >= 4L);
    }

    @Test
    void aRecordPastItsPromiseIsAFaultAndTheStoreStopsHandingOutRoom() {
        EffectStore store = new EffectStore(4096, 4);
        EffectStore.CapacitySlot slot = store.reserveCapacity(7L, 1, 64).slot();
        EffectStore.CapacityResult grown = store.finalizeCapacity(slot,
            effect(7L, "FAILED", "a=1", "b=2"));

        assertFalse(grown.admitted());
        assertEquals(1L, store.integrityFaults());
        assertEquals("reserved-bound-exceeded", store.integrityReason());
        assertTrue(store.halted());
        assertEquals(0, store.records(), "nothing was written past the promise");
        assertEquals(1, store.unresolvedTerminals().size(), "and the item is still readable");
        assertFalse(store.reserveCapacity(8L, 1, 64).admitted(), "no more room is handed out");
        assertEquals(1, store.capacitySlots());
    }

    @Test
    void thePromiseIsChargedAgainstTheCapacityLikeAnyOtherRoom() {
        EffectStore store = new EffectStore(1024, 2);
        EffectStore.CapacitySlot first = store.reserveCapacity(7L, 1, 700).slot();
        assertNotNull(first);
        assertEquals(700, store.capacityReservedBytes());
        assertFalse(store.reserveCapacity(8L, 2, 512).admitted(),
            "the two promises do not fit the bytes that are left");
        assertFalse(store.reserveCapacity(9L, 2, 256).admitted(),
            "nor do the two record promises fit the record bound");
        assertEquals(1, store.capacitySlots());
        assertTrue(store.reserveCapacity(9L, 1, 256).admitted());
        assertTrue(store.releaseCapacity(first));
        assertEquals(1, store.capacitySlots());
    }
}
