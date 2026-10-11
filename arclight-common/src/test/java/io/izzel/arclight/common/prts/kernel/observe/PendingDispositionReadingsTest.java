/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.CommitReservation;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the readout says about an item whose side effect has run and whose terminal line is not
 * written: it is named as waiting, instead of reading like a publish that never came back. */
class PendingDispositionReadingsTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final String NODE = "a/0";

    @AfterEach
    void clear() {
        System.clearProperty("arclight.prts.kernel");
        KernelModule.instance().bindCommitController(null);
        KernelModule.instance().resetReadings();
    }

    private static List<String> export() {
        return KernelReadings.export(KernelModule.instance());
    }

    private static String line(List<String> export, String key) {
        List<String> hits = new ArrayList<>();
        for (String row : export) {
            if (row.startsWith(key + "=")) {
                hits.add(row);
            }
        }
        assertEquals(1, hits.size(), key + " is one line of the readout");
        return hits.get(0);
    }

    @Test
    void withNothingBoundTheColumnOfWaitingItemsIsADash() {
        System.setProperty("arclight.prts.kernel", "true");
        List<String> export = export();
        assertEquals("product.commit.pending_dispositions=-",
            line(export, "product.commit.pending_dispositions"));
    }

    @Test
    void anItemWaitingForItsLineIsNamedByTheReadout() {
        System.setProperty("arclight.prts.kernel", "true");
        EffectStore store = new EffectStore(4096, 4);
        CommitLog.OrderSource plans =
            (worldId, domainId, nodeKey) -> new CommitLog.Resolved(7L, 0, false);
        CommitLog log = new CommitLog(() -> 4, plans, store);
        CommitReservation reservations = new CommitReservation(log, store, plans, "owner-a",
            new CommitNamespaces.ResetEpochs());
        CommitTicket ticket = reservations.reserve(request(reservations)).ticket();
        assertTrue(reservations.beginPublish(ticket));
        CommitTicket.Outcome grown = new CommitTicket.Outcome(CommitTicket.Terminal.PARTIAL, "publish",
            List.of("a=1"), 1, "a-note-nobody-promised-for");
        assertFalse(reservations.finalize(ticket, grown).finalized());
        CommitController controller = new CommitController(reservations,
            line -> new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1"),
                1, ""));
        KernelModule.instance().bindCommitController(controller);
        try {
            List<String> export = export();
            assertEquals("product.commit.pending_dispositions=1:QUEUE_CAP_EXCEEDED:record-refused:1",
                line(export, "product.commit.pending_dispositions"));
            assertEquals("product.commit.state_entries=3",
                line(export, "product.commit.state_entries"),
                "one reservation in flight is one entry in each of the three tables");
            assertEquals("product.commit.closed=0", line(export, "product.commit.closed"));
            assertEquals("product.commit.publishing=0", line(export, "product.commit.publishing"),
                "the item is not publishing any more");
            assertEquals("product.commit.outstanding=1", line(export, "product.commit.outstanding"),
                "and it is still in flight");
            assertEquals("product.commit.conservation=1", line(export, "product.commit.conservation"));
        } finally {
            KernelModule.instance().bindCommitController(null);
        }
    }

    @Test
    void aResetAskedForInsideAPublishActionIsCarriedOutOnceByItsOwner() {
        System.setProperty("arclight.prts.kernel", "true");
        EffectStore store = new EffectStore(4096, 4);
        CommitLog.OrderSource plans =
            (worldId, domainId, nodeKey) -> new CommitLog.Resolved(7L, 0, false);
        CommitLog log = new CommitLog(() -> 4, plans, store);
        CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        CommitReservation reservations = new CommitReservation(log, store, plans, "owner-a", epochs);
        KernelModule module = KernelModule.instance();
        CommitController[] holder = new CommitController[1];
        String[] inside = new String[1];
        holder[0] = new CommitController(reservations, ticket -> {
            module.resetReadings();
            inside[0] = module.commitResetPending() + "/" + holder[0].resets() + "/" + epochs.current();
            return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1"),
                1, "");
        });
        module.bindCommitController(holder[0]);
        try {
            assertTrue(holder[0].submit(request(reservations)).admitted());

            assertEquals("true/0/0", inside[0],
                "the module owes the whole reset and nothing was cleared while the action ran");
            assertEquals(1L, holder[0].resets(), "the lifecycle was reset once, at its safe point");
            assertEquals(1L, epochs.current(), "and the generation moved once");
            assertTrue(module.commitResetPending(), "the module still owes the clear of its counters");
            assertTrue(module.drainCommitReset(), "which it carries out at its next safe point");
            assertFalse(module.commitResetPending());
            assertEquals(1L, holder[0].resets(), "without ending a second lifecycle");
            assertEquals(1L, epochs.current());
        } finally {
            module.bindCommitController(null);
        }
    }

    private static CommitTicket.ReserveRequest request(CommitReservation reservations) {
        CommitNamespaces.Namespace controller = reservations.controller();
        CommitNamespaces.Namespace producer = new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L);
        CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 41L, 5L, 3L,
            "published", 7L, 100L, producer, controller);
        EffectStore.Effect variant = new EffectStore.Effect(100L, "publish", "PUBLISHED", List.of("a=1"));
        return new CommitTicket.ReserveRequest(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY, 7L, 0,
            7L, 4242L, 1, identity, controller, variant, variant, variant);
    }
}
