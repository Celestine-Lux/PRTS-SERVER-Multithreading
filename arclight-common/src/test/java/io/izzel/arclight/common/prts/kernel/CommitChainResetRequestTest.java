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
import io.izzel.arclight.common.prts.kernel.commit.WitnessSource;

/** A whole reset is asked for through the one request entry of the module and is answered at the
 * owner safe point: on the thread the host named it is carried out there, and from any other thread
 * it is handed to that safe point instead of clearing from a thread the chain does not own. Every
 * route ends in the same single clear, so a debt is never carried out twice, and a chain that has
 * been closed starts no generation after it. */
class CommitChainResetRequestTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final String NODE = "a/0";
    private static final long EFFECT = 9L;

    /** The chain a caller builds before it asks the module to adopt it. */
    private static final class Chain {

        final EffectStore store = new EffectStore(4096, 8);
        final CommitLog log;
        final CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        final CommitReservation reservations;
        final CommitController controller;

        Chain(CommitLog.OrderSource source) {
            CommitLog.OrderSource plans =
                (worldId, domainId, nodeKey) -> new CommitLog.Resolved(7L, 0, false);
            log = new CommitLog(() -> 4, source, store,
                new WitnessSource(plans, WORLD, DOMAIN, 3L, "published", 5L, 7L, NODE).bind(11L));
            reservations = new CommitReservation(log, store, plans, "owner-a", epochs);
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
        // A request another thread left in the queue is answered here, before the chain goes away. The
        // queue is state of the module, so a case that leaves one in it would otherwise be answered by
        // the first safe point of the case after it, on a chain that never asked for it. The safe point
        // is the only thing that answers one: the module is not cleared first, or it would be a fresh
        // request of this very call that the next case then reads.
        module.markSafePoint();
        assertFalse(module.commitResetPending(), "no request outlives the case that made it");
        assertTrue(module.resetQueueSources().isEmpty(), "and the queue is left empty");
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

    /** The counters of the module are not window counters: they outlive a clear on purpose, so a leg
     * reads what it moved rather than a value three legs have already added up. */
    private static long counter(String key) {
        return Long.parseLong(reading(key));
    }

    /** Names the owner thread the way a host does, drives one tick and adopts the chain. */
    private static Chain hostAndInstall() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        ExecutableProductFace.declare();
        KernelModule module = KernelModule.instance();
        module.serverTick(List.of(WORLD));
        Chain chain = new Chain(module.commitOrderSource());
        assertTrue(module.installCommitChain(chain.log, chain.controller).bound());
        return chain;
    }

    @Test
    void aRequestOnTheOwnerThreadIsCarriedOutAtTheSafePointThroughTheOneChain() throws IOException {
        Chain c = hostAndInstall();
        KernelModule module = KernelModule.instance();
        module.serverTick(List.of(WORLD));
        assertFalse("0".equals(reading("kernel.tick_index")), "the module has a window to clear");
        long requests = counter("product.commit.reset_requests");
        long handOffs = counter("product.commit.reset_hand_offs");

        KernelModule.ResetRequest answer = module.requestReadingsReset("host:tick-boundary");


        assertTrue(answer.carriedOut(), "the request is answered where it is on the owner thread");
        assertEquals("host:tick-boundary", answer.source());
        assertFalse(answer.handedOff());
        assertEquals("reset-at-safe-point", answer.reason());
        assertEquals(requests + 1L, counter("product.commit.reset_requests"),
            "the request entry counted the call");
        assertEquals("host:tick-boundary", reading("product.commit.reset_source"));
        assertEquals(handOffs, counter("product.commit.reset_hand_offs"),
            "it was answered where it arrived instead of being handed off");
        assertEquals(1L, c.controller.resets(), "the one clear ran exactly once");
        assertEquals(1L, c.epochs.current(), "and the generation moved once with it");
        assertEquals(0L, c.log.sequence(), "the adopted log was ended by that clear");
        assertTrue(c.log.lastResetTyped() != null, "and left the projection of that lifecycle");
        assertEquals("0", reading("kernel.tick_index"), "the whole clear of the module ran");
        assertEquals("0", reading("product.commit.controller_reset_pending"), "no debt is left over");
        assertFalse(module.drainCommitReset(), "and the debt cannot be carried out a second time");
        assertEquals(1L, c.controller.resets(), "without ending a second lifecycle");
        assertEquals(1L, c.epochs.current());
    }

    @Test
    void aRequestFromAnotherThreadIsHandedToTheSafePointAndClearsNothingThere() throws Exception {
        Chain c = hostAndInstall();
        KernelModule module = KernelModule.instance();
        module.serverTick(List.of(WORLD));
        String window = reading("kernel.tick_index");
        long sequence = c.log.sequence();
        long requests = counter("product.commit.reset_requests");
        long handOffs = counter("product.commit.reset_hand_offs");
        AtomicReference<KernelModule.ResetRequest> answer = new AtomicReference<>();
        Thread console = new Thread(() -> answer.set(module.requestReadingsReset("host:console")));
        console.start();
        console.join();

        assertFalse(answer.get().carriedOut(),
            "a clear does not run on a thread the chain does not own");
        assertTrue(answer.get().handedOff());
        assertEquals("queued-for-owner-safe-point", answer.get().reason());
        assertEquals(0L, c.controller.resets(), "nothing ran the lifecycle from that thread");
        assertEquals(0L, c.epochs.current(), "and no generation moved");
        assertEquals(sequence, c.log.sequence(), "the log was not ended");
        assertEquals(window, reading("kernel.tick_index"), "and no counter of the module was cleared");
        assertEquals("1", reading("product.commit.controller_reset_pending"),
            "the whole reset is owed to the owner safe point");
        assertEquals(handOffs + 1L, counter("product.commit.reset_hand_offs"),
            "the request itself is counted as handed off");
        assertEquals(requests + 1L, counter("product.commit.reset_requests"));

        assertTrue(module.drainCommitReset(), "the owner safe point carries the owed reset out");
        assertEquals(1L, c.controller.resets(), "once");
        assertEquals(1L, c.epochs.current());
        assertEquals(0L, c.log.sequence(), "and it is that safe point that ends the lifecycle");
        assertEquals("0", reading("kernel.tick_index"));
        assertFalse(module.drainCommitReset(), "the same debt is not carried out twice");
        assertEquals(1L, c.controller.resets(), "a second drain moves neither account");
        assertEquals(1L, c.epochs.current());
    }

    @Test
    void aQueueThatIsFullRefusesTheRequestWhereItArrived() throws Exception {
        Chain c = hostAndInstall();
        KernelModule module = KernelModule.instance();
        module.serverTick(List.of(WORLD));
        long refusals = counter("product.commit.reset_queue_refusals");
        for (int index = 0; index < KernelModule.resetQueueCapacity(); index++) {
            String source = "host:console-" + index;
            KernelModule.ResetRequest accepted = onAnotherThread(module, source);
            assertTrue(accepted.handedOff(), "one request per source waits for the owner: " + source);
            assertEquals("queued-for-owner-safe-point", accepted.reason());
        }
        assertEquals(KernelModule.resetQueueCapacity(), module.resetQueueDepth(),
            "the queue holds exactly what it promised to hold");

        KernelModule.ResetRequest refused = onAnotherThread(module, "host:console-overflow");

        assertFalse(refused.handedOff(), "a request the queue cannot take is not handed anywhere");
        assertEquals("owner-thread-queue-full", refused.reason(),
            "and the caller is told to ask again on the owner thread");
        assertEquals(KernelModule.resetQueueCapacity(), module.resetQueueDepth(),
            "the queue is bounded by refusing, not by dropping what it holds");
        assertEquals(refusals + 1L, counter("product.commit.reset_queue_refusals"),
            "the refusal is counted where it happened");

        KernelModule.SafePoint drained = module.markSafePoint();

        assertEquals(KernelModule.resetQueueCapacity(), drained.carried(),
            "the owner answers every entry of the queue, one generation per entry");
        assertEquals(0, drained.owed(), "nothing of it is left owed");
        assertEquals(0, drained.queued(), "and the queue is empty after it");
        assertEquals((long) KernelModule.resetQueueCapacity(), c.epochs.current(),
            "each answered request advanced the generation with it");
        assertFalse(module.commitResetPending(), "nothing is left owed");
        assertEquals("0", reading("product.commit.reset_queued"), "and the readout says so");
    }

    private static KernelModule.ResetRequest onAnotherThread(KernelModule module, String source)
        throws InterruptedException {
        AtomicReference<KernelModule.ResetRequest> answer = new AtomicReference<>();
        Thread thread = new Thread(() -> answer.set(module.requestReadingsReset(source)));
        thread.start();
        thread.join();
        return answer.get();
    }

    @Test
    void anOwedRequestAndALaterExplicitRequestAreTwoGenerations() throws Exception {
        Chain c = hostAndInstall();
        KernelModule module = KernelModule.instance();
        module.serverTick(List.of(WORLD));
        long requests = counter("product.commit.reset_requests");
        Thread console = new Thread(() -> module.requestReadingsReset("host:console"));
        console.start();
        console.join();

        module.resetReadings();

        assertEquals(1L, c.controller.resets(),
            "the legacy clear drains the one reset that is owed instead of adding a second");
        assertEquals(1L, c.epochs.current());
        assertEquals(requests + 1L, counter("product.commit.reset_requests"),
            "the request entry counted its own call only");
        module.resetReadings();
        assertEquals(2L, c.controller.resets(), "an explicit second request is a second generation");
        assertEquals(2L, c.epochs.current());
    }

    @Test
    void aRequestAfterTheChainIsClosedIsRefusedAndClearsNothing() throws IOException {
        Chain c = hostAndInstall();
        KernelModule module = KernelModule.instance();
        module.serverTick(List.of(WORLD));
        String window = reading("kernel.tick_index");
        long sequence = c.log.sequence();
        long closed = counter("product.commit.reset_closed_refusals");
        long requests = counter("product.commit.reset_requests");
        assertTrue(module.closeCommitChain().closed());

        KernelModule.ResetRequest answer = module.requestReadingsReset("host:tick-boundary");

        assertFalse(answer.carriedOut(), "a closed lifecycle starts no generation after it");
        assertEquals("lifecycle-closed", answer.reason());
        assertEquals(closed + 1L, counter("product.commit.reset_closed_refusals"));
        assertEquals("0", reading("product.commit.controller_reset_pending"),
            "a refusal that could never be carried out is not left owed");
        assertEquals(0L, c.controller.resets());
        assertEquals(0L, c.epochs.current());
        assertEquals(sequence, c.log.sequence(), "the log was not ended");
        assertEquals(window, reading("kernel.tick_index"), "and nothing was cleared");
        module.resetReadings();
        assertFalse(module.drainCommitReset());
        assertEquals(requests + 1L, counter("product.commit.reset_requests"),
            "the legacy clear takes the same gate without being a request through the entry");
        assertEquals(closed + 2L, counter("product.commit.reset_closed_refusals"));
        assertEquals(0L, c.controller.resets());
    }

    @Test
    void aClosedChainIsNeverAdoptedAgainAndNoWorkStartsOnIt() throws IOException {
        Chain c = hostAndInstall();
        KernelModule module = KernelModule.instance();
        module.serverTick(List.of(WORLD));
        module.closeCommitChain();
        assertTrue(module.rollbackCommitChain(), "with nothing outstanding the module may step back");
        KernelModule.CommitBinding second = module.installCommitChain(c.log, c.controller);
        assertFalse(second.bound(), "a chain that has been closed is refused whole");
        assertEquals("chain-closed", second.refusal());
        assertEquals("NOT_WIRED", module.commitControllerWiring());
        assertEquals("NOT_WIRED", reading("product.commit.controller_wiring"));
        CommitReservation.ReserveResult after = c.reservations.reserve(c.request());
        assertFalse(after.admitted(), "and nothing starts on it again");
        assertEquals("lifecycle-closed", after.diagnostic());
        assertEquals(0L, c.controller.resets());
    }
}
