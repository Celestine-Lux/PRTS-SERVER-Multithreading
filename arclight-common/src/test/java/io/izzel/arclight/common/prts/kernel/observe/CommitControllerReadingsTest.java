/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitReservation;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import io.izzel.arclight.common.prts.kernel.config.ExecutableProductFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The face of the commit controller: what it reads while nothing is bound, and what the module
 * does with a reset while a line is publishing. */
class CommitControllerReadingsTest {

    private static final List<String> ADDED = List.of("product.commit.controller_wiring",
        "product.commit.controller_publisher", "product.commit.controller_owner",
        "product.commit.controller_reset_epoch", "product.commit.controller_reset_pending",
        "product.commit.submits", "product.commit.supplier_calls", "product.commit.reserve_success",
        "product.commit.finalized", "product.commit.released_total", "product.commit.outstanding",
        "product.commit.publishing", "product.commit.published_total", "product.commit.partial_total",
        "product.commit.failed_total", "product.commit.published_units",
        "product.commit.conservation", "product.commit.double_finalize",
        "product.commit.unknown_ticket", "product.commit.stale_refusals",
        "product.commit.cross_log_refusals", "product.commit.off_thread_refusals",
        "product.commit.reserve_refusals", "product.commit.request_refusals",
        "product.commit.reset_voided", "product.commit.reset_during_publish",
        "product.commit.reset_reentrant_refusals", "product.commit.reset_handoffs",
        "product.commit.resets", "product.commit.resets_deferred",
        "product.commit.terminal_ledger_entries", "product.commit.terminal_ledger",
        "product.commit.effect_capacity_reserved_bytes",
        "product.commit.effect_capacity_reserved_records", "product.commit.effect_capacity_slots",
        "product.commit.effect_foreign_handles", "product.commit.effect_integrity_faults",
        "product.commit.effect_state", "product.commit.effect_unresolved_terminals",
        "product.commit.chain_reserved", "product.commit.chain_finalized",
        "product.commit.chain_released", "product.commit.chain_in_flight",
        "product.commit.chain_capacity_reserved", "product.commit.chain_capacity_finalized",
        "product.commit.chain_capacity_released", "product.commit.chain_log_taken",
        "product.commit.chain_log_consumed", "product.commit.chain_log_released",
        "product.commit.chain_conservation", "product.commit.ledger_evicted",
        "product.commit.close_calls", "product.commit.close_given_back",
        "product.commit.close_unrecorded", "product.commit.close_publishing",
        "product.commit.reset_requests", "product.commit.reset_hand_offs",
        "product.commit.reset_source", "product.commit.reset_closed_refusals",
        "product.commit.host_drive_wiring",
        "commit.ticket_stored", "commit.ticket_refused", "commit.hybrid_order_refusals",
        "commit.cross_log_refusals", "commit.stale_ticket_refusals", "commit.integrity_faults",
        "commit.held_plans");

    @AfterEach
    void clear() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        KernelModule module = KernelModule.instance();
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.resetReadings();
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

    @Test
    void everyControllerKeyIsThereOnceAndReadsZeroOrNotWired() {
        System.setProperty("arclight.prts.kernel", "true");
        List<String> export = export();
        for (String key : ADDED) {
            line(export, key);
        }
        assertEquals("product.commit.controller_wiring=NOT_WIRED",
            line(export, "product.commit.controller_wiring"));
        assertEquals("product.commit.controller_publisher=NONE",
            line(export, "product.commit.controller_publisher"));
        assertEquals("product.commit.controller_owner=-", line(export, "product.commit.controller_owner"));
        assertEquals("product.commit.controller_reset_epoch=0",
            line(export, "product.commit.controller_reset_epoch"));
        assertEquals("product.commit.supplier_calls=0", line(export, "product.commit.supplier_calls"));
        assertEquals("product.commit.outstanding=0", line(export, "product.commit.outstanding"));
        assertEquals("product.commit.conservation=1", line(export, "product.commit.conservation"));
        assertEquals("product.commit.terminal_ledger=-", line(export, "product.commit.terminal_ledger"));
        assertEquals("product.commit.effect_state=NOT_WIRED", line(export, "product.commit.effect_state"));
        assertEquals("product.commit.chain_conservation=-",
            line(export, "product.commit.chain_conservation"),
            "with no chain adopted there is no balance whose columns could hold");
        // The counters of the chain outlive a clear on purpose, the way the counters of the
        // controller do, so what a leg moved is read as a delta and the key itself is asserted
        // once here: with nothing bound every column of it is present and reads zero or a dash.
        assertEquals("product.commit.host_drive_wiring=NOT_WIRED",
            line(export, "product.commit.host_drive_wiring"),
            "no build of this tree drives the two host requests yet");
        assertEquals("commit.ticket_stored=0", line(export, "commit.ticket_stored"));
        assertEquals("commit.hybrid_order_refusals=0", line(export, "commit.hybrid_order_refusals"));
        assertEquals("commit.held_plans=0", line(export, "commit.held_plans"));
    }

    @Test
    void aResetThatArrivesInsideAPublishActionClearsNothingAndIsCarriedOutOnceByTheModule()
        throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        KernelModule module = KernelModule.instance();
        ExecutableProductFace.declare();
        module.serverTick(List.of("world-a"));
        EffectStore store = new EffectStore(4096, 8);
        CommitLog.OrderSource plans = (worldId, domainId, nodeKey) -> new CommitLog.Resolved(7L, 0, false);
        CommitLog log = new CommitLog(() -> 4, module.commitOrderSource(), store);
        CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        CommitReservation reservations = new CommitReservation(log, store, plans, "owner-a", epochs);
        final CommitController[] holder = new CommitController[1];
        final String[] inside = new String[1];
        holder[0] = new CommitController(reservations, ticket -> {
            module.resetReadings();
            inside[0] = holder[0].resetReentrantRefusals() + "/" + holder[0].resets() + "/"
                + epochs.current();
            return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
                List.of("a=1"), 1, "");
        });
        assertTrue(module.installCommitChain(log, holder[0]).bound());
        try {
            holder[0].submit(request(epochs, reservations));
            assertEquals("1/0/0", inside[0],
                "the module asked for a reset inside the publish action and nothing was cleared");
            assertEquals(0L, holder[0].resets(),
                "the controller runs no reset of its own once the action is over");
            assertEquals(1L, holder[0].deferredResets(), "the request is owed, and it is owed here");
            assertTrue(module.commitResetPending());
            assertEquals("BOUND", module.commitControllerWiring());
            assertEquals(1L, holder[0].acceptedPublished());
            assertEquals(1L, log.sequence(), "the log of the chain still carries the line it wrote");
            assertNull(log.lastResetTyped(),
                "and carries no projection at all: the controller ends no lifecycle of its own");

            assertTrue(module.drainCommitReset(), "the module carries the reset it owes out, once");
            assertEquals(1L, holder[0].resets());
            assertEquals(1L, epochs.current(), "and the generation moves once for one request");
            assertFalse(module.drainCommitReset(), "the same request is not carried out a second time");
            assertEquals(1L, holder[0].resets());
            assertEquals(1L, epochs.current());
            assertFalse(module.commitResetPending());
            assertNotNull(log.lastResetTyped(),
                "the clear of the module ended the lifecycle of the log it adopted");
            assertEquals(1, log.lastResetTyped().typedSteps().size(),
                "and folded the line that lifecycle still carried into its terminal projection");
            assertEquals(0L, log.sequence(), "the log of the module is the log of the chain, so it moved");
        } finally {
            module.rollbackCommitChain();
        }
    }

    private static CommitTicket.ReserveRequest request(CommitNamespaces.ResetEpochs epochs,
                                                       CommitReservation reservations) {
        CommitNamespaces.Namespace producer = new CommitNamespaces.Namespace("producer", 4242L, 0L, 77L);
        CommitNamespaces.Namespace controller = reservations.controller();
        CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 11L, 5L, 3L,
            "published", 7L, 9L, producer, controller);
        EffectStore.Effect variant = new EffectStore.Effect(9L, "publish", "PUBLISHED", List.of("a=1"));
        return new CommitTicket.ReserveRequest("world-a", "entity", "a/0", CommitLog.Batch.Kind.APPLY,
            7L, 0, 7L, 4242L, 1, identity, controller, variant, variant, variant);
    }
}
