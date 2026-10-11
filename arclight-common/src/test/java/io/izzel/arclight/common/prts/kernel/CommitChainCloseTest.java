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

/** A close ends the chain the module holds and nothing else: it gives back only the reservations
 * that never started, it keeps every item whose side effect may have run readable with its room, it
 * reports no line as written, and it is idempotent. The three states a close can meet - nothing
 * started, a line publishing, an item waiting for its line - are asserted one by one. */
class CommitChainCloseTest {

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

    /** The close counters of the module outlive one window on purpose, so a leg reads what it
     * moved instead of a value the legs before it have already added up. */
    private static long counter(String key) {
        return Long.parseLong(reading(key));
    }

    private static Chain hostAndInstall() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        ExecutableProductFace.declare();
        KernelModule module = KernelModule.instance();
        module.serverTick(List.of(WORLD));
        Chain chain = new Chain(module.commitOrderSource());
        assertTrue(module.installCommitChain(chain.log, chain.controller).bound());
        return chain;
    }

    private static CommitTicket.Outcome published() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1", "b=2"),
            2, "");
    }

    /** The record the promise of the reservation never covered: the store refuses it and stops
     * handing out room, and the item is carried as one waiting for its line. */
    private static CommitTicket.Outcome outgrown() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PARTIAL, "publish", List.of("a=1", "b=2"),
            2, "a-note-the-promise-never-covered-".repeat(20));
    }

    @Test
    void closingGivesBackWhatNeverStartedAndKeepsNothingElse() throws IOException {
        Chain c = hostAndInstall();
        KernelModule module = KernelModule.instance();
        c.reservations.reserve(c.request());
        assertEquals("1", reading("product.commit.effect_capacity_slots"), "the room is held");
        long calls = counter("product.commit.close_calls");

        KernelModule.CommitClose close = module.closeCommitChain();

        assertTrue(close.closed());
        assertEquals("", close.refusal());
        assertEquals(1, close.givenBack(), "the reservation that never started goes back");
        assertEquals(0, close.unrecorded());
        assertEquals(0, close.publishing());
        assertEquals("1", reading("product.commit.closed"));
        assertEquals(calls + 1L, counter("product.commit.close_calls"), "the close was taken once");
        assertEquals("1", reading("product.commit.close_given_back"));
        assertEquals("0", reading("product.commit.effect_capacity_slots"), "and its room comes back");
        assertEquals("0", reading("product.commit.chain_in_flight"));
        assertEquals("1", reading("product.commit.chain_released"));
        assertEquals("1", reading("product.commit.chain_conservation"));
        assertEquals("CLOSED", c.reservations.terminalLedger().get(0).state(),
            "the line it writes is the one that says the reservation went back");
        assertEquals("0", reading("product.commit.finalized"), "no line is reported as written");
        assertEquals("0", reading("product.commit.published_total"));
    }

    @Test
    void aSecondCloseGivesBackNothingAndMovesNoAccount() throws IOException {
        Chain c = hostAndInstall();
        KernelModule module = KernelModule.instance();
        c.reservations.reserve(c.request());
        long calls = counter("product.commit.close_calls");
        module.closeCommitChain();
        int entries = c.reservations.terminalLedger().size();
        long sequence = c.log.sequence();

        KernelModule.CommitClose again = module.closeCommitChain();

        assertTrue(again.closed(), "a close over a closed chain is still a closed chain");
        assertEquals(0, again.givenBack(), "and it gives back nothing");
        assertEquals(0, again.unrecorded());
        assertEquals(0, again.publishing());
        assertEquals(calls + 2L, counter("product.commit.close_calls"), "it was asked twice");
        assertEquals("0", reading("product.commit.close_given_back"), "the newest call gave back zero");
        assertEquals(entries, c.reservations.terminalLedger().size(), "no new line was written");
        assertEquals(sequence, c.log.sequence());
        assertEquals("1", reading("product.commit.chain_released"), "the account did not move");
        assertEquals("0", reading("product.commit.chain_in_flight"));
        assertEquals("1", reading("product.commit.chain_conservation"));
    }

    @Test
    void closingLeavesAPublishingLineAndItsRoomAlone() throws IOException {
        Chain c = hostAndInstall();
        KernelModule module = KernelModule.instance();
        CommitTicket ticket = c.reservations.reserve(c.request()).ticket();
        assertTrue(c.reservations.beginPublish(ticket));

        KernelModule.CommitClose close = module.closeCommitChain();

        assertTrue(close.closed());
        assertEquals(0, close.givenBack(), "a line whose side effect may be running is not given back");
        assertEquals(1, close.publishing());
        assertEquals(0, close.unrecorded());
        assertEquals("1", reading("product.commit.publishing"));
        assertEquals("1", reading("product.commit.effect_capacity_slots"), "its room is kept with it");
        assertEquals("1", reading("product.commit.chain_in_flight"));
        assertTrue(c.reservations.finalize(ticket, published()).finalized(),
            "the line it owes is still written after the close");
        assertEquals("1", reading("product.commit.finalized"));
        assertEquals("0", reading("product.commit.chain_in_flight"));
        assertEquals("0", reading("product.commit.effect_capacity_slots"));
        assertEquals("1", reading("product.commit.chain_conservation"));
    }

    @Test
    void closingKeepsAnItemThatWaitsForItsLineReadableWithItsRoom() throws IOException {
        Chain c = hostAndInstall();
        KernelModule module = KernelModule.instance();
        CommitTicket ticket = c.reservations.reserve(c.request()).ticket();
        assertTrue(c.reservations.beginPublish(ticket));
        assertFalse(c.reservations.finalize(ticket, outgrown()).finalized());
        assertEquals("1", reading("product.commit.pending"));
        assertEquals("1", reading("product.commit.attention"));
        int entries = c.reservations.terminalLedger().size();

        KernelModule.CommitClose close = module.closeCommitChain();

        assertTrue(close.closed());
        assertEquals(0, close.givenBack());
        assertEquals(1, close.unrecorded(), "the item whose side effect ran is not thrown away");
        assertEquals(0, close.publishing(), "and it is not a publish still in flight any more");
        assertEquals("1", reading("product.commit.close_unrecorded"));
        assertEquals("1", reading("product.commit.pending"), "it stays readable after the close");
        assertEquals("1", reading("product.commit.attention"));
        assertEquals("1", reading("product.commit.effect_capacity_slots"), "with its room");
        assertEquals(entries, c.reservations.terminalLedger().size(),
            "the close writes no line of its own over an item that waits for one");
        assertEquals("1", reading("product.commit.conservation"),
            "and the balance still holds while the room is held: it is not a proof of no leak");
        assertEquals("1", reading("product.commit.chain_in_flight"));

        assertTrue(c.reservations.finalize(ticket, published()).finalized(),
            "the owner hands the waiting item its line, and no one else does");
        assertEquals("0", reading("product.commit.pending"));
        assertEquals("0", reading("product.commit.attention"));
        assertEquals("0", reading("product.commit.effect_capacity_slots"), "the room comes back");
        assertEquals("1", reading("product.commit.chain_conservation"));
    }

    @Test
    void aCloseWithNothingBoundClosesNoChainAndRefusesWhole() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        ExecutableProductFace.declare();
        KernelModule module = KernelModule.instance();
        module.serverTick(List.of(WORLD));
        long calls = counter("product.commit.close_calls");

        KernelModule.CommitClose close = module.closeCommitChain();

        assertFalse(close.closed(), "there is no chain to close");
        assertEquals("not-wired", close.refusal());
        assertEquals(0, close.givenBack());
        assertEquals(calls, counter("product.commit.close_calls"), "and no close was taken");
        assertEquals("0", reading("product.commit.closed"));
        module.serverTick(List.of(WORLD));
        assertFalse("0".equals(reading("kernel.tick_index")));
        module.resetReadings();
        assertEquals("0", reading("kernel.tick_index"),
            "the module clears its own window exactly as it always did");
    }
}
