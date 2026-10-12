/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The write path of the close seal against the real surroundings: where the file lands, what is
 * refused before it lands, and which terminal a failure gets. Nothing here starts an instance or
 * touches a world; the module is read, never driven. */
class CloseSealWriterTest {

    private static final String RUN = "20260101-000000-7";
    private static final List<String> WORLDS = List.of("minecraft:overworld", "minecraft:the_nether");
    private static final String PRE_FLUSH = "-";
    private static final String POST_FLUSH =
        "minecraft:overworld:42:3:7:1>2>3>4,minecraft:the_nether:9:0:5:5>6>7>8";

    @TempDir
    Path tmp;

    @AfterEach
    void leaveTheModuleAsItWas() {
        System.clearProperty("arclight.prts.kernel");
        System.clearProperty(CloseSealWriter.DIAG_DIR_PROPERTY);
    }

    @Test
    void theSameFileSystemGetsOneAtomicReplaceAndLeavesNoStageBehind() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        Path file = diag.resolve(RUN).resolve("close-seal.tsv");
        assertTrue(Files.isRegularFile(file), "the record file has to be in the run directory");
        assertEquals(List.of(RUN), list(diag), "no staged file may survive the rename");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(List.of(), CloseSealChecks.columnsAndHashes(text));
        String entry = CloseSealChecks.dataLines(text).get(0);
        assertEquals("promised", CloseSealChecks.field(entry, "primaryAtomic"));
        assertEquals("ATOMIC_MOVE", CloseSealChecks.field(entry, "atomic"));
        assertEquals("-", CloseSealChecks.field(entry, "fallback"));
        assertTrue(writer.path().endsWith("close-seal.tsv"));
        Path foreign = foreignStore(diag);
        Assumptions.assumeTrue(foreign != null, "no second file system to stage across");
        Path target = Files.createDirectories(tmp.resolve("negative")).resolve("close-seal.tsv");
        String problem = CloseSealFiles.create(foreign, target, "x\n");
        assertNotEquals("", problem, "a rename across two file systems must not be promised");
        assertFalse(Files.exists(target), "a refused rename must not leave half a file");
    }

    @Test
    void aCrossFileSystemRunRefusesThePromiseAndLandsInAnIndependentFile() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path foreign = foreignStore(diag);
        Assumptions.assumeTrue(foreign != null, "no second file system to stage across");
        Path fallback = tmp.resolve("fb");
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(fallback, foreign, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertFalse(Files.exists(diag.resolve(RUN).resolve("close-seal.tsv")),
            "the diagnostic directory must stay empty when the promise is refused");
        Path landed = fallback.resolve(RUN).resolve("close-seal.tsv");
        assertTrue(Files.isRegularFile(landed), "the independent fallback file has to exist");
        String text = Files.readString(landed, StandardCharsets.UTF_8);
        assertEquals("cross-filesystem", CloseSealChecks.field(CloseSealChecks.dataLines(text).get(0),
            "fallback"));
        assertEquals("refused", CloseSealChecks.field(CloseSealChecks.dataLines(text).get(0),
            "primaryAtomic"));
        assertEquals(List.of(), CloseSealChecks.fallbackMarked(text));
        assertEquals("cross-filesystem", writer.failureReason());
    }

    @Test
    void aSymbolicLinkAtTheRunDirectoryIsRefused() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Path fallback = tmp.resolve("fb");
        Files.createSymbolicLink(diag.resolve(RUN), elsewhere);
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(fallback, null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertEquals("symlink-escape", writer.failureReason());
        assertFalse(Files.exists(elsewhere.resolve("close-seal.tsv")),
            "a link out of the diagnostic root must not receive the record");
        assertTrue(Files.isRegularFile(fallback.resolve(RUN).resolve("close-seal.tsv")));
    }

    @Test
    void theDiagnosticPathIsNormalisedBeforeItIsUsed() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(root.resolve("unused"));
        String value = root.resolve("unused").resolve("..").resolve("used").toString();
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(root.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), value);
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertTrue(Files.isRegularFile(root.resolve("used").resolve(RUN).resolve("close-seal.tsv")));
        assertFalse(Files.exists(root.resolve("unused").resolve(RUN)));
        assertEquals(root.resolve("used").resolve(RUN).toRealPath().toString(),
            Paths.get(writer.path()).getParent().toRealPath().toString());
    }

    @Test
    void aMissingOrRelativePropertyFallsBackWithItsOwnReason() throws IOException {
        Path fallback = tmp.resolve("fb");
        CloseSealWriter missing = new CloseSealWriter(RUN,
            limits(fallback, null, 1000L, List.of(1L, 1L, 1L)), null);
        entryAndTerminal(missing, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertEquals("diag-dir-property-missing", missing.failureReason());
        CloseSealWriter relative = new CloseSealWriter(RUN,
            limits(fallback, null, 1000L, List.of(1L, 1L, 1L)), "relative/close-seal");
        entryAndTerminal(relative, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertEquals("diag-dir-not-absolute", relative.failureReason());
        String text = Files.readString(fallback.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        assertEquals(List.of(), CloseSealChecks.fallbackMarked(text));
        assertFalse(text.contains("fallback=-"), "a degraded file has to name its reason");
    }

    @Test
    void aFileWhereTheDirectoryShouldBeFallsBack() throws IOException {
        Path diag = tmp.resolve("diag");
        Files.writeString(diag, "not a directory\n");
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertEquals("path-not-a-directory", writer.failureReason());
        assertTrue(Files.isRegularFile(tmp.resolve("fb").resolve(RUN).resolve("close-seal.tsv")));
    }

    @Test
    void anUnwritableDirectoryFallsBack() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("locked"));
        Files.setPosixFilePermissions(diag, PosixFilePermissions.fromString("r-x------"));
        Assumptions.assumeFalse(Files.isWritable(diag),
            "this process may write a read-only directory, so the refusal cannot be shown");
        try {
            CloseSealWriter writer = new CloseSealWriter(RUN,
                limits(tmp.resolve("fb"), null, 400L, List.of(1L, 1L, 1L)), diag.toString());
            entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
            assertEquals("path-not-writable", writer.failureReason());
            assertTrue(Files.isRegularFile(tmp.resolve("fb").resolve(RUN).resolve("close-seal.tsv")));
        } finally {
            Files.setPosixFilePermissions(diag, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aFullDiskIsNamedFromTheFailureAndNotFromTheCallSite() {
        assertEquals("disk-full", CloseSealFiles.reason(new IOException("No space left on device")));
        assertEquals("path-not-writable",
            CloseSealFiles.reason(new IOException("Read-only file system")));
        assertEquals("path-not-a-directory", CloseSealFiles.reason(new IOException("Not a directory")));
        assertEquals("io-error", CloseSealFiles.reason(new IOException("something else")));
        assertEquals("atomic-move-unsupported", CloseSealFiles.reason(
            new java.nio.file.AtomicMoveNotSupportedException("a", "b", "different stores")));
        assertNotEquals("disk-full", CloseSealFiles.reason(new IOException("something else")));
    }

    @Test
    void theRowCapRefusesTheNextRecordAndLeavesWhatWasWritten() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            new CloseSealWriter.Limits(1000L, 3, List.of(1L, 1L, 1L), 4L * 1024L * 1024L, 1, null,
                tmp.resolve("fb")), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        Path file = diag.resolve(RUN).resolve("close-seal.tsv");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(1, CloseSealChecks.dataLines(text).size(), "the capped file keeps the entry row");
        assertEquals(List.of("C1"), CloseSealChecks.stages(text));
        assertEquals("FAILED", writer.status());
        assertEquals("C4_WRITE_FAILED", writer.reason());
        assertEquals("evidence", writer.exit());
        assertTrue(writer.evidenceExit().contains("FILE_CAP_REACHED"));
        assertTrue(String.join(";", writer.consoleHistory()).contains("FILE_CAP_REACHED"),
            "the console is the second path and has to carry the refusal");
    }

    @Test
    void theByteCapRefusesTheFirstRecord() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            new CloseSealWriter.Limits(1000L, 3, List.of(1L, 1L, 1L), 200L, 4096, null,
                tmp.resolve("fb")), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertFalse(Files.exists(diag.resolve(RUN).resolve("close-seal.tsv")));
        assertEquals("NOT_EXECUTABLE", writer.status());
        assertEquals("C1_WRITE_FAILED", writer.reason());
        assertEquals("evidence", writer.exit());
        assertEquals("FILE_CAP_REACHED", writer.evidenceExit());
    }

    @Test
    void everyTerminalIsDistinctAndBooksTheSideThatFailed() throws IOException {
        List<String[]> seen = new ArrayList<>();
        seen.add(c1WriteFailed());
        seen.add(flushFailed());
        seen.add(closeRefused());
        seen.add(unrecordedItems());
        seen.add(c4WriteFailed());
        Set<String> pairs = new TreeSet<>();
        for (String[] item : seen) {
            pairs.add(item[0] + '/' + item[1]);
        }
        assertEquals(5, pairs.size(), "the five terminals have to be five different pairs: " + pairs);
        assertEquals("NOT_EXECUTABLE/C1_WRITE_FAILED", seen.get(0)[0] + '/' + seen.get(0)[1]);
        assertEquals("evidence", seen.get(0)[2]);
        assertEquals("FAILED/FLUSH_FAILED", seen.get(1)[0] + '/' + seen.get(1)[1]);
        assertEquals("core", seen.get(1)[2]);
        assertEquals("PARTIAL/CLOSE_REFUSED", seen.get(2)[0] + '/' + seen.get(2)[1]);
        assertEquals("core", seen.get(2)[2]);
        assertEquals("PARTIAL/UNRECORDED_ITEMS", seen.get(3)[0] + '/' + seen.get(3)[1]);
        assertEquals("core", seen.get(3)[2]);
        assertEquals("FAILED/C4_WRITE_FAILED", seen.get(4)[0] + '/' + seen.get(4)[1]);
        assertEquals("evidence", seen.get(4)[2]);
    }

    @Test
    void aHitDeadlineIsAStateAndDoesNotHoldTheCaller() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("blocked"));
        Files.createDirectories(diag.resolve(RUN).resolve("close-seal.tsv"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            new CloseSealWriter.Limits(1000L, 5, List.of(100_000L, 100_000L, 100_000L, 100_000L,
                100_000L), 4L * 1024L * 1024L, 4096, null, tmp.resolve("fb")), diag.toString());
        long started = System.nanoTime();
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsed < 1500L, "a backoff far past the budget held the caller " + elapsed + " ms");
        assertEquals("NOT_EXECUTABLE", writer.status());
        assertEquals("C1_WRITE_FAILED", writer.reason());
        assertEquals("evidence", writer.exit());
        assertTrue(writer.lastConsole().contains("evidence"));
        assertEquals("C1_WRITE_FAILED+C4_WRITE_FAILED", writer.evidenceExit());
    }

    @Test
    void twoThreadsEnteringAtOnceStillWriteOneEntryRecord() throws Exception {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
                writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
                    CloseSealCriteriaTest.readings(PRE_FLUSH)));
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        writer.flushing();
        writer.closed(new KernelModule.CommitClose(true, "", 0, 0, 0));
        writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
            CloseSealCriteriaTest.readings(POST_FLUSH)));
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        assertEquals(List.of("C1", "C1-DUP", "C4"), CloseSealChecks.stages(text));
        assertEquals(List.of(), CloseSealChecks.oneEntry(text));
        assertEquals(List.of(), CloseSealChecks.appendOnly(text));
        assertEquals(2, writer.eventOrdinal());
    }

    @Test
    void theWriterAndItsFileHelperNeverTakeAWorldOrAPlatformType() {
        assertEquals(List.of(), foreignTypes(CloseSealWriter.class));
        assertEquals(List.of(), foreignTypes(CloseSealFiles.class));
        assertEquals(List.of("net.minecraft.server.MinecraftServer"), foreignTypes(WorldProbe.class));
    }

    @Test
    void aModuleDrivenRunRendersTheRealReadings() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        KernelModule module = KernelModule.instance();
        writer.entry(WORLDS, module);
        writer.flushing();
        writer.closed(module.closeCommitChain());
        writer.terminal(WORLDS, module);
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        assertEquals(List.of(), CloseSealChecks.columnsAndHashes(text));
        assertEquals(List.of(), CloseSealChecks.oneEntry(text));
        assertEquals(List.of(), CloseSealChecks.sealedIdentity(text));
        assertEquals(List.of(), CloseSealChecks.unrecordedNotFolded(text));
        assertEquals(List.of(), CloseSealChecks.statusColumn(text));
        assertEquals(List.of(), CloseSealChecks.deadline(text));
        String entry = CloseSealChecks.dataLines(text).get(0);
        assertTrue(CloseSealChecks.cell(entry, "ownerIdentity")
            .contains("product.commit.owner_thread_source=server.getRunningThread"));
        assertTrue(CloseSealChecks.cell(entry, "wiring").contains("closeOutcome="));
        assertTrue(CloseSealChecks.cell(entry, "sealed").contains("product.commit.seal_discards=0"));
        assertTrue(CloseSealChecks.cell(entry, "worldSet").contains("count=2"));
        assertTrue(CloseSealChecks.cell(entry, "unload").contains("minecraft:overworld=NEVER_RUN"));
    }

    @Test
    void theHappyPathCostsWellUnderTheBudget() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L)), diag.toString());
        long started = System.nanoTime();
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsed < 200L, "the happy path took " + elapsed + " ms");
        assertEquals("OK", writer.status());
        assertEquals("NONE", writer.reason());
        assertEquals("core", writer.exit());
        assertEquals(2, writer.writeSeq());
    }

    private String[] c1WriteFailed() throws IOException {
        Path blocked = Files.createDirectories(tmp.resolve("blocked-c1"));
        Path diag = Files.createDirectories(blocked.resolve("diag"));
        Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("r-x------"));
        Files.setPosixFilePermissions(diag, PosixFilePermissions.fromString("r-x------"));
        Assumptions.assumeFalse(Files.isWritable(blocked) || Files.isWritable(diag),
            "this process may write a read-only directory, so no failure can be forced");
        try {
            CloseSealWriter writer = new CloseSealWriter(RUN,
                new CloseSealWriter.Limits(300L, 3, List.of(1L, 1L, 1L), 4L * 1024L * 1024L, 4096,
                    null, blocked.resolve("fb")), diag.toString());
            entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
            assertEquals("no-fallback-path", writer.failureReason());
            assertFalse(Files.exists(diag.resolve(RUN).resolve("close-seal.tsv")));
            return new String[] {writer.status(), writer.reason(), writer.exit()};
        } finally {
            Files.setPosixFilePermissions(diag, PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private String[] flushFailed() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("flush-failed"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb-flush"), null, 1000L, List.of(1L)), diag.toString());
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        writer.flushing();
        writer.errored(new IllegalStateException("the world loop threw"));
        writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
            CloseSealCriteriaTest.readings(POST_FLUSH)));
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        assertTrue(CloseSealChecks.cell(CloseSealChecks.dataLines(text).get(1), "error")
            .contains("IllegalStateException"));
        return new String[] {writer.status(), writer.reason(), writer.exit()};
    }

    private String[] closeRefused() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("close-refused"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb-close"), null, 1000L, List.of(1L)), diag.toString());
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        writer.flushing();
        writer.closed(new KernelModule.CommitClose(false, "off-owner-thread", 0, 0, 0));
        writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
            CloseSealCriteriaTest.readings(POST_FLUSH)));
        return new String[] {writer.status(), writer.reason(), writer.exit()};
    }

    private String[] unrecordedItems() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("unrecorded"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb-unrecorded"), null, 1000L, List.of(1L)), diag.toString());
        Map<String, String> readings = CloseSealCriteriaTest.readings(POST_FLUSH);
        readings.put("product.commit.close_unrecorded", "2");
        readings.put("product.commit.chain_sealed", "1");
        readings.put("product.commit.chain_sealed_count", "2");
        readings.put("product.commit.chain_sealed_identities",
            "11:sealed-at-close:-:owed:1,12:sealed-at-close:-:owed:1");
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        writer.flushing();
        writer.closed(new KernelModule.CommitClose(true, "", 0, 2, 0));
        writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS, readings));
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        String terminal = CloseSealChecks.dataLines(text).get(1);
        assertTrue(CloseSealChecks.cell(terminal, "unrecorded").contains("close_unrecorded=2"));
        assertTrue(CloseSealChecks.cell(terminal, "sealed")
            .contains("product.commit.chain_sealed_count=2"));
        return new String[] {writer.status(), writer.reason(), writer.exit()};
    }

    private String[] c4WriteFailed() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("c4-failed"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb-c4"), null, 1000L, List.of(1L)), diag.toString());
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        Path file = diag.resolve(RUN).resolve("close-seal.tsv");
        String before = Files.readString(file, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        Assumptions.assumeFalse(Files.isWritable(file),
            "this process may write a read-only file, so the refusal cannot be shown");
        try {
            writer.flushing();
            writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
                CloseSealCriteriaTest.readings(POST_FLUSH)));
            assertEquals(before, Files.readString(file, StandardCharsets.UTF_8),
                "a refused terminal record must not touch the entry record");
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        assertTrue(writer.lastConsole().contains("C4_WRITE_FAILED"));
        return new String[] {writer.status(), writer.reason(), writer.exit()};
    }

    private void entryAndTerminal(CloseSealWriter writer, KernelModule.CommitClose close) {
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        writer.flushing();
        writer.closed(close);
        writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
            CloseSealCriteriaTest.readings(POST_FLUSH)));
    }

    private CloseSealWriter.Limits limits(Path fallbackRoot, Path staging, long budget,
                                          List<Long> backoff) {
        return new CloseSealWriter.Limits(budget, 3, backoff, 4L * 1024L * 1024L, 4096, staging,
            fallbackRoot);
    }

    private static List<String> list(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(path -> names.add(path.getFileName().toString()));
        }
        names.sort(String::compareTo);
        return names;
    }

    private static Path foreignStore(Path local) {
        for (String candidate : List.of("/dev/shm", "/run")) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path) && !CloseSealFiles.sameFileStore(path, local)) {
                return path;
            }
        }
        return null;
    }

    private static List<String> foreignTypes(Class<?> owner) {
        List<String> found = new ArrayList<>();
        for (Method method : owner.getDeclaredMethods()) {
            found.addAll(foreign(method.getReturnType()));
            for (Class<?> parameter : method.getParameterTypes()) {
                found.addAll(foreign(parameter));
            }
        }
        for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                found.addAll(foreign(parameter));
            }
        }
        for (Field field : owner.getDeclaredFields()) {
            found.addAll(foreign(field.getType()));
        }
        found.sort(String::compareTo);
        return found;
    }

    private static List<String> foreign(Class<?> type) {
        List<String> found = new ArrayList<>();
        String name = type.getName();
        if (name.startsWith("net.minecraft.") || name.startsWith("org.bukkit.")) {
            found.add(name);
        }
        return found;
    }

    /** The negative control of the scan above: one method that does take a platform type. */
    static final class WorldProbe {

        void visit(MinecraftServer server) {
            if (server != null) {
                throw new IllegalStateException("never called");
            }
        }
    }
}
