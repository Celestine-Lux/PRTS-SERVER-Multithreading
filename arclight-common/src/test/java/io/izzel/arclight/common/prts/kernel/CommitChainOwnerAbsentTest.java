/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.CommitReservation;
import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import io.izzel.arclight.common.prts.kernel.config.ExecutableProductFace;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** The owner of a chain comes from the host and from nowhere else. Before the host has ticked there
 * is no owner thread, so a chain that is right in every other respect is still refused whole rather
 * than judged on a thread nothing named and nothing can name back. The case needs a JVM no tick has
 * run in, so it steps aside where the suite has already driven one; the isolated invocation in the
 * evidence runs it on its own and records the reading. */
class CommitChainOwnerAbsentTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final String NODE = "a/0";

    @Test
    void aChainIsRefusedWhileTheHostHasNamedNoOwnerThread() throws IOException {
        KernelModule module = KernelModule.instance();
        assumeFalse(module.commitOwnerThreadBound(),
            "no tick has been driven in this JVM, so the host names no owner thread yet");
        System.setProperty("arclight.prts.kernel", "true");
        ExecutableProductFace.declare();
        EffectStore store = new EffectStore(4096, 8);
        CommitLog.OrderSource plans =
            (worldId, domainId, nodeKey) -> WORLD.equals(worldId) && DOMAIN.equals(domainId)
                && NODE.equals(nodeKey) ? new CommitLog.Resolved(7L, 0, false) : null;
        CommitLog log = new CommitLog(() -> 4, module.commitOrderSource(), store);
        CommitReservation reservations = new CommitReservation(log, store, plans, "owner-a",
            new CommitNamespaces.ResetEpochs());
        CommitLog own = module.commits();
        try {
            assertFalse(module.commitOwnerThreadBound());
            assertEquals("0", reading("product.commit.owner_thread_bound"));
            assertEquals("-", reading("product.commit.owner_thread"));

            KernelModule.CommitBinding binding =
                module.installCommitChain(log, new CommitController(reservations, null));

            assertFalse(binding.bound(), "a chain is not judged on a thread the host never named");
            assertEquals("owner-thread-unbound", binding.refusal());
            assertNull(module.commitController(), "and nothing of it is taken");
            assertSame(own, module.commits(), "the module is still on the log it built for itself");
            assertFalse(module.commitChainIsTheOneLog());
            assertEquals("NOT_WIRED", reading("product.commit.controller_wiring"));
            assertEquals("owner-thread-unbound", reading("product.commit.binding_refusal"));
            assertEquals("0", reading("product.commit.binding_chain_log"));
            assertEquals("0", reading("product.commit.binding_log_lifetime"));
            assertEquals("0", reading("product.commit.binding_store_id"));
            assertTrue(reservations.log() == log, "the refused chain is whole on its own");
        } finally {
            module.rollbackCommitChain();
            module.resetReadings();
            System.clearProperty("arclight.prts.kernel");
            ExecutableProductFace.restore();
        }
    }

    private static String reading(String key) {
        for (String row : KernelReadings.export(KernelModule.instance())) {
            if (row.startsWith(key + "=")) {
                return row.substring(key.length() + 1);
            }
        }
        return "<missing>";
    }
}
