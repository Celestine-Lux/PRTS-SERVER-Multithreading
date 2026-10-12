/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The machine-readable side of the close seal: every criterion is a function from the file text to
 * the list of what it found wrong, so a clean file yields nothing and a broken copy yields something
 * for the criterion that owns it. */
final class CloseSealChecks {

    static final int COLUMNS = CloseSealWriter.COLUMNS.length;
    static final long BUDGET_CEILING = CloseSealWriter.HARD_BUDGET_MILLIS;
    static final long ELAPSED_SLACK = 1500L;

    private CloseSealChecks() {
    }

    static List<String> dataLines(String text) {
        List<String> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int index = 1; index < lines.length; index++) {
            if (!lines[index].isEmpty()) {
                found.add(lines[index]);
            }
        }
        return found;
    }

    static String header(String text) {
        String[] lines = text.split("\n", -1);
        return lines.length == 0 ? "" : lines[0];
    }

    static Map<String, String> cells(String line) {
        String[] parts = line.split("\t", -1);
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < CloseSealWriter.COLUMNS.length && index < parts.length; index++) {
            map.put(CloseSealWriter.COLUMNS[index], parts[index]);
        }
        return map;
    }

    static String cell(String line, String column) {
        return cells(line).getOrDefault(column, "");
    }

    static String field(String line, String key) {
        String receipt = cell(line, "receipt");
        for (String part : receipt.split("\\|")) {
            int at = part.indexOf('=');
            if (at > 0 && part.substring(0, at).equals(key)) {
                return part.substring(at + 1);
            }
        }
        return "";
    }

    static List<String> stages(String text) {
        List<String> found = new ArrayList<>();
        for (String line : dataLines(text)) {
            found.add(cell(line, "stage"));
        }
        return found;
    }

    /** W-10: every increment line describes the whole record file as it stood after its own
     * stage. The digest and the length of a line are read from the same bytes there, so a line
     * whose digest does not recompute from the prefix its own length names is a line about some
     * other object: the entry line stays recomputable after the terminal record was appended, and
     * the terminal line is the file that was left behind. */
    static List<String> receiptSnapshots(String text, List<String> receipts) {
        List<String> found = new ArrayList<>();
        List<String> rows = dataLines(text);
        if (receipts.size() != rows.size()) {
            found.add("increment lines: " + receipts.size() + ", records: " + rows.size());
        }
        StringBuilder prefix = new StringBuilder();
        long previous = -1L;
        for (int index = 0; index < rows.size(); index++) {
            prefix.append(index == 0 ? header(text) : "").append(index == 0 ? "\n" : "")
                .append(rows.get(index)).append('\n');
            if (index >= receipts.size()) {
                continue;
            }
            String[] parts = receipts.get(index).trim().split("\\s+");
            if (parts.length != 4) {
                found.add("increment line " + (index + 1) + " carries " + parts.length + " fields");
                continue;
            }
            long at = prefix.toString().getBytes(StandardCharsets.UTF_8).length;
            String digest = CloseSealWriter.md5(prefix.toString());
            if (!CloseSealFiles.FILE_NAME.equals(parts[1])) {
                found.add("increment line " + (index + 1) + " names " + parts[1]);
            }
            if (!("writeSeq=" + (index + 1)).equals(parts[3])) {
                found.add("increment line " + (index + 1) + " carries " + parts[3]);
            }
            long length = number(parts[2].startsWith("bytes=") ? parts[2].substring(6) : parts[2],
                -1L);
            if (length != at) {
                found.add("increment line " + (index + 1) + " says " + parts[2]
                    + " where the record file stood at " + at);
            } else if (length <= previous) {
                found.add("increment line " + (index + 1) + " does not move past the one before it");
            }
            previous = length;
            if (!digest.equals(parts[0])) {
                found.add("increment line " + (index + 1) + " carries " + parts[0]
                    + " where the record file's own digest is " + digest);
            }
        }
        return found;
    }

    /** K-24: every line carries the full column set and its own row hash recomputes. */
    static List<String> columnsAndHashes(String text) {
        List<String> found = new ArrayList<>();
        List<String> all = new ArrayList<>();
        all.add(header(text));
        all.addAll(dataLines(text));
        for (String line : all) {
            String[] parts = line.split("\t", -1);
            if (parts.length != COLUMNS) {
                found.add("row has " + parts.length + " columns, expected " + COLUMNS);
                continue;
            }
            String prefix = line.substring(0, line.lastIndexOf('\t'));
            String expected = CloseSealWriter.md5(prefix).substring(0, 16);
            if (!expected.equals(parts[parts.length - 1])) {
                found.add("row hash " + parts[parts.length - 1] + " does not recompute to " + expected);
            }
        }
        return found;
    }

    /** K-01: the file only grows; the sequence is 1..n with no repeat and no step back. */
    static List<String> appendOnly(String text) {
        List<String> found = new ArrayList<>();
        int expected = 1;
        for (String line : dataLines(text)) {
            long seq = number(cell(line, "writeSeq"), -1L);
            if (seq != expected) {
                found.add("writeSeq " + seq + " where " + expected + " was due");
            }
            expected++;
        }
        return found;
    }

    /** K-02, K-03, K-05: one entry record, the repeats named as such, one run id, stages in order. */
    static List<String> oneEntry(String text) {
        List<String> found = new ArrayList<>();
        int entries = 0;
        String runId = null;
        int last = 0;
        for (String line : dataLines(text)) {
            String stage = cell(line, "stage");
            if ("C1".equals(stage)) {
                entries++;
            }
            if (stage.startsWith("C1") && !"C1".equals(stage) && !"C1-DUP".equals(stage)) {
                found.add("stage " + stage + " is neither the entry record nor a repeat");
            }
            if (runId == null) {
                runId = cell(line, "runId");
            } else if (!runId.equals(cell(line, "runId"))) {
                found.add("row carries runId " + cell(line, "runId") + ", run is " + runId);
            }
            int rank = "C4".equals(stage) ? 2 : 1;
            if (rank < last) {
                found.add("stage " + stage + " arrives after the terminal record");
            }
            last = Math.max(last, rank);
        }
        if (entries != 1) {
            found.add("entry records: " + entries + ", expected exactly one");
        }
        for (String line : dataLines(text)) {
            if ("C1-DUP".equals(cell(line, "stage"))
                && "-".equals(field(line, "note"))) {
                found.add("a repeat entry record carries no reason");
            }
        }
        return found;
    }

    /** K-03: one run id throughout, and stages that only move forward. */
    static List<String> stagesMonotone(String text) {
        List<String> found = new ArrayList<>();
        String runId = null;
        int last = -1;
        for (String line : dataLines(text)) {
            String stage = cell(line, "stage");
            String current = cell(line, "runId");
            if (stage.isEmpty()) {
                found.add("row carries no stage");
            }
            if (runId == null) {
                runId = current;
            } else if (!runId.equals(current)) {
                found.add("row carries runId " + current + ", the run is " + runId);
            }
            int rank = "C4".equals(stage) ? 2 : ("C1".equals(stage) || "C1-DUP".equals(stage) ? 1 : -1);
            if (rank < 0) {
                found.add("stage " + stage + " is outside the three");
                continue;
            }
            if (rank < last) {
                found.add("stage " + stage + " arrives after a stage that follows it");
            }
            last = Math.max(last, rank);
        }
        return found;
    }

    /** K-04: the entry record is still there, byte for byte, before the terminal record. */
    static List<String> entryNotReplaced(String text) {
        List<String> found = new ArrayList<>();
        String entry = null;
        int entryIndex = -1;
        List<String> rows = dataLines(text);
        for (int index = 0; index < rows.size(); index++) {
            if ("C1".equals(cell(rows.get(index), "stage"))) {
                entry = rows.get(index);
                entryIndex = index;
            }
        }
        if (entry == null) {
            found.add("no entry record at all");
            return found;
        }
        int copies = 0;
        int terminalIndex = -1;
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).equals(entry)) {
                copies++;
            }
            if ("C4".equals(cell(rows.get(index), "stage"))) {
                terminalIndex = index;
            }
        }
        if (copies != 1) {
            found.add("the entry record appears " + copies + " times");
        }
        if (terminalIndex >= 0 && entryIndex > terminalIndex) {
            found.add("the entry record arrives after the terminal record");
        }
        if (text.indexOf(entry) != text.lastIndexOf(entry)) {
            found.add("the entry record appears more than once in the file");
        }
        return found;
    }

    /** K-07: the five stage states, in the order the path takes them. */
    static List<String> phases(String text) {
        List<String> found = new ArrayList<>();
        for (String line : dataLines(text)) {
            String phases = field(line, "phases");
            if (phases.isEmpty()) {
                found.add("row carries no stage path");
                continue;
            }
            int last = -1;
            for (String step : phases.split(">")) {
                String name = step.split("@")[0];
                int rank = rank(name);
                if (rank < 0) {
                    found.add("stage state " + name + " is outside the five");
                    continue;
                }
                if (rank < last) {
                    found.add("stage state " + name + " arrives out of order");
                }
                last = Math.max(last, rank);
            }
            String phase = field(line, "phase");
            if (rank(phase) < 0) {
                found.add("row names stage state " + phase + ", which is outside the five");
            }
        }
        return found;
    }

    private static int rank(String name) {
        for (int index = 0; index < CloseSealWriter.PHASES.length; index++) {
            if (CloseSealWriter.PHASES[index].equals(name)) {
                return index;
            }
        }
        return -1;
    }

    /** K-08: the budget is a ceiling of one second and a hit deadline is a state, never a pass. */
    static List<String> deadline(String text) {
        List<String> found = new ArrayList<>();
        for (String line : dataLines(text)) {
            long budget = number(field(line, "budgetMs"), -1L);
            if (budget <= 0L || budget > BUDGET_CEILING) {
                found.add("budget " + budget + " ms is outside 1.." + BUDGET_CEILING);
            }
            String state = field(line, "deadline");
            if (!"WITHIN".equals(state) && !"EXCEEDED".equals(state)) {
                found.add("deadline state " + state + " is neither WITHIN nor EXCEEDED");
            }
            long elapsed = number(field(line, "elapsedMs"), -1L);
            long ceiling = "WITHIN".equals(state) ? BUDGET_CEILING : ELAPSED_SLACK;
            if (elapsed < 0L || elapsed > ceiling) {
                found.add("elapsed " + elapsed + " ms is past " + ceiling + " with state " + state);
            }
        }
        return found;
    }

    /** K-15, K-16: the terminal name is one of the closed set and names which side failed. */
    static List<String> terminals(String text) {
        List<String> found = new ArrayList<>();
        for (String line : dataLines(text)) {
            String status = field(line, "rowStatus");
            String reason = field(line, "rowReason");
            String exit = field(line, "rowExit");
            if (!"C4".equals(cell(line, "stage"))) {
                if (!"-".equals(status) || !"-".equals(exit)) {
                    found.add("an entry record carries the terminal " + status + '/' + exit);
                }
                continue;
            }
            if (!contains(CloseSealWriter.STATUSES, status)) {
                found.add("terminal status " + status + " is outside the four");
            }
            if (!contains(CloseSealWriter.REASONS, reason)) {
                found.add("terminal reason " + reason + " is outside the closed set");
            }
            if (!"core".equals(exit) && !"evidence".equals(exit)) {
                found.add("terminal exit " + exit + " is neither the business nor the evidence side");
            }
            boolean evidence = reason.endsWith("WRITE_FAILED") || "FILE_CAP_REACHED".equals(reason);
            if (evidence != "evidence".equals(exit)) {
                found.add("reason " + reason + " is booked on the " + exit + " side");
            }
            if ("C4".equals(cell(line, "stage")) && !contains(CloseSealWriter.STATUSES,
                cell(line, "status"))) {
                found.add("terminal record status " + cell(line, "status") + " is outside the four");
            }
        }
        return found;
    }

    static List<String> statusColumn(String text) {
        List<String> found = new ArrayList<>();
        for (String line : dataLines(text)) {
            String stage = cell(line, "stage");
            String status = cell(line, "status");
            if ("C4".equals(stage)) {
                if (!contains(CloseSealWriter.STATUSES, status)) {
                    found.add("terminal record status " + status + " is outside the four");
                }
            } else if (!"-".equals(status)) {
                found.add("entry record carries terminal status " + status);
            }
        }
        return found;
    }

    /** K-17: the seal identity is complete and every discard is counted. */
    static List<String> sealedIdentity(String text) {
        List<String> found = new ArrayList<>();
        for (String line : dataLines(text)) {
            String sealed = cell(line, "sealed");
            for (String key : new String[] {"product.commit.chain_sealed",
                "product.commit.chain_sealed_count", "product.commit.chain_sealed_identities",
                "product.commit.seal_discards"}) {
                if (!sealed.contains(key + "=")) {
                    found.add("sealed cell is missing " + key);
                }
            }
            if (!sealed.contains("product.commit.seal_discards=0")) {
                found.add("seal discards are not zero on this row");
            }
            String count = value(sealed, "product.commit.chain_sealed_count");
            String identities = value(sealed, "product.commit.chain_sealed_identities");
            int expected = "-".equals(identities) || identities.isEmpty() ? 0
                : identities.split(",").length;
            if (!"-".equals(identities) && number(count, -1) != expected) {
                found.add("sealed count " + count + " disagrees with " + expected + " identities");
            }
        }
        return found;
    }

    /** K-18: an unwired chain is a missing column, never a zero. */
    static List<String> unrecordedNotFolded(String text) {
        List<String> found = new ArrayList<>();
        for (String line : dataLines(text)) {
            String wiring = value(cell(line, "wiring"), "product.commit.controller_wiring");
            String scope = value(cell(line, "unrecorded"), "scope");
            if (CloseSealWriter.NOT_WIRED.equals(wiring)
                && !CloseSealWriter.NOT_WIRED.equals(scope)) {
                found.add("an unwired chain is read as scope " + scope + " instead of NOT_WIRED");
            }
            if (!CloseSealWriter.NOT_WIRED.equals(wiring)
                && !"WIRED".equals(scope)) {
                found.add("a wired chain is read as scope " + scope);
            }
        }
        return found;
    }

    /** K-19: the four flush steps of every world are readable and in order. */
    static List<String> flushOrder(String text) {
        List<String> found = new ArrayList<>();
        for (String line : dataLines(text)) {
            String rows = value(cell(line, "flush"), "product.commit.stop_flush_rows");
            if (rows.isEmpty() || "-".equals(rows)) {
                continue;
            }
            for (String row : rows.split(",")) {
                String[] parts = row.split(":");
                if (parts.length < 5) {
                    found.add("flush row " + row + " carries no four step stamps");
                    continue;
                }
                String[] steps = parts[parts.length - 1].split(">");
                if (steps.length != 4) {
                    found.add("flush row " + row + " does not carry four steps");
                    continue;
                }
                long previous = -1L;
                for (String step : steps) {
                    long value = number(step, -1L);
                    if (value == 0L) {
                        found.add("flush row " + row + " took a step without a stamp");
                    }
                    if (value <= previous) {
                        found.add("flush row " + row + " steps are not strictly increasing");
                    }
                    previous = value;
                }
            }
        }
        return found;
    }

    /** K-23: a degraded write names why it degraded, inside the file it degraded into. */
    static List<String> fallbackMarked(String text) {
        List<String> found = new ArrayList<>();
        for (String line : dataLines(text)) {
            if (field(line, "fallback").isEmpty()) {
                found.add("row carries no fallback state");
            }
        }
        return found;
    }

    /** K-15: the world set is readable on both records and the terminal one still matches. */
    static List<String> worldSet(String text) {
        List<String> found = new ArrayList<>();
        for (String line : dataLines(text)) {
            String value = cell(line, "worldSet");
            if (!value.contains("count=") || !value.contains("ids=") || !value.contains("sameAsC1=")) {
                found.add("world set cell " + value + " is incomplete");
            }
            if ("C4".equals(cell(line, "stage")) && !value.contains("sameAsC1=1")) {
                found.add("the terminal world set differs from the entry world set");
            }
        }
        return found;
    }

    /** K-06: the entry record is pre-close and the terminal record post-flush, and they differ. */
    static List<String> preClosePostClose(String text) {
        List<String> found = new ArrayList<>();
        String entry = null;
        String terminal = null;
        for (String line : dataLines(text)) {
            if ("C1".equals(cell(line, "stage"))) {
                entry = line;
            }
            if ("C4".equals(cell(line, "stage"))) {
                terminal = line;
            }
        }
        if (entry == null || terminal == null) {
            found.add("the pair of records is incomplete");
            return found;
        }
        if (field(entry, "phases").contains("FLUSHING")
            || field(entry, "phases").contains("AFTER_CLOSE")) {
            found.add("the entry record was taken after the flush or the close had started");
        }
        if (!field(terminal, "phases").contains("FLUSHING")) {
            found.add("the terminal record does not show the flush stage");
        }
        if (cell(entry, "flush").equals(cell(terminal, "flush"))) {
            found.add("entry and terminal carry the same flush cell");
        }
        return found;
    }

    static String value(String cell, String key) {
        for (String part : cell.split("\\|")) {
            int at = part.indexOf('=');
            if (at > 0 && part.substring(0, at).equals(key)) {
                return part.substring(at + 1);
            }
        }
        return "";
    }

    static long number(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    static boolean contains(String[] values, String value) {
        for (String candidate : values) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
