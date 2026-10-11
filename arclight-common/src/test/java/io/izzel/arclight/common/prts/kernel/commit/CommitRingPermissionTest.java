/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What may fill, read or clear a ring: only the log, only for one ring of one pair, and only once
 * per fill. */
class CommitRingPermissionTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";

    private static CommitLog log(int capacity) {
        return new CommitLog(() -> capacity,
            (worldId, domainId, nodeKey) -> new CommitLog.Resolved(7L, 0, false),
            new EffectStore(4096, 8));
    }

    private static CommitLog.Batch batch() {
        return new CommitLog.Batch(WORLD, DOMAIN, "a/0", CommitLog.Batch.Kind.APPLY, 7L, 4242L, 2);
    }

    private static CommitLog.Entry entry() {
        return new CommitLog.Entry(1L, 1L, 7L, 0, WORLD, DOMAIN, "a/0", 0L,
            CommitLog.Batch.Kind.APPLY, 7L, 4242L, 2, CommitLog.Entry.Disposition.ACCEPTED, null, false);
    }

    private static CommitRing ringOf(CommitLog log, String worldId, String domainId) {
        for (CommitRing ring : log.rings()) {
            if (ring.worldId().equals(worldId) && ring.domainId().equals(domainId)) {
                return ring;
            }
        }
        throw new IllegalStateException("no ring for " + worldId + "/" + domainId);
    }

    @Test
    void aNullPermissionFillsNothing() {
        CommitLog log = log(2);
        log.beginTick(1L);
        log.reach(batch());
        CommitRing ring = ringOf(log, WORLD, DOMAIN);
        int depth = ring.depth();
        CommitRing.Push push = ring.push(entry(), null);
        assertFalse(push.stored());
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, push.code());
        assertEquals(1L, ring.permissionRefusals());
        assertEquals(depth, ring.depth());
    }

    @Test
    void onePermissionFillsOneSlot() {
        CommitLog log = log(2);
        CommitLog.RingSlot slot = log.reserveSlot(WORLD, DOMAIN);
        CommitRing ring = ringOf(log, WORLD, DOMAIN);
        CommitLog.RingAuthority permission = log.fill(ring, slot.ticket());
        assertNotNull(permission);
        assertTrue(ring.push(entry(), permission).stored());

        CommitRing.Push again = ring.push(entry(), permission);
        assertFalse(again.stored(), "a permission is spent by the fill it allowed");
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, again.code());
        assertEquals(1, ring.depth());
    }

    @Test
    void aPermissionOfAnotherRingOfTheSamePairIsRefused() {
        CommitLog log = log(2);
        log.beginTick(1L);
        log.reach(batch());
        CommitRing before = ringOf(log, WORLD, DOMAIN);
        log.reset();
        log.beginTick(1L);
        log.reach(batch());
        CommitRing after = ringOf(log, WORLD, DOMAIN);
        assertNotSame(before, after, "the reset leaves a new ring for the same pair");

        CommitRing.Push push = before.push(entry(), log.fill(after, 0L));
        assertFalse(push.stored(), "a permission of the new ring is not a permission of the old one");
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, push.code());
        assertEquals(0, before.depth());
    }

    @Test
    void aPermissionOfAnotherLogIsRefused() {
        CommitLog first = log(2);
        CommitLog second = log(2);
        first.beginTick(1L);
        first.reach(batch());
        second.beginTick(1L);
        second.reach(batch());
        CommitLog.RingAuthority permission = first.fill(ringOf(first, WORLD, DOMAIN), 0L);
        CommitRing other = ringOf(second, WORLD, DOMAIN);
        assertFalse(other.push(entry(), permission).stored(),
            "the pair is the same and the log is not");
        assertEquals(1L, other.permissionRefusals());
    }

    @Test
    void aSlotThisLogNoLongerHoldsMintsNothing() {
        CommitLog log = log(2);
        CommitLog.RingSlot slot = log.reserveSlot(WORLD, DOMAIN);
        CommitRing ring = ringOf(log, WORLD, DOMAIN);
        assertNotNull(log.fill(ring, slot.ticket()));
        assertTrue(log.releaseSlot(slot));
        assertNull(log.fill(ring, slot.ticket()), "a given-back slot mints no permission");
        assertFalse(ring.push(entry(), log.fill(ring, slot.ticket())).stored());
    }

    @Test
    void readingAndClearingNeedAPermissionToo() {
        CommitLog log = log(2);
        log.beginTick(1L);
        log.reach(batch());
        CommitRing ring = ringOf(log, WORLD, DOMAIN);
        int depth = ring.depth();
        assertNotNull(ring.peek(), "reading what the traversal would read stays open");
        assertNull(ring.pop(null));
        assertEquals(depth, ring.depth(), "an outside reader cannot take a line");
        ring.reset(null);
        assertEquals(depth, ring.depth(), "nor clear the ring");
        assertEquals(2L, ring.permissionRefusals());
        assertEquals(depth, ring.reading().depth(), "the snapshot says what the ring holds");
    }

    @Test
    void theMutatorsAreOutOfReachOfAnotherPackage() throws Exception {
        assertFalse(Modifier.isPublic(CommitRing.class.getDeclaredMethod("push", CommitLog.Entry.class,
            CommitLog.RingAuthority.class).getModifiers()));
        assertFalse(Modifier.isPublic(CommitRing.class.getDeclaredMethod("pop",
            CommitLog.RingAuthority.class).getModifiers()));
        assertFalse(Modifier.isPublic(CommitRing.class.getDeclaredMethod("reset",
            CommitLog.RingAuthority.class).getModifiers()));
        assertFalse(Modifier.isPublic(CommitRing.class.getDeclaredConstructor(String.class,
            String.class, int.class, long.class, Object.class).getModifiers()));
        assertFalse(Modifier.isPublic(CommitLog.class.getDeclaredMethod("fill", CommitRing.class,
            long.class).getModifiers()), "the mint is not public either");
        for (var constructor : CommitLog.RingAuthority.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()),
                "a permission cannot be built outside the log");
        }
        assertFalse(Modifier.isPublic(CommitLog.RingAuthority.class.getModifiers()));
    }
}
