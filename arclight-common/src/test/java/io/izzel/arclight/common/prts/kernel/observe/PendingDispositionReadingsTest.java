/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.CommitReservation;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import io.izzel.arclight.common.prts.kernel.config.ExecutableProductFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
    void clear() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        KernelModule module = KernelModule.instance();
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.markSafePoint();
        assertFalse(module.commitResetPending(), "no request outlives the case that made it");
        module.rollbackCommitChain();
        ExecutableProductFace.restore();
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

    /** Names the owner thread the way a host does and installs the chain of the fixture. */
    private static void hostAndInstall(CommitLog log, CommitController controller) throws IOException {
        ExecutableProductFace.declare();
        KernelModule module = KernelModule.instance();
        module.serverTick(List.of(WORLD));
        assertTrue(module.installCommitChain(log, controller).bound(),
            "the whole chain is taken or none of it is");
    }

    private static CommitTicket.Outcome published() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1"), 1, "");
    }

    @Test
    void withNothingBoundTheColumnOfWaitingItemsIsADash() {
        System.setProperty("arclight.prts.kernel", "true");
        List<String> export = export();
        assertEquals("product.commit.pending_dispositions=-",
            line(export, "product.commit.pending_dispositions"));
    }

    @Test
    void anItemWaitingForItsLineIsNamedByTheReadout() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        EffectStore store = new EffectStore(4096, 4);
        CommitLog.OrderSource plans =
            (worldId, domainId, nodeKey) -> new CommitLog.Resolved(7L, 0, false);
        KernelModule module = KernelModule.instance();
        CommitLog log = new CommitLog(() -> 4, module.commitOrderSource(), store);
        CommitReservation reservations = new CommitReservation(log, store, plans, "owner-a",
            new CommitNamespaces.ResetEpochs());
        CommitController controller = new CommitController(reservations,
            line -> new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1"),
                1, ""));
        // The chain is taken while it is healthy; the item that waits for a line is created after
        // that, through the very chain the module adopted.
        hostAndInstall(log, controller);
        try {
            CommitTicket ticket = reservations.reserve(request(reservations)).ticket();
            assertTrue(reservations.beginPublish(ticket));
            CommitTicket.Outcome grown = new CommitTicket.Outcome(CommitTicket.Terminal.PARTIAL,
                "publish", List.of("a=1"), 1, "a-note-nobody-promised-for");
            assertFalse(reservations.finalize(ticket, grown).finalized());
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
            assertFalse(module.rollbackCommitChain(),
                "a rollback does not take the module off a chain that still owes a line");
            assertEquals("product.commit.binding_refusal=rollback-pending-dispositions",
                line(export(), "product.commit.binding_refusal"));
            assertEquals("product.commit.controller_wiring=BOUND",
                line(export(), "product.commit.controller_wiring"), "the evidence stays reachable");

            assertTrue(reservations.finalize(ticket, published()).finalized(),
                "the owner hands the waiting item its line");
            assertEquals("product.commit.pending=0", line(export(), "product.commit.pending"));
            assertEquals("product.commit.effect_capacity_slots=0",
                line(export(), "product.commit.effect_capacity_slots"), "and the room comes back");
            assertTrue(module.rollbackCommitChain(), "only now is the rollback taken");
            assertEquals("product.commit.controller_wiring=NOT_WIRED",
                line(export(), "product.commit.controller_wiring"));
        } finally {
            module.rollbackCommitChain();
        }
    }

    @Test
    void aResetAskedForInsideAPublishActionIsCarriedOutOnceByItsOwner() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        EffectStore store = new EffectStore(4096, 4);
        CommitLog.OrderSource plans =
            (worldId, domainId, nodeKey) -> new CommitLog.Resolved(7L, 0, false);
        KernelModule module = KernelModule.instance();
        CommitLog log = new CommitLog(() -> 4, module.commitOrderSource(), store);
        CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        CommitReservation reservations = new CommitReservation(log, store, plans, "owner-a", epochs);
        CommitController[] holder = new CommitController[1];
        String[] inside = new String[1];
        holder[0] = new CommitController(reservations, ticket -> {
            module.resetReadings();
            inside[0] = module.commitResetPending() + "/" + holder[0].resets() + "/" + epochs.current();
            return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1"),
                1, "");
        });
        hostAndInstall(log, holder[0]);
        try {
            assertTrue(holder[0].submit(request(reservations)).admitted());

            assertEquals("true/0/0", inside[0],
                "the module owes the whole reset and nothing was cleared while the action ran");
            assertEquals(0L, holder[0].resets(),
                "nothing ran the lifecycle before the module was asked to clear");
            assertEquals(0L, epochs.current(), "and no generation moved with it");
            assertTrue(module.commitResetPending(), "the module still owes the clear of its counters");
            assertTrue(module.drainCommitReset(), "which it carries out at its next safe point");
            assertFalse(module.commitResetPending());
            assertEquals(1L, holder[0].resets(), "the lifecycle is reset once, at that safe point");
            assertEquals(1L, epochs.current(), "and the generation moves once with it");
            assertFalse(module.drainCommitReset(), "the request is not carried out twice");
            assertEquals(1L, holder[0].resets(), "without ending a second lifecycle");
            assertEquals(1L, epochs.current());
        } finally {
            module.rollbackCommitChain();
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
