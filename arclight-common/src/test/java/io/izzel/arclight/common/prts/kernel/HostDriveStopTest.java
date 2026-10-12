/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.config.ExecutableProductFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stopping path of the host as a controlled drive: every world the host carries goes through the
 * four step set, the chain is closed after them, and which of the two came first is read back from
 * the run. The five combinations of a reset and a close are each read on six faces at once, and a
 * generation that is absent or was never tracked is a refusal and not a zero. */
class HostDriveStopTest {

    private static final List<String> WORLDS =
        List.of(HostDriveFixture.WORLD, HostDriveFixture.SECOND_WORLD);

    private final HostDriveFixture fixture = new HostDriveFixture();
    private final KernelModule module = KernelModule.instance();

    @BeforeEach
    void switchTheKernelOn() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        ExecutableProductFace.declare();
    }

    @AfterEach
    void leaveTheModuleAsItWas() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.markSafePoint();
        disposeWaitingItems();
        assertFalse(module.commitResetPending(), "no request outlives the case that made it");
        assertTrue(module.resetQueueSources().isEmpty(), "and the queue is left empty");
        module.rollbackCommitChain();
        ExecutableProductFace.restore();
    }

    private HostDriveFixture.Chain install() {
        fixture.tick(WORLDS);
        return new HostDriveFixture.Chain(module, ticket -> published());
    }

    private static CommitTicket.Outcome published() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1", "b=2"),
            2, "");
    }

    /** Hands every item that waits for a disposition its line, so the chain it holds can be stepped
     * off at the end of a case. */
    private void disposeWaitingItems() {
        for (KernelModule.Disposition item : module.commitChainDispositions()) {
            module.disposeCommitDisposition(item.ticketId(), published());
        }
    }

    /** The six faces one combination is read on at once: the generation, the tick the module was
     * cleared to, the lines of the adopted log, the reservations, what waits for a disposition, and
     * whether a close sealed anything. */
    private String sixFaces(HostDriveFixture.Chain chain) {
        return "epoch=" + chain.epochs.current()
            + " tick=" + HostDriveFixture.reading("kernel.tick_index")
            + " log=" + chain.log.sequence() + "/" + chain.log.ringDepth()
            + " reservation=" + chain.reservations.reserveSuccess() + "/" + chain.reservations.finalized()
            + "/" + chain.reservations.releasedCount() + "/" + chain.reservations.outstanding()
            + " pending=" + HostDriveFixture.reading("product.commit.pending")
            + " sealed=" + HostDriveFixture.reading("product.commit.chain_sealed");
    }

    /** The same six faces with the tick column taken out, for a case where a tick of the host sequence
     * legitimately ran between two reads. */
    private static String withoutTick(String faces) {
        return faces.replaceAll("tick=[0-9]+", "tick=-");
    }

    @Test
    void theStoppingPathFlushesEveryWorldInTheFourStepSetAndThenCloses() {
        HostDriveFixture.Chain chain = install();
        long ticks = HostDriveFixture.counter("kernel.tick_index");
        long sessions = HostDriveFixture.counter("product.commit.stop_flush_sessions");
        long closes = HostDriveFixture.counter("product.commit.close_calls");
        long refusals = HostDriveFixture.counter("product.commit.stop_flush_refusals");

        fixture.stop(WORLDS);

        assertEquals(sessions + 2L, HostDriveFixture.counter("product.commit.stop_flush_sessions"));
        List<String> flushed = module.commitStopFlushWorlds();
        assertEquals(List.of(HostDriveFixture.WORLD, HostDriveFixture.SECOND_WORLD),
            flushed.subList(flushed.size() - 2, flushed.size()),
            "the worlds are walked in the order the host carried them");
        assertEquals("1", HostDriveFixture.reading("product.commit.stop_flush_ordered"),
            "every flushed world took the four steps in the order the set names");
        assertEquals("flush-before-close", HostDriveFixture.reading("product.commit.stop_flush_order"),
            "which of the two stop entries came first is read from the run, not from this code");
        assertEquals(refusals, HostDriveFixture.counter("product.commit.stop_flush_refusals"),
            "no world of this stop was refused");
        assertEquals(closes + 1L, HostDriveFixture.counter("product.commit.close_calls"));
        assertEquals("HOST_SAFE_POINT+CLOSE",
            HostDriveFixture.reading("product.commit.safe_point_wiring"),
            "both host entries have now been entered in this process");
        assertTrue(ticks > 0L);
        assertEquals("0", HostDriveFixture.reading("product.commit.chain_sealed"),
            "this close sealed nothing, and the column is about this close");
        assertEquals("0", HostDriveFixture.reading("product.commit.seal_discards"));
        assertTrue(chain.reservations.closed(), "and the chain is closed after the flush");
    }

    @Test
    void aWorldThatIsNotLiveIsRefusedWholeAndNoColumnOfItIsZero() {
        fixture.tick(WORLDS);
        long refusals = HostDriveFixture.counter("product.commit.stop_flush_refusals");
        int rows = module.commitStopFlushRows().size();

        KernelModule.WorldStopFlush served = module.flushWorldAtStop(HostDriveFixture.WORLD);
        KernelModule.WorldStopFlush again = module.flushWorldAtStop(HostDriveFixture.WORLD);
        KernelModule.WorldStopFlush never = module.flushWorldAtStop("world-that-was-never-observed");

        assertTrue(served.refusal().isEmpty(), "a live world is flushed");
        assertTrue(served.ordered(), "its four steps were taken in order");
        assertEquals(-1L, again.worldEpoch(),
            "the world was forgotten by its own flush, so it reads as absent and not as a zero");
        assertEquals("world-not-live", again.refusal());
        assertEquals(-1L, never.worldEpoch(),
            "a world the live list does not carry is absent here as well, and absent is a refusal");
        assertEquals("world-not-live", never.refusal());
        assertEquals(refusals + 2L, HostDriveFixture.counter("product.commit.stop_flush_refusals"));
        assertEquals("world-not-live", HostDriveFixture.reading("product.commit.stop_flush_refusal"));
        KernelModule.WorldStopFlush refused = module.commitStopFlushRows().get(rows + 1);
        assertEquals("world-a", refused.worldId());
        assertEquals(-1L, refused.worldEpoch(), "a refused world carries the generation it was refused for");
        assertEquals("0>0>0>0", refused.steps(), "and a refused session stamped no step at all");
        assertEquals("1", HostDriveFixture.reading("product.commit.stop_flush_ordered"),
            "a refused session is not one of the sessions the order is read over");
        assertFalse(again.ordered(), "a refused session took no step at all");
    }

    @Test
    void theCloseAfterTheFlushSealsWhatIsUnrecordedAndIsIdempotent() {
        HostDriveFixture.Chain chain = install();
        CommitTicket ticket = chain.reservations.reserve(chain.request(31L)).ticket();
        assertTrue(chain.reservations.beginPublish(ticket));
        CommitTicket.Outcome grown = new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
            List.of("a=1", "b=2"), 2, "a-note-the-promise-never-covered-".repeat(20));
        assertFalse(chain.reservations.finalize(ticket, grown).finalized(),
            "the record is refused and the item waits for a disposition");

        fixture.stop(WORLDS);

        assertEquals("1", HostDriveFixture.reading("product.commit.chain_sealed"));
        assertEquals("1", HostDriveFixture.reading("product.commit.chain_sealed_count"));
        assertEquals("0", HostDriveFixture.reading("product.commit.seal_discards"),
            "an item whose line is not written keeps its identity instead of being dropped");
        assertTrue(HostDriveFixture.reading("product.commit.chain_sealed_identities")
            .startsWith(ticket.ticketId() + ":sealed-at-close:"),
            "and the identity is the ticket the owner must hand its line to");
        assertEquals("1", HostDriveFixture.reading("product.commit.pending"),
            "the item keeps its room: a close is not a cleanup");
        assertEquals("0", HostDriveFixture.reading("product.commit.accepting_new_work"));
        assertEquals("closed-with-pending-dispositions",
            HostDriveFixture.reading("product.commit.reset_composition")
                .substring(0, "closed-with-pending-dispositions".length()));
        assertFalse(module.rollbackCommitChain(),
            "and a rollback does not take the module off a chain that still owes a line");
        assertEquals("rollback-pending-dispositions",
            HostDriveFixture.reading("product.commit.binding_refusal"));
        assertEquals("BOUND", HostDriveFixture.reading("product.commit.controller_wiring"));

        long lines = chain.log.sequence();
        long closes = HostDriveFixture.counter("product.commit.close_calls");
        fixture.stop(WORLDS);

        assertEquals(closes + 1L, HostDriveFixture.counter("product.commit.close_calls"),
            "the second close was entered as well");
        assertEquals(lines, chain.log.sequence(), "and it wrote no line of its own");
        assertEquals(0L, chain.reservations.releasedCount(), "it gave nothing back a second time");
        assertEquals("1", HostDriveFixture.reading("product.commit.pending"));
    }

    @Test
    void aResetBeforeACloseLeavesTheGenerationWhereItWas() {
        HostDriveFixture.Chain chain = install();
        HostDriveFixture.requestFromAnotherThread(module, HostDriveFixture.FIXTURE_SOURCE);
        fixture.postTick(WORLDS);
        assertEquals("epoch=1 tick=0 log=0/0 reservation=0/0/0/0 pending=0 sealed=0", sixFaces(chain));

        fixture.stop(WORLDS);

        assertEquals("epoch=1 tick=0 log=0/0 reservation=0/0/0/0 pending=0 sealed=0", sixFaces(chain),
            "a close ends the chain and does not advance the generation");
        assertEquals("lifecycle-closed", HostDriveFixture.reading("product.commit.reset_composition")
            .substring(0, "lifecycle-closed".length()));
    }

    @Test
    void aResetAfterACloseOpensNoGeneration() {
        HostDriveFixture.Chain chain = install();
        fixture.stop(WORLDS);
        String closed = sixFaces(chain);

        KernelModule.ResetRequest request = module.requestReadingsReset(HostDriveFixture.FIXTURE_SOURCE);

        assertFalse(request.carriedOut());
        assertEquals("lifecycle-closed", request.reason());
        assertTrue(chain.controller.resetClosedRefusals() > 0L);
        assertEquals(closed, sixFaces(chain), "not one of the six faces moved");

        HostDriveFixture.requestFromAnotherThread(module, "fixture-after-the-close");
        fixture.postTick(WORLDS);

        assertEquals(withoutTick(closed), withoutTick(sixFaces(chain)),
            "and the safe point that took it changed none of the faces but the tick it ran on");
    }

    @Test
    void aCloseWithSomethingWaitingKeepsItReadableWithItsRoom() {
        HostDriveFixture.Chain chain = install();
        CommitTicket ticket = chain.reservations.reserve(chain.request(41L)).ticket();
        assertTrue(chain.reservations.beginPublish(ticket));
        assertFalse(chain.reservations.finalize(ticket, new CommitTicket.Outcome(
            CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1", "b=2"), 2,
            "a-note-the-promise-never-covered-".repeat(20))).finalized());

        String waiting = sixFaces(chain);

        fixture.stop(WORLDS);

        assertEquals(waiting.replace("sealed=0", "sealed=1"), sixFaces(chain),
            "the close is told apart from the debt it left, and it moved no other face");
        assertEquals("1", HostDriveFixture.reading("product.commit.chain_capacity_reserved"));
        assertEquals("1", HostDriveFixture.reading("product.commit.conservation"),
            "conservation on its own does not say the room came back");
    }

    @Test
    void aCloseWithNothingWaitingReadsTheCloseItself() {
        HostDriveFixture.Chain chain = install();
        String clean = sixFaces(chain);

        fixture.stop(WORLDS);

        assertEquals(clean, sixFaces(chain), "a close with nothing waiting seals nothing and moves no face");
        assertEquals("lifecycle-closed:owe=0:clear=0:accepting=0:owedAtSafePoint=0",
            HostDriveFixture.reading("product.commit.reset_composition"),
            "the close itself is what blocks, and it owes nothing");
    }

    @Test
    void aRepeatedRequestIsOneEntryAndTwoRequestsAreTwoGenerations() {
        HostDriveFixture.Chain chain = install();
        HostDriveFixture.requestFromAnotherThread(module, "fixture-a");
        HostDriveFixture.requestFromAnotherThread(module, "fixture-a");
        assertEquals(1, module.resetQueueDepth());

        fixture.postTick(WORLDS);
        HostDriveFixture.requestFromAnotherThread(module, "fixture-b");
        fixture.postTick(WORLDS);

        assertEquals(2L, chain.controller.resets(), "two explicit requests are two generations");
        assertEquals(2L, chain.epochs.current());
        assertEquals("epoch=2 tick=0 log=0/0 reservation=0/0/0/0 pending=0 sealed=0", sixFaces(chain));

        long closes = HostDriveFixture.counter("product.commit.close_calls");
        fixture.stop(WORLDS);
        long lines = chain.log.sequence();
        fixture.stop(WORLDS);

        assertEquals(closes + 2L, HostDriveFixture.counter("product.commit.close_calls"));
        assertEquals(lines, chain.log.sequence(), "the second close wrote no line and released nothing");
        assertEquals(0L, chain.reservations.releasedCount());
        assertEquals(2L, chain.epochs.current(), "and no close moved the generation");
    }
}
