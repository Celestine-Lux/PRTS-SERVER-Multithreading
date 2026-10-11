/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitReservation;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        "commit.ticket_stored", "commit.ticket_refused", "commit.hybrid_order_refusals",
        "commit.cross_log_refusals", "commit.stale_ticket_refusals", "commit.integrity_faults",
        "commit.held_plans");

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
        assertEquals("commit.ticket_stored=0", line(export, "commit.ticket_stored"));
        assertEquals("commit.hybrid_order_refusals=0", line(export, "commit.hybrid_order_refusals"));
        assertEquals("commit.held_plans=0", line(export, "commit.held_plans"));
    }

    @Test
    void aResetThatArrivesInsideAPublishActionClearsNothing() {
        System.setProperty("arclight.prts.kernel", "true");
        EffectStore store = new EffectStore(4096, 8);
        CommitLog.OrderSource plans = (worldId, domainId, nodeKey) -> new CommitLog.Resolved(7L, 0, false);
        CommitLog log = new CommitLog(() -> 4, plans, store);
        CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        CommitReservation reservations = new CommitReservation(log, store, plans, "owner-a", epochs);
        final CommitController[] holder = new CommitController[1];
        final String[] inside = new String[1];
        holder[0] = new CommitController(reservations, ticket -> {
            KernelModule.instance().resetReadings();
            inside[0] = holder[0].resetReentrantRefusals() + "/" + holder[0].resets() + "/"
                + epochs.current();
            return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
                List.of("a=1"), 1, "");
        });
        KernelModule.instance().bindCommitController(holder[0]);
        try {
            holder[0].submit(request(epochs, reservations));
            assertEquals("1/0/0", inside[0],
                "the module asked for a reset inside the publish action and nothing was cleared");
            assertEquals(1L, holder[0].resets(), "the deferred reset ran after the terminal line");
            assertEquals(1L, holder[0].deferredResets());
            assertEquals("BOUND", KernelModule.instance().commitControllerWiring());
            assertEquals(1L, holder[0].acceptedPublished());
            assertEquals(1, log.lastResetTyped().typedSteps().size());
        } finally {
            KernelModule.instance().bindCommitController(null);
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
