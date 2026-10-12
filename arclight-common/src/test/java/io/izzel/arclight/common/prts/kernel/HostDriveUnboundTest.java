/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** A request that arrives before the host has named the thread it runs the server on is refused where
 * it was asked and queues nothing: there is no owner to answer it and nothing may be cleared on a
 * thread no tick has named. The case needs a JVM no tick has run in, so it steps aside where the
 * suite has already driven one; the isolated invocation in the evidence runs it on its own. */
class HostDriveUnboundTest {

    @Test
    void aRequestBeforeTheHostNamedAnOwnerThreadClearsNothingAndQueuesNothing() {
        KernelModule module = KernelModule.instance();
        assumeFalse(module.commitOwnerThreadBound(),
            "no tick has been driven in this JVM, so the host names no owner thread yet");
        String before = HostDriveFixture.reading("kernel.tick_index");
        long requests = HostDriveFixture.counter("product.commit.reset_requests");

        HostDriveFixture.Asked asked =
            HostDriveFixture.requestFromAnotherThread(module, HostDriveFixture.FIXTURE_SOURCE);

        assertFalse(asked.request().carriedOut(), "nothing is carried out without an owner");
        assertFalse(asked.request().handedOff(), "and nothing is queued for an owner that is not there");
        assertEquals("owner-thread-unbound", asked.request().reason());
        assertEquals("0", HostDriveFixture.reading("product.commit.owner_thread_bound"));
        assertEquals("-", HostDriveFixture.reading("product.commit.owner_thread"));
        assertEquals("0", HostDriveFixture.reading("product.commit.reset_queued"));
        assertTrue(module.resetQueueSources().isEmpty(), "the queue of the owner stays empty");
        assertEquals(before, HostDriveFixture.reading("kernel.tick_index"),
            "and not one counter was cleared by the refusal");
        assertEquals(requests + 1L, HostDriveFixture.counter("product.commit.reset_requests"),
            "the request was counted where it arrived");
        assertEquals(HostDriveFixture.FIXTURE_SOURCE + "|" + asked.threadId()
            + "|-|none|owner-thread-unbound", HostDriveFixture.reading("product.commit.reset_trace"),
            "the trace names the thread that asked and no thread that answered");
        assertEquals("0", HostDriveFixture.reading("product.commit.reset_trace_cross_thread"));
    }

    @Test
    void aWorldNoListEverCarriedIsRefusedAsUntracked() {
        KernelModule module = KernelModule.instance();
        assumeFalse(module.commitOwnerThreadBound(),
            "no tick has been driven in this JVM, so no list of worlds has been observed either");

        KernelModule.WorldStopFlush row = module.flushWorldAtStop(HostDriveFixture.WORLD);

        assertEquals(0L, row.worldEpoch(),
            "a world that was never tracked reads as untracked, and untracked is a refusal and not a zero");
        assertEquals("world-untracked", row.refusal());
        assertFalse(row.ordered(), "and no step of the set was taken for it");
        assertEquals("0", HostDriveFixture.reading("product.commit.stop_flush_sessions"));
        assertEquals("1", HostDriveFixture.reading("product.commit.stop_flush_refusals"),
            "the refusal is counted where it was made");
    }
}
