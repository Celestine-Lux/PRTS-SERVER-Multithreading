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

/** The balance of an adopted chain, read column by column instead of as one boolean: what was
 * reserved, what was finalized, what was released and what is still in flight, next to the room and
 * the ring slots of the same chain, at every step a line can take. */
class ChainConservationReadingsTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final String NODE = "a/0";

    /** The places the frozen plan gives the lines of this fixture, one after the other. */
    private static final class Plans implements CommitLog.OrderSource {

        private int position;

        @Override
        public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
            return new CommitLog.Resolved(7L, position, false);
        }

        CommitTicket.ReserveRequest request(CommitReservation reservations, int position, long effect) {
            this.position = position;
            CommitNamespaces.Namespace producer =
                new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L);
            CommitNamespaces.Namespace namespace = reservations.controller();
            CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 11L, 5L, 3L,
                "published", 7L, effect, producer, namespace);
            EffectStore.Effect variant =
                new EffectStore.Effect(effect, "publish", "PUBLISHED", List.of("a=1", "b=2"));
            return new CommitTicket.ReserveRequest(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY,
                7L, position, 7L, 4242L, 2, identity, namespace, variant, variant, variant);
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

    private static CommitTicket.Outcome published() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1", "b=2"),
            2, "");
    }

    private static CommitTicket.Outcome outgrown() {
        return new CommitTicket.Outcome(CommitTicket.Terminal.PARTIAL, "publish", List.of("a=1", "b=2"),
            2, "a-note-the-promise-never-covered-".repeat(20));
    }

    /** What one column reads, without the name of the column in front of it. */
    private static String value(List<String> export, String key) {
        String row = line(export, key);
        return row.substring(row.indexOf('=') + 1);
    }

    /** Every column of the balance, as one reading, so a step is compared against all of them. */
    private static String balance(List<String> export) {
        return "reserved=" + value(export, "product.commit.chain_reserved")
            + " finalized=" + value(export, "product.commit.chain_finalized")
            + " released=" + value(export, "product.commit.chain_released")
            + " inFlight=" + value(export, "product.commit.chain_in_flight")
            + " room=" + value(export, "product.commit.chain_capacity_reserved")
            + "/" + value(export, "product.commit.chain_capacity_finalized")
            + "/" + value(export, "product.commit.chain_capacity_released")
            + " slots=" + value(export, "product.commit.effect_capacity_slots")
            + " ring=" + value(export, "product.commit.reserved_slots")
            + " taken/consumed/released=" + value(export, "product.commit.chain_log_taken")
            + "/" + value(export, "product.commit.chain_log_consumed")
            + "/" + value(export, "product.commit.chain_log_released")
            + " pins=" + value(export, "product.commit.effect_pins")
            + " conservation=" + value(export, "product.commit.chain_conservation");
    }

    @Test
    void withNothingBoundTheChainColumnsAreZeroAndTheBalanceIsADash() {
        System.setProperty("arclight.prts.kernel", "true");
        List<String> export = export();
        for (String key : List.of("product.commit.chain_reserved", "product.commit.chain_finalized",
            "product.commit.chain_released", "product.commit.chain_in_flight",
            "product.commit.chain_capacity_reserved", "product.commit.chain_capacity_finalized",
            "product.commit.chain_capacity_released", "product.commit.chain_log_taken",
            "product.commit.chain_log_consumed", "product.commit.chain_log_released",
            "product.commit.ledger_evicted")) {
            assertTrue(line(export, key).endsWith("=0"), key + " reads zero while nothing is bound");
        }
        assertEquals("product.commit.chain_conservation=-",
            line(export, "product.commit.chain_conservation"),
            "no chain is adopted, so there is no balance to read");
    }

    @Test
    void theFourColumnsFollowEveryStepALineCanTake() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        ExecutableProductFace.declare();
        KernelModule module = KernelModule.instance();
        module.serverTick(List.of(WORLD));
        Plans plans = new Plans();
        EffectStore store = new EffectStore(4096, 8);
        CommitLog log = new CommitLog(() -> 4, module.commitOrderSource(), store,
            new WitnessSource(plans, WORLD, DOMAIN, 3L, "published", 5L, 7L, NODE));
        CommitReservation reservations = new CommitReservation(log, store, plans, "owner-a",
            new CommitNamespaces.ResetEpochs());
        CommitController controller = new CommitController(reservations, null);
        assertTrue(module.installCommitChain(log, controller).bound());
        try {
            assertEquals("reserved=0 finalized=0 released=0 inFlight=0 room=0/0/0 slots=0 ring=0"
                    + " taken/consumed/released=0/0/0 pins=0 conservation=1",
                balance(export()), "an empty chain balances at zero");

            CommitTicket given = reservations.reserve(plans.request(reservations, 0, 100L)).ticket();
            assertEquals("reserved=1 finalized=0 released=0 inFlight=1 room=1/0/0 slots=1 ring=1"
                    + " taken/consumed/released=1/0/0 pins=0 conservation=1",
                balance(export()), "a reservation taken is one in flight with its room");

            assertTrue(reservations.release(given, CommitTicket.ReleaseReason.CANCELLED).released());
            assertEquals("reserved=1 finalized=0 released=1 inFlight=0 room=1/0/1 slots=0 ring=0"
                    + " taken/consumed/released=1/0/1 pins=0 conservation=1",
                balance(export()), "a reservation given back is released, not finalized");

            CommitTicket landed = reservations.reserve(plans.request(reservations, 1, 101L)).ticket();
            assertTrue(reservations.beginPublish(landed));
            assertTrue(reservations.finalize(landed, published()).finalized());
            assertEquals("reserved=2 finalized=1 released=1 inFlight=0 room=2/1/1 slots=0 ring=0"
                    + " taken/consumed/released=2/1/1 pins=1 conservation=1",
                balance(export()), "a line that landed is finalized and pins its record");

            CommitTicket waiting = reservations.reserve(plans.request(reservations, 2, 102L)).ticket();
            assertTrue(reservations.beginPublish(waiting));
            assertFalse(reservations.finalize(waiting, outgrown()).finalized());
            assertEquals("reserved=3 finalized=1 released=1 inFlight=1 room=3/1/1 slots=1 ring=1"
                    + " taken/consumed/released=3/1/1 pins=1 conservation=1",
                balance(export()),
                "an item waiting for its line is in flight and holds its room while the balance holds");
            assertEquals("product.commit.conservation=1", line(export(), "product.commit.conservation"),
                "and the older boolean says the same, which is why neither one proves no leak");
            assertEquals("product.commit.pending=1", line(export(), "product.commit.pending"));

            assertTrue(reservations.finalize(waiting, published()).finalized(),
                "the owner hands it the line that closes it");
            assertEquals("reserved=3 finalized=2 released=1 inFlight=0 room=3/2/1 slots=0 ring=0"
                    + " taken/consumed/released=3/2/1 pins=2 conservation=1",
                balance(export()), "and the room of that item goes back with it");
            assertEquals("product.commit.ledger_evicted=0", line(export(), "product.commit.ledger_evicted"),
                "nothing was evicted out of the bounded ledger");
            assertEquals("product.commit.terminal_ledger_entries=3",
                line(export(), "product.commit.terminal_ledger_entries"),
                "one line given back, one landed, one landed after waiting");
        } finally {
            module.rollbackCommitChain();
        }
    }
}
