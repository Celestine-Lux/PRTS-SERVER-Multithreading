/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.config.ExecutableProductFace;
import io.izzel.arclight.common.prts.kernel.observe.KernelCommandExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command face is one of the two sessions a real request for a whole reset can come from. It
 * asks through the one request entry the module has and names itself, so a reading says where a
 * reset came from; a category that is off has nothing to reset and asks for nothing. */
class KernelResetCommandTest {

    private static final List<String> WORLDS = List.of(HostDriveFixture.WORLD);

    private final HostDriveFixture fixture = new HostDriveFixture();
    private final KernelModule module = KernelModule.instance();

    @AfterEach
    void leaveTheModuleAsItWas() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.markSafePoint();
        disposeWaitingItems();
        assertFalse(module.commitResetPending(), "no request outlives the case that made it");
        module.rollbackCommitChain();
        ExecutableProductFace.restore();
    }

    /** Hands every item that waits for a disposition its line, so the chain it holds can be stepped
     * off at the end of a case. */
    private void disposeWaitingItems() {
        for (KernelModule.Disposition item : module.commitChainDispositions()) {
            module.disposeCommitDisposition(item.ticketId(), new CommitTicket.Outcome(
                CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1", "b=2"), 2, ""));
        }
    }

    @Test
    void theCommandFaceAsksThroughTheOneRequestEntryAndNamesItself() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        ExecutableProductFace.declare();
        fixture.tick(WORLDS);
        new HostDriveFixture.Chain(module, ticket -> new CommitTicket.Outcome(
            CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1", "b=2"), 2, ""));

        List<String> answer = KernelCommandExtension.extension().run(List.of("reset"));

        assertEquals(1, answer.size(), "one session answers with one line");
        assertTrue(answer.get(0).contains("source=" + KernelModule.HOST_COMMAND_RESET_SOURCE),
            "the entry names where the request came from");
        assertTrue(answer.get(0).contains("carriedOut=true"),
            "a command runs on the thread the chain is owned by, so it is answered where it is made");
        assertEquals(KernelModule.HOST_COMMAND_RESET_SOURCE,
            HostDriveFixture.reading("product.commit.reset_source"));
        assertEquals("1", HostDriveFixture.reading("product.commit.resets"));
        assertEquals(KernelModule.HOST_COMMAND_RESET_SOURCE, KernelModule.HOST_COMMAND_RESET_SOURCE);
        assertTrue(KernelCommandExtension.extension().subcommands().contains("reset"));
        assertTrue(KernelCommandExtension.extension().subcommands().contains("selftest"),
            "the session that was there before it is still offered");
    }

    @Test
    void theCommandFaceAsksForNothingWhileTheCategoryIsOff() {
        long requests = HostDriveFixture.counter("product.commit.reset_requests");
        long safePoints = HostDriveFixture.counter("product.commit.safe_point_calls");

        List<String> answer = KernelCommandExtension.extension().run(List.of("reset"));

        assertTrue(answer.get(0).contains("the category is off"), answer.toString());
        assertEquals(requests, HostDriveFixture.counter("product.commit.reset_requests"),
            "a category that is off has nothing to reset");
        assertEquals(safePoints, HostDriveFixture.counter("product.commit.safe_point_calls"),
            "and no tick of the host sequence ran, so the safe point was not entered either");
    }
}
