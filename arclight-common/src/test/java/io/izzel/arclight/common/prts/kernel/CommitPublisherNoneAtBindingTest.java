/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.CommitReservation;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import io.izzel.arclight.common.prts.kernel.config.ExecutableProductFace;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.izzel.arclight.common.prts.kernel.commit.WitnessSource;

/** A build whose controller carries no publish action refuses the request where it is asked, and it
 * does so in all three states of the binding: before a chain is installed, after one is installed,
 * and after an install was refused. Nothing a request could move moves in any of them, and the
 * module reads the refusal through the log it holds in each state. */
class CommitPublisherNoneAtBindingTest {

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
            new WitnessSource(plans, WORLD, DOMAIN, 3L, "published", 5L, 7L, NODE).bind(11L));
        final CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        final CommitReservation reservations =
            new CommitReservation(log, store, plans, "owner-a", epochs);
        final CommitController controller = new CommitController(reservations, null);

        CommitTicket.ReserveRequest request() {
            CommitNamespaces.Namespace producer =
                new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L);
            CommitNamespaces.Namespace namespace = reservations.controller();
            CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 11L, 5L, 3L,
                "published", 7L, EFFECT, producer, namespace);
            EffectStore.Effect variant =
                new EffectStore.Effect(EFFECT, "publish", "PUBLISHED", List.of("a=1", "b=2"));
            return new CommitTicket.ReserveRequest(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY,
                7L, 0, 7L, 4242L, 2, identity, namespace, variant, variant, variant);
        }
    }

    @BeforeEach
    void switchTheKernelOnAndNameTheOwnerThread() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        ExecutableProductFace.declare();
        KernelModule.instance().serverTick(List.of(WORLD));
    }

    @AfterEach
    void leaveTheModuleAsItWas() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        KernelModule module = KernelModule.instance();
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.resetReadings();
        module.rollbackCommitChain();
        ExecutableProductFace.restore();
    }

    private static String reading(String key) {
        for (String row : KernelReadings.export(KernelModule.instance())) {
            if (row.startsWith(key + "=")) {
                return row.substring(key.length() + 1);
            }
        }
        return "<missing>";
    }

    /** The 15 columns a request could move. The refusal count is read apart from them. */
    private static String ledger(Chain c) {
        return "controller[submits=" + c.controller.submits() + " supplierCalls="
            + c.controller.supplierCalls() + " publishFailures=" + c.controller.publishFailures()
            + " acceptedPublished=" + c.controller.acceptedPublished() + " refusals="
            + c.controller.refusals() + " refusedStarts=" + c.controller.refusedStarts() + "]"
            + " reservations[reserveSuccess=" + c.reservations.reserveSuccess() + " finalized="
            + c.reservations.finalized() + " released=" + c.reservations.releasedCount()
            + " outstanding=" + c.reservations.outstanding() + " publishing="
            + c.reservations.publishing() + " pending=" + c.reservations.pendingCount() + " ledger="
            + c.reservations.terminalLedger().size() + " failed=" + c.reservations.failedTotal()
            + " published=" + c.reservations.publishedTotal() + " conservation="
            + c.reservations.conservationHolds() + "]"
            + " log[sequence=" + c.log.sequence() + " dropped=" + c.log.dropped() + " ticketStored="
            + c.log.ticketStored() + " ringDepth=" + c.log.ringDepth() + " taken="
            + c.log.reservationsTaken() + " consumed=" + c.log.reservationsConsumed()
            + " released=" + c.log.reservationsReleased() + " heldPlans=" + c.log.heldPlans() + "]"
            + " store[capacitySlots=" + c.store.capacitySlots() + " capacityReserved="
            + c.store.capacityReserved() + " capacityFinalized=" + c.store.capacityFinalized()
            + " capacityReleased=" + c.store.capacityReleased() + " records=" + c.store.records()
            + "]"
            + " epoch=" + c.epochs.current();
    }

    /** One refused request, read the same way in all three states of the binding. */
    private static void assertRefusedAndNothingMoved(Chain c, String expectedWiring,
                                                     String expectedRefusals) {
        String before = ledger(c);
        CommitController.SubmitResult result = c.controller.submit(c.request());

        assertFalse(result.admitted(), "nothing ran, and the result says so");
        assertNull(result.terminal(), "an action that does not exist reports no terminal");
        assertEquals(RejectCode.NATIVE_UNDECLARED, result.code());
        assertEquals("not-wired", result.diagnostic());
        assertNull(result.ticket(), "no ticket is minted");
        assertEquals(0, result.successBatch());
        assertEquals(0L, result.unitCount());
        assertEquals(before, ledger(c), "every account of the chain is where it was");
        assertEquals(1L, c.controller.notWiredRefusals(), "the refusal is the only thing it moved");
        assertEquals("0", reading("product.commit.supplier_calls"), "the action was never called");
        assertEquals("0", reading("product.commit.submits"));
        assertEquals("0", reading("product.commit.reserve_success"));
        assertEquals("0", reading("product.commit.finalized"));
        assertEquals("0", reading("product.commit.failed_total"));
        assertEquals("0", reading("product.commit.terminal_ledger_entries"));
        assertEquals("-", reading("product.commit.terminal_ledger"));
        assertEquals("0", reading("product.commit.effect_capacity_slots"));
        assertEquals(expectedWiring, reading("product.commit.controller_wiring"));
        assertEquals("NONE", reading("product.commit.controller_publisher"));
        assertEquals("0", reading("product.commit.controller_executable"));
        assertEquals(expectedRefusals, reading("product.commit.not_wired_refusals"));
        assertEquals("0", reading("commit.entries"), "the log of the module took no line either");
        assertEquals("0", reading("commit.dropped"));
        assertEquals("0", reading("commit.ticket_stored"));
        assertEquals("0", reading("commit.held_plans"));
    }

    @Test
    void beforeAnInstallTheRequestIsRefusedAndNothingMoves() {
        Chain c = new Chain();

        assertRefusedAndNothingMoved(c, "NOT_WIRED", "0");
        assertEquals("-", reading("product.commit.binding_refusal"));

        assertSame(c.log, c.reservations.log(), "the chain is whole on its own all along");
        assertFalse(c.module.commitChainIsTheOneLog(),
            "and the module is on the log it built for itself until a chain is taken");
    }

    @Test
    void afterAnInstallTheRequestIsRefusedAndNothingMoves() {
        Chain c = new Chain();
        assertTrue(c.module.installCommitChain(c.log, c.controller).bound());

        assertRefusedAndNothingMoved(c, "BOUND", "1");
        assertEquals("1", reading("product.commit.binding_chain_log"),
            "the module now watches the log of the chain, and that log took nothing");
    }

    @Test
    void afterARefusedInstallTheRequestIsRefusedAndNothingMoves() {
        Chain c = new Chain();
        Chain other = new Chain();
        CommitLog own = c.module.commits();

        KernelModule.CommitBinding refused = c.module.installCommitChain(other.log, c.controller);

        assertFalse(refused.bound(), "the chain the holder mints in is not the chain that was named");
        assertEquals("chain-log-mismatch", refused.refusal());
        assertSame(own, c.module.commits(), "so the module stays on the log it built for itself");
        assertNull(c.module.commitController());

        assertRefusedAndNothingMoved(c, "NOT_WIRED", "0");
        assertEquals("chain-log-mismatch", reading("product.commit.binding_refusal"),
            "and the refused install is readable where it was refused");
        assertEquals("0", reading("product.commit.binding_chain_log"));
    }
}
