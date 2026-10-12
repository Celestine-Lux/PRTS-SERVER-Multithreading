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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** The chain the platform adopts at the tick boundary, driven through the one entry the host calls:
 * a declaration that is off is refused before anything is built, a chain that passes is taken whole
 * and taken once, and a chain that was taken still refuses every request because it carries no publish
 * action. The tick here is the sequence the platform walks and not a server: no event bus delivers
 * anything, no world is touched, and a case that only passes here has not been shown to pass on one.
 * A reset is asked for under a fixture name, because a test is not one of the host sessions. */
class ProductionChainAdoptionTest {

    private static final List<String> WORLDS = List.of("world-a", "world-b");
    private static final String DOMAIN = "entity";
    private static final String NODE = "a/0";
    private static final long EFFECT = 9L;
    private static final String FIXTURE_RESET_SOURCE = "fixture-chain-adoption";

    private final KernelModule module = KernelModule.instance();

    @BeforeEach
    void switchTheKernelOn() {
        System.setProperty("arclight.prts.kernel", "true");
    }

    @AfterEach
    void leaveTheModuleAsItWas() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        module.markSafePoint();
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

    private static long counter(String key) {
        return Long.parseLong(reading(key));
    }

    /** One request the adopted chain would judge if it had an action to run it. */
    private static CommitTicket.ReserveRequest request(CommitReservation reservations) {
        CommitNamespaces.Namespace producer =
            new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L);
        CommitNamespaces.Namespace namespace = reservations.controller();
        CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 11L, 5L, 3L,
            "published", 7L, EFFECT, producer, namespace);
        EffectStore.Effect variant =
            new EffectStore.Effect(EFFECT, "publish", "PUBLISHED", List.of("a=1", "b=2"));
        return new CommitTicket.ReserveRequest("world-a", DOMAIN, NODE, CommitLog.Batch.Kind.APPLY,
            7L, 0, 7L, 4242L, 2, identity, namespace, variant, variant, variant);
    }

    /** What one request came back as, read the same way in every state of the binding. */
    private static String verdict(CommitController controller, CommitTicket.ReserveRequest request) {
        CommitController.SubmitResult result = controller.submit(request);
        return result.admitted() + "|" + result.terminal() + "|" + result.code() + "|"
            + result.diagnostic() + "|" + result.successBatch() + "|" + result.unitCount() + "|"
            + result.ticket();
    }

    private static final String REFUSED_WITH_NO_ACTION =
        "false|null|" + RejectCode.NATIVE_UNDECLARED + "|not-wired|0|0|null";

    /** The accounts of a chain a refused request may not move; the refusal count is read apart. */
    private static String ledger(CommitController controller) {
        CommitReservation reservations = controller.reservations();
        EffectStore store = reservations.store();
        return "controller[submits=" + controller.submits() + " supplierCalls="
            + controller.supplierCalls() + " acceptedPublished=" + controller.acceptedPublished()
            + " refusals=" + controller.refusals() + " refusedStarts=" + controller.refusedStarts() + "]"
            + " reservations[reserveSuccess=" + reservations.reserveSuccess() + " finalized="
            + reservations.finalized() + " released=" + reservations.releasedCount()
            + " outstanding=" + reservations.outstanding() + " publishing="
            + reservations.publishing() + " pending=" + reservations.pendingCount() + " ledger="
            + reservations.terminalLedger().size() + " failed=" + reservations.failedTotal() + "]"
            + " log[sequence=" + reservations.log().sequence() + " ticketStored="
            + reservations.log().ticketStored() + " ringDepth=" + reservations.log().ringDepth()
            + " taken=" + reservations.log().reservationsTaken() + "]"
            + " store[capacitySlots=" + store.capacitySlots() + " capacityReserved="
            + store.capacityReserved() + " capacityFinalized=" + store.capacityFinalized()
            + " records=" + store.records() + "]";
    }

    @Test
    void theTickBoundaryAdoptsOneChainAndEveryLaterCallIsInert() throws IOException {
        ExecutableProductFace.declare();
        module.serverTick(WORLDS);
        long calls = module.commitAdoptionCalls();

        KernelModule.CommitBinding binding = module.adoptCommitChainOnce();

        assertTrue(binding.bound(), "the declaration is executable and the chain passes every check");
        assertEquals("", binding.refusal());
        CommitController controller = module.commitController();
        CommitReservation reservations = controller.reservations();
        assertSame(reservations.log(), module.commits(), "the holder mints in the log of the module");
        assertSame(module.commits().effects(), reservations.store(), "the log carries that store");
        assertSame(module.commitOrderSource(), module.commits().orderSource(),
            "and its plan authority is the one instance of the module");
        assertSame(Thread.currentThread(), reservations.ownerThread());
        assertTrue(module.commitChainIsTheOneLog());
        assertTrue(module.commitOrderSourceBound());
        assertEquals(KernelModule.CHAIN_OWNER_ID, reservations.controller().ownerId());
        assertEquals(calls + 1L, module.commitAdoptionCalls(), "the entry was called once");

        assertEquals("BOUND", reading("product.commit.controller_wiring"));
        assertEquals("1", reading("product.commit.binding_chain_log"));
        assertEquals("1", reading("product.commit.binding_order_source"));
        assertEquals("-", reading("product.commit.binding_refusal"));
        assertEquals(String.valueOf(binding.storeId()), reading("product.commit.binding_store_id"));
        assertEquals(String.valueOf(binding.logLifetime()),
            reading("product.commit.binding_log_lifetime"));
        assertTrue(binding.storeId() != 0L && binding.logLifetime() != 0L);
        assertEquals("BOUND", reading("product.commit.store_wiring"));
        assertEquals(KernelModule.CHAIN_OWNER_ID, reading("product.commit.controller_owner"));
        assertEquals("host:server-thread", reading("product.commit.owner_thread"));
        assertEquals("NONE", reading("product.commit.controller_publisher"));
        assertEquals("0", reading("product.commit.controller_executable"));

        KernelModule.CommitBinding again = module.adoptCommitChainOnce();

        assertTrue(again.bound(), "a module that holds a chain answers with it instead of rebuilding");
        assertEquals(binding.logLifetime(), again.logLifetime());
        assertEquals(binding.storeId(), again.storeId());
        assertSame(controller, module.commitController(), "the very same controller is still the one");
        assertEquals(calls + 2L, module.commitAdoptionCalls(), "the second call was counted");
        assertEquals("-", reading("product.commit.binding_refusal"),
            "and it did not overwrite the reading of an adoption that did happen");
        assertEquals("BOUND", reading("product.commit.controller_wiring"));
        assertEquals(String.valueOf(binding.storeId()), reading("product.commit.binding_store_id"));
        assertEquals("0", reading("product.commit.submits"), "nothing was submitted by either call");
        assertEquals("0", reading("product.commit.reserve_success"));
        assertEquals("0", reading("product.commit.controller_reset_epoch"), "no generation advanced");
    }

    @Test
    void anUndeclaredFaceIsRefusedWholeAndLeavesTheModuleOnItsOwnLog() throws IOException {
        ExecutableProductFace.restore();
        module.serverTick(WORLDS);
        CommitLog own = module.commits();
        long calls = module.commitAdoptionCalls();

        KernelModule.CommitBinding binding = module.adoptCommitChainOnce();

        assertFalse(binding.bound(), "a face nobody declared is not obeyed");
        assertEquals(KernelModule.REFUSAL_DECLARED_FACE_OFF, binding.refusal());
        assertEquals(0L, binding.logLifetime(), "and the refusal names no identity it did not take");
        assertEquals(0L, binding.storeId());
        assertSame(own, module.commits(), "the module is still on the log it built for itself");
        assertNull(module.commitController(), "no controller was taken");
        assertFalse(module.commitChainIsTheOneLog());
        assertEquals(calls + 1L, module.commitAdoptionCalls());

        assertEquals("NOT_WIRED", reading("product.commit.controller_wiring"));
        assertEquals(KernelModule.REFUSAL_DECLARED_FACE_OFF, reading("product.commit.binding_refusal"));
        assertEquals("0", reading("product.commit.binding_chain_log"));
        assertEquals("0", reading("product.commit.binding_order_source"));
        assertEquals("0", reading("product.commit.binding_store_id"));
        assertEquals("0", reading("product.commit.binding_log_lifetime"));
        assertEquals("NOT_WIRED", reading("product.commit.store_wiring"));
        assertEquals("NOT_WIRED", reading("product.commit.effect_state"));
        assertEquals("-", reading("product.commit.controller_owner"));
        assertEquals("NONE", reading("product.commit.controller_publisher"));
        assertEquals("0", reading("product.commit.controller_executable"));
        assertEquals("0", reading("product.commit.enabled"));
        assertEquals("0", reading("product.commit.executable"));

        KernelModule.CommitBinding second = module.adoptCommitChainOnce();

        assertEquals(binding.refusal(), second.refusal(), "the next tick is refused the same way");
        assertSame(own, module.commits());
        assertNull(module.commitController());
        assertEquals(calls + 2L, module.commitAdoptionCalls());
    }

    @Test
    void aChainIsNotAdoptedBeforeTheHostHasNamedTheOwnerThread() throws IOException {
        assumeFalse(module.commitOwnerThreadBound(),
            "no tick has been driven in this JVM, so the host names no owner thread yet");
        ExecutableProductFace.declare();
        CommitLog own = module.commits();

        KernelModule.CommitBinding binding = module.adoptCommitChainOnce();

        assertFalse(binding.bound(), "a chain is not judged on a thread the host never named");
        assertEquals("owner-thread-unbound", binding.refusal());
        assertSame(own, module.commits());
        assertNull(module.commitController());
        assertEquals("NOT_WIRED", reading("product.commit.controller_wiring"));
        assertEquals("owner-thread-unbound", reading("product.commit.binding_refusal"));
        assertEquals("0", reading("product.commit.binding_chain_log"));
        assertEquals("0", reading("product.commit.binding_store_id"));
    }

    @Test
    void anAdoptionAskedFromAnotherThreadIsRefusedWhole() throws IOException {
        ExecutableProductFace.declare();
        module.serverTick(WORLDS);
        CommitLog own = module.commits();
        long calls = module.commitAdoptionCalls();
        AtomicReference<KernelModule.CommitBinding> answer = new AtomicReference<>();
        Thread asker = new Thread(() -> answer.set(module.adoptCommitChainOnce()));
        asker.start();
        join(asker);

        KernelModule.CommitBinding binding = answer.get();

        assertFalse(binding.bound(), "the owner gate has no exception for the one caller of it");
        assertEquals("off-owner-thread", binding.refusal());
        assertSame(own, module.commits(), "the module is still on the log it built for itself");
        assertNull(module.commitController(), "and not one piece of the candidate was taken");
        assertFalse(module.commitChainIsTheOneLog());
        assertEquals(calls + 1L, module.commitAdoptionCalls());
        assertEquals("NOT_WIRED", reading("product.commit.controller_wiring"));
        assertEquals("off-owner-thread", reading("product.commit.binding_refusal"));
        assertEquals("0", reading("product.commit.binding_chain_log"));
    }

    @Test
    void bindingDoesNotMakeARequestExecutable() throws IOException {
        ExecutableProductFace.declare();
        module.serverTick(WORLDS);
        assertTrue(module.adoptCommitChainOnce().bound());
        CommitController controller = module.commitController();
        CommitTicket.ReserveRequest request = request(controller.reservations());

        String bound = ledger(controller);
        assertEquals(REFUSED_WITH_NO_ACTION, verdict(controller, request),
            "bound and able to run are two columns, and the refusal says which one is missing");
        assertEquals(bound, ledger(controller), "every account of the chain is where it was");
        assertEquals(1L, controller.notWiredRefusals(), "the refusal is the only thing it moved");
        assertEquals("1", reading("product.commit.not_wired_refusals"));
        assertEquals("0", reading("product.commit.submits"), "the first counter stands past the gate");
        assertEquals("0", reading("product.commit.reserve_success"));
        assertEquals("0", reading("product.commit.supplier_calls"));
        assertEquals("BOUND", reading("product.commit.controller_wiring"));
        assertEquals("NONE", reading("product.commit.controller_publisher"));
        assertEquals("0", reading("product.commit.controller_executable"));

        assertTrue(module.rollbackCommitChain(), "the module can be taken off the chain it holds");
        assertNull(module.commitController());

        assertEquals(REFUSED_WITH_NO_ACTION, verdict(controller, request),
            "the same chain, the same request, and the same refusal word for word");
        assertEquals(bound, ledger(controller), "the same chain, unchanged while the module stepped off");
        assertEquals(2L, controller.notWiredRefusals(), "and the refusal is again the only thing moved");
        assertEquals("0", reading("product.commit.not_wired_refusals"),
            "with no chain bound the module publishes the columns of a chain nobody holds");
        assertEquals("NOT_WIRED", reading("product.commit.controller_wiring"));
        assertEquals("0", reading("product.commit.controller_executable"));
    }

    @Test
    void theAdoptedChainStillAnswersAResetAndCloses() throws IOException {
        ExecutableProductFace.declare();
        module.serverTick(WORLDS);
        assertTrue(module.adoptCommitChainOnce().bound());
        CommitController controller = module.commitController();
        long closes = counter("product.commit.close_calls");

        KernelModule.ResetRequest here = module.requestReadingsReset(FIXTURE_RESET_SOURCE);

        assertTrue(here.carriedOut(), "the owner thread carries the reset through the adopted chain");
        assertEquals("reset-at-safe-point", here.reason());
        assertEquals(1L, controller.resets());
        assertEquals(1L, controller.epoch());
        assertEquals("1", reading("product.commit.controller_reset_epoch"));
        assertEquals("reset-at-safe-point",
            reading("product.commit.reset_trace").substring(
                reading("product.commit.reset_trace").lastIndexOf('|') + 1));
        assertEquals("BOUND", reading("product.commit.controller_wiring"),
            "a reset clears the live counters and not the binding");
        assertEquals("1", reading("product.commit.binding_chain_log"));
        assertFalse(module.drainCommitReset(), "a reset that ran leaves no debt to drain");
        assertEquals("0", reading("product.commit.controller_reset_pending"));
        assertEquals("-", reading("product.commit.pending_dispositions"),
            "a dash is nothing owed, and it is not a zero");
        assertEquals("-", reading("product.commit.chain_dispositions"));
        assertEquals("0", reading("product.commit.chain_sealed"));
        assertEquals("0", reading("product.commit.chain_sealed_count"));
        assertEquals("0", reading("product.commit.seal_discards"));
        assertEquals("0", reading("product.commit.attention"));
        assertEquals("accepting", reading("product.commit.disposition_gate_reason"));

        KernelModule.CommitClose close = module.closeCommitChain();

        assertTrue(close.closed(), "the chain the module adopted is the one the close ends");
        assertEquals("", close.refusal());
        assertEquals(closes + 1L, counter("product.commit.close_calls"));
        assertEquals("1", reading("product.commit.closed"));
        assertEquals("0", reading("product.commit.close_unrecorded"));
        assertEquals("0", reading("product.commit.close_publishing"));
        assertEquals("BOUND", reading("product.commit.controller_wiring"),
            "and the wiring column still says what the chain is");
        assertEquals("lifecycle-closed", reading("product.commit.disposition_gate_reason"));

        KernelModule.CommitClose again = module.closeCommitChain();

        assertTrue(again.closed(), "a second close ends nothing that was already ended");
        assertEquals(0, again.givenBack(), "and gives back nothing a second time");
        assertEquals(closes + 2L, counter("product.commit.close_calls"));
    }

    private static void join(Thread thread) {
        try {
            thread.join(30_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        assertFalse(thread.isAlive(), "the asking thread finished");
    }
}
