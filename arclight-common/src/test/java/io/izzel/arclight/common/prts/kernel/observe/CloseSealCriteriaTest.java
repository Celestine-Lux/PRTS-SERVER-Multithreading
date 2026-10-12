/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every criterion of the close seal, and the broken copy that has to make it fail. The clean file
 * is built once from frozen snapshots; each mutation breaks exactly the property its criterion
 * owns, and the pair is printed as a table so the two lists can be compared by machine. */
class CloseSealCriteriaTest {

    private static final String RUN = "20260101-000000-1";
    private static final List<String> WORLDS = List.of("minecraft:overworld", "minecraft:the_nether");
    private static final String PRE_FLUSH = "-";
    private static final String POST_FLUSH =
        "minecraft:overworld:42:3:7:1>2>3>4,minecraft:the_nether:9:0:5:5>6>7>8";

    @TempDir
    Path tmp;

    private record Case(String id, String criterion, Function<String, List<String>> check,
                        String negative, UnaryOperator<String> mutate) {
    }

    private List<Case> criteria() {
        return List.of(
            new Case("K-01", "the file only grows and the sequence is 1 to n",
                CloseSealChecks::appendOnly, "N-01 two rows share one write sequence",
                text -> editCell(text, 1, "writeSeq", "1")),
            new Case("K-02", "exactly one entry record per run",
                CloseSealChecks::oneEntry, "N-02 the entry record is renamed to a terminal one",
                text -> editCell(text, 0, "stage", "C4")),
            new Case("K-03", "the stages only move forward",
                CloseSealChecks::stagesMonotone, "N-03 the two records are swapped",
                text -> swapRows(text, 0, 1)),
            new Case("K-04", "the entry record is never replaced",
                CloseSealChecks::entryNotReplaced, "N-04 the entry record is dropped",
                text -> dropRow(text, 0)),
            new Case("K-05", "the entry record is pre close and the terminal one post flush",
                CloseSealChecks::preClosePostClose, "N-05 the entry record already shows the flush",
                text -> editField(text, 0, "phases",
                    "C1_NOT_WRITTEN@0>C1_WRITTEN@1>FLUSHING@2>C4_NOT_WRITTEN@3")),
            new Case("K-06", "the five stage states are readable and in order",
                CloseSealChecks::phases, "N-06 two stage states are out of order",
                text -> editField(text, 1, "phases",
                    "C1_NOT_WRITTEN@0>C1_WRITTEN@1>AFTER_CLOSE@2>FLUSHING@3>C4_NOT_WRITTEN@4")),
            new Case("K-07", "the write budget is a ceiling of one second",
                CloseSealChecks::deadline, "N-07 the budget is raised past the ceiling",
                text -> editField(text, 1, "budgetMs", "2000")),
            new Case("K-08", "the terminal name is distinct and books the side that failed",
                CloseSealChecks::terminals, "N-08 the terminal is booked on the wrong side",
                text -> editField(text, 1, "rowExit", "evidence")),
            new Case("K-09", "only the terminal record carries a terminal status",
                CloseSealChecks::statusColumn, "N-09 the entry record carries a terminal status",
                text -> editCell(text, 0, "status", "OK")),
            new Case("K-10", "the seal identity is complete and discards are zero",
                CloseSealChecks::sealedIdentity, "N-10 the discard counter is removed",
                text -> editCell(text, 0, "sealed",
                    valueOf(text, 0, "sealed").replace("|product.commit.seal_discards=0", ""))),
            new Case("K-11", "an unwired chain is a missing column and never a zero",
                CloseSealChecks::unrecordedNotFolded, "N-11 an unwired chain is read as wired",
                text -> editCell(text, 0, "wiring",
                    "product.commit.controller_wiring=NOT_WIRED|product.commit.close_entry="
                        + "host:server-stopping|product.commit.binding_refusal=-"
                        + "|product.commit.close_calls=1|closeOutcome=not-wired|closeScope=WIRED")),
            new Case("K-12", "the four flush steps are readable and strictly increasing",
                CloseSealChecks::flushOrder, "N-12 the flush steps run backwards",
                text -> editCell(text, 1, "flush",
                    valueOf(text, 1, "flush").replace("1>2>3>4", "4>3>2>1"))),
            new Case("K-13", "every row carries the full column set and its own hash",
                CloseSealChecks::columnsAndHashes, "N-13 one row hash is altered",
                text -> editRaw(text, 1, "rowHash", "0000000000000000")),
            new Case("K-14", "a degraded write names why it degraded",
                CloseSealChecks::fallbackMarked, "N-14 the fallback state is removed",
                text -> editCell(text, 1, "receipt",
                    valueOf(text, 1, "receipt").replace("|fallback=-|", "|"))),
            new Case("K-15", "the world set is readable and the terminal one still matches",
                CloseSealChecks::worldSet, "N-15 the terminal world set is marked different",
                text -> editCell(text, 1, "worldSet",
                    valueOf(text, 1, "worldSet").replace("sameAsC1=1", "sameAsC1=0"))));
    }

    @Test
    void everyCriterionHoldsOnTheCleanFileAndFailsOnItsBrokenCopy() throws IOException {
        String clean = cleanFile();
        List<Case> cases = criteria();
        for (Case item : cases) {
            assertEquals(List.of(), item.check().apply(clean),
                item.id() + " on the clean file: " + item.check().apply(clean));
        }
        for (Case item : cases) {
            String broken = item.mutate().apply(clean);
            assertFalse(broken.equals(clean), item.id() + " mutation changed nothing");
            assertFalse(item.check().apply(broken).isEmpty(),
                item.id() + " did not notice " + item.negative());
        }
        System.out.println("CRITERIA=" + cases.size() + " NEGATIVES=" + cases.size());
        for (Case item : cases) {
            System.out.println("PAIR\t" + item.id() + "\t" + item.negative() + "\t" + item.criterion());
        }
    }

    @Test
    void aSecondEntryWritesARepeatRecordAndNeverASecondEntryRecord() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("repeat"));
        CloseSealWriter writer = writer(dir);
        writer.entry(snapshot("C1", PRE_FLUSH));
        writer.entry(snapshot("C1", PRE_FLUSH));
        writer.flushing();
        writer.closed(new KernelModule.CommitClose(true, "", 0, 0, 0));
        writer.terminal(snapshot("C4", POST_FLUSH));
        String text = Files.readString(dir.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        assertEquals(List.of("C1", "C1-DUP", "C4"), CloseSealChecks.stages(text));
        assertEquals(List.of(), CloseSealChecks.oneEntry(text));
        assertEquals(List.of(), CloseSealChecks.appendOnly(text));
        assertEquals(List.of(), CloseSealChecks.entryNotReplaced(text));
        String broken = editCell(text, 1, "stage", "C1");
        assertFalse(CloseSealChecks.oneEntry(broken).isEmpty(),
            "a second record named as the entry record has to be noticed");
        String unreasoned = editField(text, 1, "note", "-");
        assertFalse(CloseSealChecks.oneEntry(unreasoned).isEmpty(),
            "a repeat without a reason has to be noticed");
    }

    private CloseSealWriter writer(Path dir) {
        return new CloseSealWriter(RUN, limits(dir), dir.toString());
    }

    private CloseSealWriter.Limits limits(Path dir) {
        return new CloseSealWriter.Limits(1000L, 3, List.of(50L, 200L, 500L),
            4L * 1024L * 1024L, 4096, null, dir.resolve("fallback"));
    }

    private String cleanFile() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("clean"));
        CloseSealWriter writer = writer(dir);
        writer.entry(snapshot("C1", PRE_FLUSH));
        writer.flushing();
        writer.closed(new KernelModule.CommitClose(true, "", 0, 0, 0));
        writer.terminal(snapshot("C4", POST_FLUSH));
        return Files.readString(dir.resolve(RUN).resolve("close-seal.tsv"), StandardCharsets.UTF_8);
    }

    private CloseSealWriter.Snapshot snapshot(String stage, String flushRows) {
        return new CloseSealWriter.Snapshot(stage, WORLDS, readings(flushRows));
    }

    static Map<String, String> readings(String flushRows) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("product.commit.controller_wiring", "HOST_CLOSE_CONTROLLER");
        map.put("product.commit.close_unrecorded", "0");
        map.put("product.commit.chain_sealed", "0");
        map.put("product.commit.chain_sealed_count", "0");
        map.put("product.commit.chain_sealed_turn", "-1");
        map.put("product.commit.chain_sealed_identities", "-");
        map.put("product.commit.chain_dispositions", "-");
        map.put("product.commit.seal_discards", "0");
        map.put("product.commit.pending_dispositions", "-");
        map.put("product.commit.pending", "0");
        map.put("product.commit.attention", "0");
        map.put("product.commit.stop_flush_rows", flushRows);
        map.put("product.commit.stop_flush_sessions", PRE_FLUSH.equals(flushRows) ? "0" : "2");
        map.put("product.commit.stop_flush_worlds",
            PRE_FLUSH.equals(flushRows) ? "-" : "minecraft:overworld,minecraft:the_nether");
        map.put("product.commit.stop_flush_steps", PRE_FLUSH.equals(flushRows) ? "-" : "5>6>7>8");
        map.put("product.commit.stop_flush_ordered", PRE_FLUSH.equals(flushRows) ? "-" : "1");
        map.put("product.commit.stop_flush_order",
            PRE_FLUSH.equals(flushRows) ? "NOT_WIRED" : "flush-before-close");
        map.put("product.commit.stop_flush_refusals", "0");
        map.put("product.commit.stop_flush_refusal", "-");
        map.put("product.commit.close_calls", PRE_FLUSH.equals(flushRows) ? "0" : "1");
        map.put("product.commit.close_entry", "host:server-stopping");
        map.put("product.commit.binding_refusal", "-");
        map.put("product.commit.owner_thread_source", "server.getRunningThread");
        map.put("product.commit.owner_thread_bound", "1");
        map.put("product.commit.owner_thread_id", "238");
        map.put("product.commit.owner_thread_named", "228");
        map.put("product.commit.owner_thread_mismatches", "0");
        map.put("product.commit.state_entries", "0");
        map.put("product.commit.finished_entries", "0");
        map.put("product.commit.ledger_evicted", "0");
        map.put("product.commit.terminal_ledger_entries", "0");
        map.put("product.commit.terminal_ledger", "-");
        map.put("product.commit.effect_unresolved_terminals", "0");
        map.put("product.commit.chain_reserved", "0");
        map.put("product.commit.chain_finalized", "0");
        map.put("product.commit.chain_released", "0");
        map.put("product.commit.chain_in_flight", "0");
        map.put("product.commit.chain_conservation", "1");
        map.put("product.commit.chain_capacity_reserved", "0");
        map.put("product.commit.chain_capacity_finalized", "0");
        map.put("product.commit.chain_capacity_released", "0");
        map.put("intent.queue_depth", "0");
        map.put("intent.released", "1");
        map.put("arena.claims", "0");
        map.put("arena.releases", "0");
        map.put("arena.pinned", "0");
        map.put("arena.generation_bumps", "0");
        map.put("arena.stale_releases", "0");
        map.put("arena.repeat_releases", "0");
        map.put("arena.quarantined_slots", "0");
        map.put("arena.pin_pairs_hold", "1");
        map.put("write.world_epochs_tracked", "1");
        map.put("write.world_epochs_live", "2");
        map.put("write.world_epochs_changes", "2");
        return map;
    }

    static List<String> rows(String text) {
        return new ArrayList<>(CloseSealChecks.dataLines(text));
    }

    static String rebuild(String text, List<String> rows) {
        StringBuilder builder = new StringBuilder(CloseSealChecks.header(text)).append('\n');
        for (String row : rows) {
            builder.append(row).append('\n');
        }
        return builder.toString();
    }

    static String valueOf(String text, int row, String column) {
        return CloseSealChecks.cell(rows(text).get(row), column);
    }

    static String editCell(String text, int row, String column, String value) {
        List<String> all = rows(text);
        String[] parts = all.get(row).split("\t", -1);
        parts[indexOf(column)] = value;
        all.set(row, recompute(String.join("\t", parts)));
        return rebuild(text, all);
    }

    static String editField(String text, int row, String key, String value) {
        List<String> all = rows(text);
        String[] parts = all.get(row).split("\t", -1);
        int at = indexOf("receipt");
        parts[at] = swap(parts[at], key, value);
        all.set(row, recompute(String.join("\t", parts)));
        return rebuild(text, all);
    }

    static String dropRow(String text, int row) {
        List<String> all = rows(text);
        all.remove(row);
        return rebuild(text, all);
    }

    static String swapRows(String text, int left, int right) {
        List<String> all = rows(text);
        String held = all.get(left);
        all.set(left, all.get(right));
        all.set(right, held);
        return rebuild(text, all);
    }

    static String editRaw(String text, int row, String column, String value) {
        List<String> all = rows(text);
        String[] parts = all.get(row).split("\t", -1);
        parts[indexOf(column)] = value;
        all.set(row, String.join("\t", parts));
        return rebuild(text, all);
    }

    private static String swap(String cell, String key, String value) {
        List<String> parts = new ArrayList<>();
        boolean placed = false;
        for (String part : cell.split("\\|", -1)) {
            int at = part.indexOf('=');
            if (at > 0 && part.substring(0, at).equals(key)) {
                if (value == null) {
                    continue;
                }
                parts.add(key + '=' + value);
                placed = true;
                continue;
            }
            parts.add(part);
        }
        if (!placed && value != null) {
            parts.add(key + '=' + value);
        }
        return String.join("|", parts);
    }

    private static String recompute(String row) {
        String prefix = row.substring(0, row.lastIndexOf('\t'));
        return prefix + '\t' + CloseSealWriter.md5(prefix).substring(0, 16);
    }

    private static int indexOf(String column) {
        for (int index = 0; index < CloseSealWriter.COLUMNS.length; index++) {
            if (CloseSealWriter.COLUMNS[index].equals(column)) {
                return index;
            }
        }
        throw new IllegalArgumentException("unknown column " + column);
    }

    @Test
    void theColumnSetIsTheOneTheHeaderNames() throws IOException {
        String clean = cleanFile();
        assertEquals(CloseSealWriter.COLUMNS.length,
            CloseSealChecks.header(clean).split("\t", -1).length);
        assertEquals(List.of(), CloseSealChecks.columnsAndHashes(clean));
        assertTrue(CloseSealChecks.header(clean).startsWith("schema\trunId\twriteSeq"));
    }
}
