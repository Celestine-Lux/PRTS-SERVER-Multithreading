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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.izzel.arclight.common.prts.kernel.commit.WitnessSource;

/** The four boundaries the close of a chain leaves open: an item whose line is not written stops the
 * chain from taking new work instead of only being counted, a close seals it with an identity a
 * diagnostic can read back, a reset composed with that close says which of the two it is, and the
 * line is handed to the item by its owner and by no one else. */
class CommitChainCloseoutBoundaryTest {

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

        Chain(CommitLog.OrderSource source, CommitController.Publisher publisher) {
            CommitLog.OrderSource plans =
                (worldId, domainId, nodeKey) -> new CommitLog.Resolved(7L, 0, false);
            log = new CommitLog(() -> 4, source, store,
                new WitnessSource(plans, WORLD, DOMAIN, 3L, "published", 5L, 7L, NODE).bind(11L));
            reservations = new CommitReservation(log, store, plans, "owner-a", epochs);
            controller = new CommitController(reservations, publisher);
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
        module.markSafePoint();
        assertFalse(module.commitResetPending(), "no request outlives the case that made it");
        // The case owns whatever is still sealed: it hands each of those items its line here, which is
        // what its owner would do, and only then is the chain free to go. A rollback while an item is
        // owed is refused on purpose, so this is a disposition and not a way around one.
        for (KernelModule.Disposition item : module.commitChainDispositions()) {
            module.disposeCommitDisposition(item.ticketId(), published());
        }
        assertTrue(module.commitChainDispositions().isEmpty(), "no sealed item outlives its case");
        if ("BOUND".equals(module.commitControllerWiring())) {
            assertTrue(module.rollbackCommitChain(), "and the chain goes once it owes nothing");
        }
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

    private static CommitTicket.Outcome published() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1", "b=2"),
            2, "");
    }

    /** The record the promise of the reservation never covered: the store refuses it and the item is
     * carried as one waiting for its line. */
    private static CommitTicket.Outcome outgrown() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PARTIAL, "publish", List.of("a=1", "b=2"),
            2, "a-note-the-promise-never-covered-".repeat(20));
    }

    private static Chain hostAndInstall(CommitController.Publisher publisher) throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        ExecutableProductFace.declare();
        KernelModule module = KernelModule.instance();
        module.serverTick(List.of(WORLD));
        Chain chain = new Chain(module.commitOrderSource(), publisher);
        KernelModule.CommitBinding binding = module.installCommitChain(chain.log, chain.controller);
        assertTrue(binding.bound(), "the chain is taken: refusal=" + binding.refusal()
            + " wiring=" + module.commitControllerWiring() + " closed="
            + chain.reservations.closed() + " bound=" + module.commitOwnerThreadBound());
        return chain;
    }

    /** One item whose side effect ran and whose line the holder could not write, and which left the
     * chain underneath still taking work: the terminal that cannot be read is a debt of its own, not
     * the fault that stops the room. */
    private static CommitTicket waitingItem(Chain c) {
        CommitTicket ticket = c.reservations.reserve(c.request()).ticket();
        assertTrue(c.reservations.beginPublish(ticket));
        assertFalse(c.reservations.finalize(ticket, unreadable()).finalized(), "the record was refused");
        assertEquals(1, c.reservations.pendingCount());
        assertFalse(c.store.halted(), "and the room underneath is still handing work out");
        return ticket;
    }

    /** A terminal whose rows are not the rows of the reservation: the holder cannot read it, so it
     * carries the item instead of writing a line that says nothing. */
    private static CommitTicket.Outcome unreadable() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1"), 1,
            "not-the-rows-the-promise-covered");
    }

    @Test
    void anOwedDispositionStopsTheChainFromTakingNewWorkAndSaysSo() throws IOException {
        Chain c = hostAndInstall(ticket -> published());
        KernelModule module = KernelModule.instance();
        long refusals = Long.parseLong(reading("product.commit.disposition_gate_refusals"));
        assertTrue(c.controller.acceptingNewWork(), "a chain with nothing owed takes work");

        waitingItem(c);

        assertEquals("pending-dispositions", c.controller.dispositionGateReason());
        assertFalse(c.controller.acceptingNewWork(), "the debt stops the chain before it can grow");
        assertEquals("0", reading("product.commit.accepting_new_work"),
            "and the readout says the chain is taking nothing");
        assertEquals("pending-dispositions", reading("product.commit.disposition_gate_reason"));
        assertEquals("1", reading("product.commit.disposition_gate"),
            "which reads as the gate being shut");
        assertEquals("1", reading("product.commit.attention"), "and the readout raises attention");
        CommitTicket.ReserveRequest next = c.request();
        String diagnostic = c.controller.submit(next).diagnostic();
        assertEquals("pending-dispositions", diagnostic,
            "a request for a new line is refused, not run behind the item that is owed");
        assertEquals(0L, c.controller.supplierCalls(), "nothing was run for it");
        assertEquals(refusals + 1L, Long.parseLong(reading("product.commit.disposition_gate_refusals")),
            "the refusal is counted where it happened");
        assertEquals("1", reading("product.commit.conservation"),
            "and the balance still holds while the room is held: it is not a proof of no leak");
    }

    @Test
    void aCloseSealsAnItemThatWaitsForItsLineWithAnIdentityADiagnosticReadsBack()
        throws IOException {
        Chain c = hostAndInstall(null);
        KernelModule module = KernelModule.instance();
        CommitTicket ticket = waitingItem(c);

        KernelModule.CommitClose close = module.closeCommitChain();

        assertTrue(close.closed());
        assertEquals(1, close.unrecorded(), "the item whose side effect ran is sealed, not cleared");
        assertEquals("1", reading("product.commit.chain_sealed"));
        assertEquals("1", reading("product.commit.chain_sealed_count"));
        assertFalse("0".equals(reading("product.commit.chain_sealed_turn")),
            "the window the seal was taken in is part of it");
        List<KernelModule.Disposition> sealed = module.commitSealedIdentities();
        assertEquals(1, sealed.size());
        assertEquals(ticket.ticketId(), sealed.get(0).ticketId(), "the ticket is carried, whole");
        assertEquals("sealed-at-close", sealed.get(0).step());
        assertEquals(ticket.ticketId() + ":VERSION_MISMATCH:row-count-mismatch",
            reading("product.commit.chain_dispositions"),
            "the identity the chain holds for it is the same one the seal carries");
        assertTrue(reading("product.commit.chain_sealed_identities").startsWith(ticket.ticketId() + ":"),
            "and the readout spells the identity out");
        assertEquals("1", reading("product.commit.pending"), "the item stays readable after the close");
        assertEquals("1", reading("product.commit.effect_capacity_slots"), "with the room it holds");
        assertEquals("0", reading("product.commit.finalized"), "and no line is reported as written");
        assertEquals("closed-with-pending-dispositions", reading("product.commit.reset_composition")
            .split(":")[0], "a reset inside it names what it is waiting for");
    }

    @Test
    void onlyTheOwnerHandsASealedItemItsLineAndNothingElseMovesIt() throws Exception {
        Chain c = hostAndInstall(null);
        KernelModule module = KernelModule.instance();
        CommitTicket ticket = waitingItem(c);
        module.closeCommitChain();
        assertEquals("1", reading("product.commit.chain_sealed"));
        assertEquals("lifecycle-closed", c.controller.dispositionGateReason(),
            "a closed chain takes nothing whether or not an item is owed");

        Thread other = new Thread(() -> c.reservations.finalize(ticket, published()));
        other.start();
        other.join();

        assertEquals("1", reading("product.commit.pending"), "a line from another thread moves nothing");
        assertEquals("1", reading("product.commit.chain_sealed"));
        assertEquals(1, c.reservations.pendingDispositions().get(0).attempts(),
            "and is not carried as an attempt of the item");

        KernelModule.Disposition disposed = module.disposeCommitDisposition(ticket.ticketId(), published());

        assertTrue(disposed.finalized(), "the owner hands the item its line");
        assertEquals("disposed-by-owner", disposed.step());
        assertFalse(disposed.sealed(), "and the seal is over with it");
        assertEquals("0", reading("product.commit.chain_sealed"));
        assertEquals("0", reading("product.commit.pending"));
        assertEquals("0", reading("product.commit.effect_capacity_slots"), "the room comes back");
        assertEquals("1", reading("product.commit.chain_conservation"));
        assertEquals("1", reading("product.commit.finalized"), "the line is written exactly once");
        assertEquals("0", reading("product.commit.disposition_gate_refusals"),
            "and nothing was retried on its own to get there");
    }

    @Test
    void aResetInsideAClosedChainSaysWhichOfTheTwoItIs() throws IOException {
        Chain c = hostAndInstall(null);
        KernelModule module = KernelModule.instance();
        CommitTicket ticket = waitingItem(c);
        CommitTicket.ReserveRequest free = c.request();
        assertFalse(c.controller.submit(free).admitted(),
            "the chain refuses new work while the item waits for its line");
        module.closeCommitChain();

        KernelModule.ResetComposition owed = module.describeResetComposition();
        assertTrue(owed.chainClosed(), "the chain is closed after the close");
        assertFalse(owed.acceptsWork(), "and takes nothing new");
        assertEquals(1, owed.dispositionOwed(), "with one item still waiting for its line");
        assertFalse(owed.clearAllowed(), "so no safe point clears it");
        assertEquals("closed-with-pending-dispositions", owed.blockedBy());
        assertEquals("lifecycle-closed", module.requestReadingsReset("host:tick-boundary").reason());
        assertEquals("1", reading("product.commit.chain_sealed_count"),
            "a refused reset seals nothing new and releases nothing");
        assertEquals("1", reading("product.commit.pending"), "and takes nothing away");

        assertTrue(module.disposeCommitDisposition(ticket.ticketId(), published()).finalized());
        KernelModule.ResetComposition clean = module.describeResetComposition();

        assertTrue(clean.chainClosed(), "the chain is still closed");
        assertEquals(0, clean.dispositionOwed(), "and owes nothing now");
        assertFalse(clean.clearAllowed(), "a close is terminal: it starts no generation after it");
        assertEquals("lifecycle-closed", clean.blockedBy());
        assertTrue(module.rollbackCommitChain(),
            "and a chain that owes nothing can be taken off: a closed chain is not a hidden one");
    }
}
