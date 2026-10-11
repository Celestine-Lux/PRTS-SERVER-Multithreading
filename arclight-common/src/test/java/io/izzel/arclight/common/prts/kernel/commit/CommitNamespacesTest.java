/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The runtime namespace: which columns decide that a record belongs to an earlier lifecycle, and
 * which column is only there to be read by a diagnostic. */
class CommitNamespacesTest {

    @Test
    void oneProcessNeverMintsTheSameLifetimeTwice() {
        long first = CommitNamespaces.Lifetimes.mint();
        long second = CommitNamespaces.Lifetimes.mint();

        assertNotEquals(first, second);
        assertNotEquals(CommitNamespaces.NO_LIFETIME, first);
        assertTrue(first > 0L);
    }

    @Test
    void stalenessIsReadFromTheLifetimeAndTheResetGenerationOnly() {
        CommitNamespaces.Namespace held = new CommitNamespaces.Namespace("producer", 7L, 0L, 1L);
        CommitNamespaces.Namespace otherThread = new CommitNamespaces.Namespace("producer", 7L, 0L, 2L);
        CommitNamespaces.Namespace otherOwner = new CommitNamespaces.Namespace("elsewhere", 7L, 0L, 1L);
        CommitNamespaces.Namespace nextReset = new CommitNamespaces.Namespace("producer", 7L, 1L, 1L);
        CommitNamespaces.Namespace nextLife = new CommitNamespaces.Namespace("producer", 8L, 0L, 1L);

        assertFalse(held.staleAgainst(otherThread), "the thread is not what makes a record stale");
        assertFalse(held.staleAgainst(otherOwner), "the owner column is not what makes it stale");
        assertTrue(held.staleAgainst(nextReset), "a later reset generation is another lifecycle");
        assertTrue(held.staleAgainst(nextLife), "another module lifetime is another lifecycle");
        assertTrue(held.staleAgainst(null));
    }

    @Test
    void theDeclaredDefaultNamespaceNamesNothing() {
        assertFalse(CommitNamespaces.Namespace.NONE.declared());
        assertEquals(CommitNamespaces.NO_LIFETIME, CommitNamespaces.Namespace.NONE.lifetime());
        assertEquals(CommitNamespaces.NO_THREAD, CommitNamespaces.Namespace.NONE.threadId());
        assertFalse(new CommitNamespaces.Namespace(null, 7L, 0L, 1L).text().startsWith("null"));
    }

    @Test
    void theResetOwnerHandsOutTheGenerationsOfOneLifecycle() {
        CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();

        assertEquals(0L, epochs.current());
        assertEquals(1L, epochs.advance());
        assertEquals(1L, epochs.current());
        assertEquals(2L, epochs.advance());
    }

    @Test
    void oneLifecycleAdvancingDoesNotMoveAnother() {
        CommitNamespaces.ResetEpochs first = new CommitNamespaces.ResetEpochs();
        CommitNamespaces.ResetEpochs second = new CommitNamespaces.ResetEpochs();

        first.advance();

        assertEquals(1L, first.current());
        assertEquals(0L, second.current());
        assertTrue(new CommitNamespaces.Namespace("producer", 7L, first.current(), 1L)
            .staleAgainst(new CommitNamespaces.Namespace("producer", 7L, second.current(), 1L)));
    }

    @Test
    void aNamespaceReadsAsOneColumn() {
        assertEquals("producer/7/0/1", new CommitNamespaces.Namespace("producer", 7L, 0L, 1L).text());
    }
}
