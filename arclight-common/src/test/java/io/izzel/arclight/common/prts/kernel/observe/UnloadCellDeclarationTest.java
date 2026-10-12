/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The unload cell of the close record: what it declares about the delivery family and what it may
 * never claim. The record is copied before any world is unloaded, so this cell cannot carry a
 * delivery and says so; a process that records no delivery keeps the default, and a rollback that
 * drops the recording leaves the cell where it was. */
class UnloadCellDeclarationTest {

    private static final String RUN = "20260101-000000-9";
    private static final List<String> WORLDS = List.of("minecraft:overworld", "minecraft:the_nether");

    @TempDir
    Path tmp;

    @AfterEach
    void leaveTheModuleAsItWas() {
        KernelModule.instance().removeUnloadDelivery();
    }

    /** Renders one entry and one terminal record into a directory of its own and reads the unload
     * cell of the entry back, so the two states are compared as they are written. */
    private String unloadCell() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag-" + System.nanoTime()));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            new CloseSealWriter.Limits(1000L, 1, List.of(1L), 1L << 20, 16, null, null), diag.toString());
        KernelModule module = KernelModule.instance();
        writer.entry(WORLDS, module);
        writer.terminal(WORLDS, module);
        String text = Files.readString(diag.resolve(RUN).resolve(CloseSealFiles.FILE_NAME),
            StandardCharsets.UTF_8);
        return CloseSealChecks.cell(CloseSealChecks.dataLines(text).get(0), "unload");
    }

    @Test
    void aProcessThatRecordsDeliveriesDeclaresItselfFrozenBeforeThemAndClaimsNone() throws IOException {
        KernelModule.instance().installUnloadDelivery();

        String cell = unloadCell();

        assertTrue(cell.contains("unloadSource=record-frozen-before-delivery"),
            "the record is copied before the delivery and says so instead of claiming a bit");
        assertTrue(cell.contains("minecraft:overworld=PENDING_AFTER_RECORD"));
        assertFalse(cell.contains("TRIGGERED"), "no world cell of this record may carry a delivered word");
        assertFalse(cell.contains("=DELIVERED"));
    }

    @Test
    void aProcessThatRecordsNoneKeepsTheDefaultAndARollbackReturnsToIt() throws IOException {
        String before = unloadCell();
        assertTrue(before.contains("unloadSource=default-never-run"));
        assertTrue(before.contains("minecraft:overworld=NEVER_RUN"));

        KernelModule.instance().installUnloadDelivery();
        assertTrue(unloadCell().contains("unloadSource=record-frozen-before-delivery"));

        KernelModule.instance().removeUnloadDelivery();
        assertTrue(unloadCell().contains("unloadSource=default-never-run"),
            "dropping the recording leaves the cell where it was before it was installed");
    }
}
