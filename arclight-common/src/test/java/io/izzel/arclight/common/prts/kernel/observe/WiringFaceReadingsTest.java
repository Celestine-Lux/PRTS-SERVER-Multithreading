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
import io.izzel.arclight.common.prts.kernel.commit.WitnessSource;

/** The columns that say whether a bound controller can run anything, what it refused for that
 * reason, and whether an item is waiting for a line its owner has to hand it. */
class WiringFaceReadingsTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final String NODE = "a/0";
    private static final long EFFECT = 9L;

    private static final class Plans implements CommitLog.OrderSource {

        @Override
        public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
            return WORLD.equals(worldId) && DOMAIN.equals(domainId) && NODE.equals(nodeKey)
                ? new CommitLog.Resolved(7L, 0, false) : null;
        }
    }

    private static final class Chain {

        final KernelModule module = KernelModule.instance();
        final Plans plans = new Plans();
        final EffectStore store = new EffectStore(4096, 8);
        final CommitLog log = new CommitLog(() -> 4, module.commitOrderSource(), store,
            new WitnessSource(plans, WORLD, DOMAIN, 3L, "published", 5L, 7L, NODE));
        final CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        final CommitReservation reservations =
            new CommitReservation(log, store, plans, "owner-a", epochs);
        final CommitController controller;

        Chain(CommitController.Publisher publisher) {
            controller = new CommitController(reservations, publisher);
        }

        CommitTicket.ReserveRequest request() {
            CommitNamespaces.Namespace producer =
                new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L);
            CommitNamespaces.Namespace controller = reservations.controller();
            CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 11L, 5L, 3L,
                "published", 7L, EFFECT, producer, controller);
            EffectStore.Effect variant =
                new EffectStore.Effect(EFFECT, "publish", "PUBLISHED", List.of("a=1", "b=2"));
            return new CommitTicket.ReserveRequest(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY,
                7L, 0, 7L, 4242L, 2, identity, controller, variant, variant, variant);
        }
    }

    /** Names the owner thread the way a host does and installs the chain of the fixture. */
    private static void hostAndInstall(Chain c) throws IOException {
        ExecutableProductFace.declare();
        c.module.serverTick(List.of(WORLD));
        assertTrue(c.module.installCommitChain(c.log, c.controller).bound(),
            "the whole chain is taken or none of it is");
    }

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
    void withNothingBoundEveryWiringColumnReadsZeroOrAWire() {
        System.setProperty("arclight.prts.kernel", "true");
        List<String> export = export();

        assertEquals("product.commit.controller_wiring=NOT_WIRED",
            line(export, "product.commit.controller_wiring"));
        assertEquals("product.commit.controller_publisher=NONE",
            line(export, "product.commit.controller_publisher"));
        assertEquals("product.commit.controller_executable=0",
            line(export, "product.commit.controller_executable"));
        assertEquals("product.commit.controller_reset_pending=0",
            line(export, "product.commit.controller_reset_pending"));
        assertEquals("product.commit.not_wired_refusals=0",
            line(export, "product.commit.not_wired_refusals"));
        assertEquals("product.commit.reset_pending_dispositions=0",
            line(export, "product.commit.reset_pending_dispositions"));
        assertEquals("product.commit.pending=0", line(export, "product.commit.pending"));
        assertEquals("product.commit.attention=0", line(export, "product.commit.attention"));
        assertEquals("product.commit.conservation=1", line(export, "product.commit.conservation"));
        assertEquals("product.commit.binding_refusal=-", line(export, "product.commit.binding_refusal"),
            "a module that installed nothing refuses nothing");
        assertEquals("product.commit.binding_log_lifetime=0",
            line(export, "product.commit.binding_log_lifetime"));
        assertEquals("product.commit.binding_store_id=0",
            line(export, "product.commit.binding_store_id"));
        assertEquals("product.commit.binding_chain_log=0",
            line(export, "product.commit.binding_chain_log"));
        assertEquals("product.commit.binding_order_source=0",
            line(export, "product.commit.binding_order_source"));
    }

    @Test
    void aBoundControllerWithoutAnActionReadsAsBoundAndNotExecutable() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        Chain c = new Chain(null);
        hostAndInstall(c);
        try {
            List<String> before = export();
            assertEquals("product.commit.controller_wiring=BOUND",
                line(before, "product.commit.controller_wiring"));
            assertEquals("product.commit.controller_publisher=NONE",
                line(before, "product.commit.controller_publisher"));
            assertEquals("product.commit.controller_executable=0",
                line(before, "product.commit.controller_executable"),
                "being bound and being able to run are two columns");
            assertEquals("product.commit.binding_chain_log=1",
                line(before, "product.commit.binding_chain_log"),
                "the log of the module is the log of the chain it took");
            assertEquals("product.commit.binding_order_source=1",
                line(before, "product.commit.binding_order_source"),
                "and its plan authority is the one instance of the module");

            assertFalse(c.controller.submit(c.request()).admitted());

            List<String> after = export();
            assertEquals("product.commit.not_wired_refusals=1",
                line(after, "product.commit.not_wired_refusals"));
            assertEquals("product.commit.controller_executable=0",
                line(after, "product.commit.controller_executable"));
            assertEquals("product.commit.supplier_calls=0", line(after, "product.commit.supplier_calls"));
            assertEquals("product.commit.submits=0", line(after, "product.commit.submits"));
            assertEquals("product.commit.reserve_success=0", line(after, "product.commit.reserve_success"));
            assertEquals("product.commit.finalized=0", line(after, "product.commit.finalized"));
            assertEquals("product.commit.failed_total=0", line(after, "product.commit.failed_total"));
            assertEquals("product.commit.terminal_ledger_entries=0",
                line(after, "product.commit.terminal_ledger_entries"));
            assertEquals("product.commit.terminal_ledger=-", line(after, "product.commit.terminal_ledger"));
            assertEquals("commit.entries=0", line(after, "commit.entries"),
                "the log of the module took no line either");
            assertEquals("commit.dropped=0", line(after, "commit.dropped"));
            assertEquals("product.commit.effect_capacity_slots=0",
                line(after, "product.commit.effect_capacity_slots"));
        } finally {
            c.module.rollbackCommitChain();
        }
    }

    @Test
    void aBoundControllerWithAnActionReadsAsExecutable() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        Chain c = new Chain(ticket -> new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED,
            "publish", List.of("a=1", "b=2"), 2, ""));
        hostAndInstall(c);
        try {
            assertTrue(c.controller.submit(c.request()).admitted());
            List<String> export = export();
            assertEquals("product.commit.controller_executable=1",
                line(export, "product.commit.controller_executable"));
            assertEquals("product.commit.controller_publisher=SYNTHETIC",
                line(export, "product.commit.controller_publisher"),
                "the action of this build is the synthetic one and says so");
            assertEquals("product.commit.not_wired_refusals=0",
                line(export, "product.commit.not_wired_refusals"));
            assertEquals("product.commit.supplier_calls=1", line(export, "product.commit.supplier_calls"));
        } finally {
            c.module.rollbackCommitChain();
        }
    }
}
