/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who carries out a reset of the commit lifecycle and how often: the module owns the whole request
 * and runs it at one safe point, the controller only decides whether that point may be entered, and
 * a request that was refused waits instead of being run in the middle of the work that refused it. */
class KernelModuleResetOwnerTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final String NODE = "a/0";
    private static final long EFFECT = 9L;

    private static final class Plans implements CommitLog.OrderSource {

        long plan = 7L;
        int position;

        @Override
        public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
            return WORLD.equals(worldId) && DOMAIN.equals(domainId) && NODE.equals(nodeKey)
                ? new CommitLog.Resolved(plan, position, false) : null;
        }
    }

    private static final class Chain {

        final KernelModule module = KernelModule.instance();
        final Plans plans = new Plans();
        final EffectStore store = new EffectStore(65536, 64);
        final CommitLog log = new CommitLog(() -> 8, module.commitOrderSource(), store);
        final CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        final CommitReservation reservations =
            new CommitReservation(log, store, plans, "owner-a", epochs);
        final CommitController controller;

        Chain(CommitController.Publisher publisher) {
            controller = new CommitController(reservations, publisher);
            assertTrue(module.installCommitChain(log, controller).bound(),
                "the whole chain is taken or none of it is");
        }

        CommitTicket.ReserveRequest request(long instance) {
            CommitNamespaces.Namespace producer =
                new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L);
            CommitNamespaces.Namespace controller = reservations.controller();
            CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instance,
                5L, 3L, "published", plans.plan, EFFECT, producer, controller);
            EffectStore.Effect variant =
                new EffectStore.Effect(EFFECT, "publish", "PUBLISHED", List.of("a=1", "b=2"));
            return new CommitTicket.ReserveRequest(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY,
                plans.plan, plans.position, plans.plan, 4242L, 2, identity, controller, variant,
                variant, variant);
        }
    }

    private static CommitTicket.Outcome published() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
            List.of("a=1", "b=2"), 2, "");
    }

    private static String reading(KernelModule module, String key) {
        for (String row : KernelReadings.export(module)) {
            if (row.startsWith(key + "=")) {
                return row.substring(key.length() + 1);
            }
        }
        return "<missing>";
    }

    @BeforeEach
    void switchTheKernelOnAndNameTheOwnerThread() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        // The declared face is written for the length of one test, and one tick is what names the
        // thread a chain is installed on: the install is owner-only and names no thread of its own.
        ExecutableProductFace.declare();
        KernelModule.instance().serverTick(List.of(WORLD));
    }

    @AfterEach
    void leaveTheModuleAsItWas() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        KernelModule module = KernelModule.instance();
        // A driven tick hands its observation seams out; the module is a singleton, so they are handed
        // back here rather than left to the test that runs next.
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.markSafePoint();
        assertFalse(module.commitResetPending(), "no request outlives the case that made it");
        module.rollbackCommitChain();
        ExecutableProductFace.restore();
    }

    @Test
    void oneRequestIsOneDecisionAndIsCarriedOutOnce() {
        Chain c = new Chain(ticket -> published());
        c.module.serverTick(List.of(WORLD));
        assertTrue(c.module.tickIndex() > 0L, "a driven tick moves the clock of the module");

        c.module.resetReadings();

        assertEquals(0L, c.module.tickIndex(), "the whole clear ran, and it ran here");
        assertEquals(1L, c.controller.resets(), "one request, one decision");
        assertEquals(1L, c.epochs.current(), "and one generation");
        assertFalse(c.module.commitResetPending(), "the module owes nothing after it");
        assertFalse(c.module.drainCommitReset(), "so the same request is not carried out twice");
        assertEquals(1L, c.controller.resets());
        assertEquals(1L, c.epochs.current());

        c.module.serverTick(List.of(WORLD));
        c.module.resetReadings();
        assertTrue(c.module.tickIndex() == 0L, "a second request is carried out the same way");
        assertEquals(2L, c.controller.resets(), "and it is a second decision");
        assertEquals(2L, c.epochs.current(), "of a second generation");

        assertNotNull(c.log.lastResetTyped(),
            "the log this chain publishes into is the log the module adopted: the clear of the "
                + "module ends that lifecycle and leaves its projection here");
        assertEquals(0, c.log.lastResetTyped().typedSteps().size(),
            "and no line was ever written into it, so the projection is empty");
        assertEquals(0L, c.log.sequence(), "the sequence of the adopted log moved with the clear");
    }

    @Test
    void aRequestInsideAPublishActionIsNotRunByTheSubmitThatRefusedIt() {
        KernelModule module = KernelModule.instance();
        AtomicReference<String> inside = new AtomicReference<>();
        Chain[] holder = new Chain[1];
        holder[0] = new Chain(ticket -> {
            module.resetReadings();
            inside.set(module.commitResetPending() + "/" + holder[0].controller.resets() + "/"
                + holder[0].epochs.current());
            return published();
        });
        Chain c = holder[0];

        CommitController.SubmitResult result = c.controller.submit(c.request(11L));

        assertEquals("true/0/0", inside.get(),
            "nothing was cleared while the action ran, and the module knows what it owes");
        assertTrue(result.admitted());
        assertEquals(CommitTicket.Terminal.PUBLISHED, result.terminal());
        assertEquals(0L, c.controller.resets(), "the request is not carried out on the way back");
        assertEquals(0L, c.epochs.current());
        assertEquals(1L, c.controller.deferredResets(), "it is counted where it was refused");
        assertEquals(1L, c.controller.resetReentrantRefusals());
        assertEquals(1L, c.log.sequence(), "the line of the action is where it wrote it");
        assertEquals(1, c.log.ringDepth());
        assertEquals("1", reading(module, "product.commit.controller_reset_pending"),
            "the module owes the whole reset and the readout says so");

        assertTrue(module.drainCommitReset(), "the module carries it out at its next safe point");
        assertEquals(1L, c.controller.resets());
        assertEquals(1L, c.epochs.current());
        assertEquals(0L, c.log.sequence(),
            "the line of the chain was written into the log the module adopted, and the clear of "
                + "the module ended that lifecycle");
        assertNotNull(c.log.lastResetTyped(),
            "so the line is folded into the terminal projection of that lifecycle");
        assertEquals(1, c.log.lastResetTyped().typedSteps().size(),
            "exactly the one line the action wrote");
    }

    @Test
    void aRequestWhileALinePublishesWaitsForThatLine() {
        KernelModule module = KernelModule.instance();
        Chain c = new Chain(ticket -> published());
        CommitTicket ticket = c.reservations.reserve(c.request(11L)).ticket();
        assertTrue(c.reservations.beginPublish(ticket), "a line is in flight");

        module.resetReadings();

        assertEquals(1, c.reservations.publishing());
        assertEquals(0L, c.controller.resets(), "a reset during a publish is not carried out");
        assertEquals(0L, c.epochs.current());
        assertEquals(1L, c.controller.resetWhilePublishing());
        assertTrue(module.commitResetPending());
        assertEquals("1", reading(module, "product.commit.controller_reset_pending"),
            "the debt is the module's, and the controller column reads the same one");
        assertEquals(1, c.log.reservedSlots(), "the slot of the line in flight is still held");
        assertEquals(1, c.store.capacitySlots(), "and so is its room");

        assertTrue(c.reservations.finalize(ticket, published()).finalized(),
            "the terminal line of the item in flight is written first");
        assertEquals(1L, c.log.sequence(), "the terminal line was written before the reset ran");
        assertTrue(module.drainCommitReset(), "and then the request the module owes runs");
        assertEquals(1L, c.controller.resets());
        assertEquals(1L, c.epochs.current());
        assertEquals(0L, c.log.sequence(), "the clear of the module ended the lifecycle of its log");
        assertNotNull(c.log.lastResetTyped());
        assertEquals(1, c.log.lastResetTyped().typedSteps().size(),
            "and the line it still carried is the projection it left behind");
    }

    @Test
    void aRequestWithAWaitingItemIsRefusedUntilItsOwnerHandsItItsLine() {
        KernelModule module = KernelModule.instance();
        Chain c = new Chain(ticket -> published());
        CommitTicket ticket = c.reservations.reserve(c.request(11L)).ticket();
        assertTrue(c.reservations.beginPublish(ticket));
        CommitTicket.Outcome grown = new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED,
            "publish", List.of("a=1", "b=2"), 2, "a-note-the-promise-never-covered-".repeat(20));
        assertFalse(c.reservations.finalize(ticket, grown).finalized(), "the record was refused");
        assertEquals(1, c.reservations.pendingCount());

        module.resetReadings();

        assertEquals(0L, c.controller.resets(),
            "the window of the waiting item is not closed over an item nobody can write any more");
        assertEquals(0L, c.epochs.current());
        assertEquals(1L, c.controller.resetPendingDispositions());
        assertTrue(module.commitResetPending(), "the module holds the request until it can run it");
        assertEquals(1, c.reservations.pendingCount(), "the item is still readable");
        assertEquals(1, c.store.capacitySlots(), "and still holds the room it was promised");
        assertEquals("1", reading(module, "product.commit.attention"), "the readout raises attention");
        assertEquals("1", reading(module, "product.commit.pending"));
        assertEquals("1", reading(module, "product.commit.reset_pending_dispositions"));
        assertEquals("1", reading(module, "product.commit.conservation"),
            "and conservation on its own does not say the room came back");
        assertFalse(module.rollbackCommitChain(),
            "and a rollback does not take the module off a chain that still owes a line");
        assertEquals("rollback-pending-dispositions", reading(module, "product.commit.binding_refusal"));

        assertTrue(c.reservations.finalize(ticket, published()).finalized(),
            "the owner hands the waiting item its line");
        assertEquals(0, c.reservations.pendingCount());
        assertEquals(0, c.store.capacitySlots(), "and the room comes back with it");
        assertEquals("0", reading(module, "product.commit.attention"), "the alert goes down again");

        assertTrue(module.drainCommitReset(), "only now is the request carried out");
        assertEquals(1L, c.controller.resets());
        assertEquals(1L, c.epochs.current());
        assertFalse(module.commitResetPending());
    }
}
