/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The unload delivery of one stop as a controlled drive: the tree raises the event at its stopping
 * injection, the delivery reads the lifecycle control the tick, the stop flush and the close already
 * share and moves nothing of it, and a second delivery of one world in one stop is a repeat that
 * fails the criterion instead of being merged into the first row. No event bus delivers anything
 * here, so a case that only passes here has not been shown to pass on a server. */
class WorldUnloadDeliveryTest {

    private final HostDriveFixture fixture = new HostDriveFixture();
    private final KernelModule module = KernelModule.instance();

    @BeforeEach
    void switchTheKernelOn() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        module.installUnloadDelivery();
    }

    @AfterEach
    void leaveTheModuleAsItWas() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        module.removeUnloadDelivery();
        module.removeWaitSiteTap();
        module.removeWritePathTap();
    }

    /** The rows of one world over the whole process, so a case reads its own worlds and not what an
     * earlier case left in the log. */
    private List<KernelModule.WorldUnloadDelivery> rowsOf(String world) {
        List<KernelModule.WorldUnloadDelivery> rows = new ArrayList<>();
        for (KernelModule.WorldUnloadDelivery row : module.unloadDeliveries()) {
            if (row.worldId().equals(world)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private KernelModule.WorldStopFlush flushOf(String world) {
        for (KernelModule.WorldStopFlush row : module.commitStopFlushRows()) {
            if (row.worldId().equals(world)) {
                return row;
            }
        }
        throw new AssertionError("no flush row was recorded for " + world);
    }

    @Test
    void everyWorldOfOneStopIsDeliveredOnceAndReadsTheControlTheFlushUsed() {
        List<String> worlds = List.of("case-delivery-a", "case-delivery-b");
        fixture.tick(worlds);
        fixture.stop(worlds);
        int rowsBefore = module.unloadDeliveries().size();
        long duplicatesBefore = module.unloadDuplicates();

        KernelModule.WorldUnloadDelivery first = module.noteWorldUnload("case-delivery-a");
        KernelModule.WorldUnloadDelivery second = module.noteWorldUnload("case-delivery-b");

        assertEquals(rowsBefore + 2, module.unloadDeliveries().size());
        assertEquals(duplicatesBefore, module.unloadDuplicates(),
            "two worlds of one stop are two deliveries and no repeat");
        assertTrue(first.delivered() && second.delivered());
        assertEquals(first.stopEntry(), second.stopEntry(), "one stop entry carries both deliveries");
        assertTrue(first.stopEntry() > 0L, "the entry the close took is the window they name");
        assertEquals(flushOf("case-delivery-a").worldEpoch(), first.worldEpoch(),
            "the generation of a delivery is the one the shared flush took off that world");
        assertEquals(flushOf("case-delivery-a").steps(), first.steps());
        assertTrue(first.flushEntry() > 0L && second.flushEntry() > first.flushEntry(),
            "each world names the entry of its own flush, in the order the host walked the worlds");

        String row = first.row();
        assertTrue(row.startsWith("world=case-delivery-a|worldEpoch=" + first.worldEpoch() + "|"));
        assertTrue(row.contains("|source=" + KernelModule.HOST_UNLOAD_SOURCE + "|"),
            "the trigger source is the stopping walk of this tree and not a platform delivery");
        assertTrue(row.contains("|flushSteps=" + first.steps() + "|"));
        assertTrue(row.contains("|reclaim=owner:" + KernelModule.HOST_STOP_FLUSH_OWNER + "|byFlush:"));
        assertTrue(row.contains("|byDelivery:0|"), "the delivery itself takes nothing back");
        assertTrue(row.contains("|oldGeneration=UNLIVE(-1)|"),
            "the flush forgot the world first, so an old generation reads as the refusal it is");
        assertTrue(row.endsWith("|delivery=TRIGGERED"));

        assertTrue(HostDriveFixture.reading("product.unload.rows").contains("world=case-delivery-a|"));
        assertTrue(HostDriveFixture.reading("product.unload.counts").contains("case-delivery-a=1"));
        assertEquals("WIRED", HostDriveFixture.reading("product.unload.wiring"));
        assertEquals(KernelModule.HOST_UNLOAD_SOURCE, HostDriveFixture.reading("product.unload.source"));
    }

    @Test
    void aSecondDeliveryOfOneWorldInOneStopIsWrittenAsARepeatAndFailsTheCriterion() {
        List<String> worlds = List.of("case-repeat");
        fixture.tick(worlds);
        fixture.stop(worlds);
        long duplicatesBefore = module.unloadDuplicates();

        KernelModule.WorldUnloadDelivery once = module.noteWorldUnload("case-repeat");
        KernelModule.WorldUnloadDelivery twice = module.noteWorldUnload("case-repeat");

        assertTrue(once.delivered(), "the first delivery of the world is the one delivery");
        assertFalse(twice.delivered(), "the second one is not, and the row says so");
        assertTrue(twice.row().endsWith("|delivery=" + KernelModule.UNLOAD_DUPLICATE));
        assertEquals(duplicatesBefore + 1L, module.unloadDuplicates(),
            "the repeat is counted where the criterion reads it");
        assertEquals(2, rowsOf("case-repeat").size(),
            "the repeat keeps a row of its own instead of being merged into the first");
        assertTrue(HostDriveFixture.reading("product.unload.counts").contains("case-repeat=2"),
            "the per world count reads two, which is the run the criterion fails");
        assertEquals(duplicatesBefore + 1L, HostDriveFixture.counter("product.unload.duplicates"));
    }

    @Test
    void aWorldOfTheStopThatNothingReachedReadsNeverRunInsteadOfAZero() {
        List<String> worlds = List.of("case-silent-a", "case-silent-b");
        fixture.tick(worlds);
        fixture.stop(worlds);

        module.noteWorldUnload("case-silent-a");

        String rows = HostDriveFixture.reading("product.unload.rows");
        assertTrue(rows.contains("world=case-silent-b|delivery=NEVER_RUN"),
            "a world of the stop nothing reached has a cell that names it");
        assertFalse(rows.contains("world=case-silent-b|delivery=0"),
            "and that cell is not the zero a count would be");
        assertTrue(HostDriveFixture.reading("product.unload.counts").contains("case-silent-b=0"),
            "the count of deliveries is the count of an event, which the criterion asks for");
    }

    @Test
    void theDeliveryAdvancesNoGenerationAndTakesNoTokenBack() {
        List<String> worlds = List.of("case-shared-a", "case-shared-b");
        fixture.tick(worlds);
        fixture.stop(worlds);
        long epochsBefore = HostDriveFixture.counter("write.world_epochs_changes");
        long liveBefore = HostDriveFixture.counter("write.world_epochs_live");
        long releasedBefore = HostDriveFixture.counter("intent.released");
        long depthBefore = HostDriveFixture.counter("intent.queue_depth");
        long advancesBefore = module.unloadEpochAdvances();
        long reclaimsBefore = module.unloadTokenReclaims();

        KernelModule.WorldUnloadDelivery first = module.noteWorldUnload("case-shared-a");
        KernelModule.WorldUnloadDelivery second = module.noteWorldUnload("case-shared-b");

        assertEquals(epochsBefore, HostDriveFixture.counter("write.world_epochs_changes"),
            "the delivery opened no generation: the shared source has one writer and it is not this one");
        assertEquals(liveBefore, HostDriveFixture.counter("write.world_epochs_live"),
            "and the live set of that source is where the delivery found it");
        assertEquals(releasedBefore, HostDriveFixture.counter("intent.released"),
            "the delivery took no token back");
        assertEquals(depthBefore, HostDriveFixture.counter("intent.queue_depth"));
        assertEquals(advancesBefore, module.unloadEpochAdvances());
        assertEquals(reclaimsBefore, module.unloadTokenReclaims());
        assertTrue(first.worldEpoch() > 0L && second.worldEpoch() > 0L);
        assertNotEquals(first.worldEpoch(), second.worldEpoch(),
            "the two worlds carry the generations the one shared source handed out");
    }

    @Test
    void aWorldTheStopNeverCarriedIsNamedWhereItCannotBeFilledAndIsNeverAZero() {
        List<String> worlds = List.of("case-absent-live");
        fixture.tick(worlds);
        fixture.stop(worlds);

        KernelModule.WorldUnloadDelivery absent = module.noteWorldUnload("case-never-observed");

        assertEquals("UNLIVE(-1)", absent.oldGenerationCell(),
            "a world the tracked set does not carry is absent, and absent is a refusal of its own name");
        assertEquals("NEVER_RUN", absent.worldEpochCell(),
            "the shared flush never carried the world, so it has no generation to read");
        String row = absent.row();
        assertTrue(row.contains("|worldEpoch=NEVER_RUN|"));
        assertTrue(row.contains("|flushEntry=NEVER_RUN|"));
        assertTrue(row.contains("|flushSteps=-|"));
        assertTrue(row.contains("|reclaim=owner:NOT_EXECUTABLE|byFlush:NOT_EXECUTABLE|"),
            "what the shared flush never took back is named and not counted as nothing");
    }

    @Test
    void theWiringColumnFollowsTheRecordingAndARollbackTakesItBack() {
        assertEquals("WIRED", HostDriveFixture.reading("product.unload.wiring"));

        module.removeUnloadDelivery();

        assertEquals("NOT_WIRED", HostDriveFixture.reading("product.unload.wiring"),
            "a process that records nothing claims no delivery");
    }
}
