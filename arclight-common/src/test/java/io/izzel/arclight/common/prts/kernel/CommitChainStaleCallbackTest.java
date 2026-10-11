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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two callbacks that do not land where they were meant to. A callback of a generation that has
 * moved on is refused on every route and its item is carried as one waiting for a line instead of
 * being dropped quietly, and a terminal that cannot be read is carried the same way: neither is
 * disposed of by itself, only by its owner. While such an item waits, the balance reading of the
 * chain still holds and its room is still held, so that reading is never a proof of no leak. */
class CommitChainStaleCallbackTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final String NODE = "a/0";
    private static final long EFFECT = 9L;

    private static final class Chain {

        final EffectStore store = new EffectStore(4096, 8);
        final CommitLog log;
        final CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        final CommitReservation reservations;
        final CommitController controller;

        Chain(CommitLog.OrderSource source) {
            log = new CommitLog(() -> 4, source, store);
            reservations = new CommitReservation(log, store,
                (worldId, domainId, nodeKey) -> new CommitLog.Resolved(7L, 0, false), "owner-a", epochs);
            controller = new CommitController(reservations, null);
        }

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

    private static Chain hostAndInstall() throws IOException {
        return chain(hostAndNameTheOwnerThread(), true);
    }

    /** A chain of the same shape that is never adopted: an item whose window has closed can never be
     * disposed of, and the module refuses to take itself off a chain that still owes a line, so the
     * leg that builds one drives a chain of its own and leaves the module where it found it. */
    private static Chain standalone() throws IOException {
        return chain(hostAndNameTheOwnerThread(), false);
    }

    private static KernelModule hostAndNameTheOwnerThread() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        ExecutableProductFace.declare();
        KernelModule module = KernelModule.instance();
        module.serverTick(List.of(WORLD));
        return module;
    }

    private static Chain chain(KernelModule module, boolean adopt) {
        Chain chain = new Chain(module.commitOrderSource());
        if (adopt) {
            assertTrue(module.installCommitChain(chain.log, chain.controller).bound());
        }
        return chain;
    }

    private static CommitTicket.Outcome published() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1", "b=2"),
            2, "");
    }

    private static CommitTicket.Outcome undeclared() {
        return new CommitTicket.Outcome(null, "publish", List.of("a=1", "b=2"), 2, "");
    }

    private static CommitTicket.Outcome outgrown() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PARTIAL, "publish", List.of("a=1", "b=2"),
            2, "a-note-the-promise-never-covered-".repeat(20));
    }

    @Test
    void aCallbackOfAGenerationThatMovedOnIsRefusedOnEveryRouteAndIsNotDropped() throws IOException {
        Chain c = standalone();
        KernelModule module = KernelModule.instance();
        CommitTicket old = c.reservations.reserve(c.request()).ticket();
        assertTrue(c.reservations.beginPublish(old));
        // The generation moves under a line whose side effect is in flight. The controller refuses a
        // reset while a line publishes, so the holder is asked directly: whatever moved the
        // generation, the three routes below have to refuse and the item has to stay readable.
        c.reservations.onReset();
        c.epochs.advance();
        long sequence = c.log.sequence();
        long taken = c.log.reservationsTaken();

        assertFalse(c.reservations.beginPublish(old), "an old line is not started again");
        CommitReservation.FinalizeResult late = c.reservations.finalize(old, published());

        assertFalse(late.finalized());
        assertEquals("stale-ticket", late.diagnostic());
        assertEquals(1L, c.reservations.staleRefusals());
        assertEquals(1, c.reservations.pendingCount(),
            "the item is carried as one waiting for its line, not dropped");
        assertEquals(1, c.store.capacitySlots(), "its room is still held with it");
        assertEquals("COUNTER_MISSING", c.reservations.pendingDispositions().get(0).code().name(),
            "and what it waits on is readable");
        assertEquals("stale-ticket", c.reservations.pendingDispositions().get(0).diagnostic());
        assertTrue(c.reservations.conservationHolds(),
            "the balance holds while the room is held, so it is no evidence that nothing leaked");
        assertEquals(1, c.reservations.outstanding());
        assertFalse(c.reservations.release(old, CommitTicket.ReleaseReason.CANCELLED).released(),
            "a line whose side effect may have run is never given back");
        assertEquals(0L, c.reservations.finalized(), "and nothing was recorded by the refusals");
        assertEquals(sequence, c.log.sequence(), "no line was written for the old one");
        assertEquals(taken, c.log.reservationsTaken(), "and no place was taken by a refusal");

        long refusals = c.reservations.staleRefusals();
        CommitReservation.FinalizeResult again = c.reservations.finalize(old, published());

        assertFalse(again.finalized(), "the closed window never opens again by itself");
        assertEquals("stale-ticket", again.diagnostic());
        assertEquals(refusals + 1L, c.reservations.staleRefusals(),
            "the third route is refused and counted as well");
        assertEquals(1, c.reservations.pendingDispositions().get(0).attempts(),
            "the item is not publishing any more, so the callback that follows adds no attempt");
        assertEquals(1, c.store.capacitySlots(), "the room of that item is never given back");
        assertEquals("NOT_WIRED", reading("product.commit.controller_wiring"),
            "a chain of this shape is never adopted: nothing of the module was moved by it");
        assertEquals("0", reading("product.commit.pending"));
    }

    @Test
    void anOffOwnerThreadCallbackOfTheCurrentGenerationMovesNoPhase() throws Exception {
        Chain c = hostAndInstall();
        CommitTicket ticket = c.reservations.reserve(c.request()).ticket();
        assertTrue(c.reservations.beginPublish(ticket));
        AtomicReference<String> answer = new AtomicReference<>();
        Thread other = new Thread(() -> answer.set(
            c.reservations.finalize(ticket, published()).diagnostic()));
        other.start();
        other.join();

        assertEquals("off-owner-thread", answer.get(), "the callback is refused where it arrived");
        assertEquals("0", reading("product.commit.stale_refusals"),
            "a callback from another thread is not a stale one");
        assertEquals(1L, c.reservations.offThreadRefusals());
        assertEquals(1L, c.reservations.publishing(), "the phase was not rewritten");
        assertEquals(0L, c.reservations.finalized());
        assertEquals(Thread.currentThread(), c.reservations.ownerThread(), "the owner did not move");
        assertTrue(c.reservations.finalize(ticket, published()).finalized(),
            "and the owner thread writes the line");
    }

    @Test
    void anErrorOfTheOwnersCallbackWaitsForTheOwnerAndIsThenDisposed() throws IOException {
        Chain c = hostAndInstall();
        KernelModule module = KernelModule.instance();
        long closes = module.commitCloseCalls();
        CommitTicket ticket = c.reservations.reserve(c.request()).ticket();
        assertTrue(c.reservations.beginPublish(ticket));

        CommitReservation.FinalizeResult refused = c.reservations.finalize(ticket, outgrown());

        assertFalse(refused.finalized());
        assertEquals("record-refused", refused.diagnostic());
        assertEquals("1:QUEUE_CAP_EXCEEDED:record-refused:1",
            reading("product.commit.pending_dispositions"),
            "what it waits on and how often it was tried are readable");
        assertEquals("0", reading("product.commit.finalized"), "nobody retried it on their own");

        CommitReservation.FinalizeResult unreadable = c.reservations.finalize(ticket, undeclared());

        assertFalse(unreadable.finalized(), "a terminal that names nothing is not a terminal");
        assertEquals("terminal-undeclared", unreadable.diagnostic());
        assertEquals("1:VERSION_MISMATCH:terminal-undeclared:2",
            reading("product.commit.pending_dispositions"));
        assertEquals("0", reading("product.commit.finalized"), "and it is still waiting");
        assertEquals("1", reading("product.commit.effect_capacity_slots"));
        assertEquals("BOUND", reading("product.commit.controller_wiring"),
            "the chain that was adopted is the chain that was driven");
        assertEquals(closes, module.commitCloseCalls(), "and no close was taken on it");

        assertTrue(c.reservations.finalize(ticket, published()).finalized(),
            "the owner hands it the line that closes it");
        assertEquals(1L, c.reservations.finalized());
        assertEquals("0", reading("product.commit.pending"));
        assertEquals("0", reading("product.commit.attention"));
        assertEquals("0", reading("product.commit.effect_capacity_slots"), "and the room comes back");
        assertEquals("PUBLISHED", c.reservations.terminalLedger()
            .get(c.reservations.terminalLedger().size() - 1).state());
    }
}
