/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** What the module held at the moment a case of the reset mailbox failed. A red run of one of these
 * cases has been seen on a machine that is not this one, so a failure here has to leave behind what
 * it saw instead of being re-run until it is green. The snapshot is written in the exception handler,
 * which runs where the assertion threw: no cleanup of the case has touched the module yet, and the
 * queue still holds the sources that case actually admitted. It reads and prints, and changes no
 * outcome. */
final class ResetMailboxFlakeWatch implements TestExecutionExceptionHandler, BeforeTestExecutionCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(ResetMailboxFlakeWatch.class);
    private static final String STARTED_AT = "startedAt";

    /** The worker-failure lists the running case fills, so a throw that a worker caught is printed
     * with the rest of the snapshot instead of staying in a list nobody read. */
    private static final List<List<Throwable>> WATCHED = new ArrayList<>();

    /** The rows of the readout a snapshot carries: the four outcomes of the queue path, the two debts
     * the pending flag stands for, and what the chain did with them. */
    private static final List<String> ROWS = List.of(
        "product.commit.reset_requests", "product.commit.reset_hand_offs",
        "product.commit.reset_source", "product.commit.reset_closed_refusals",
        "product.commit.reset_handoffs", "product.commit.resets", "product.commit.resets_deferred",
        "product.commit.reset_pending_dispositions", "product.commit.reset_during_publish",
        "product.commit.reset_reentrant_refusals", "product.commit.reset_voided",
        "product.commit.reset_queued", "product.commit.reset_queue_capacity",
        "product.commit.reset_queue_refusals", "product.commit.reset_queue_sources",
        "product.commit.safe_point_calls", "product.commit.reset_trace",
        "product.commit.reset_trace_cross_thread", "product.commit.chain_sealed",
        "product.commit.chain_sealed_count", "product.commit.chain_sealed_turn",
        "product.commit.chain_dispositions", "product.commit.disposition_count",
        "product.commit.accepting_new_work", "product.commit.reset_composition",
        "product.commit.host_drive_wiring");

    ResetMailboxFlakeWatch() {
    }

    /** Registers one list a case collects worker throws in and hands it straight back, so a case
     * keeps using it as before. */
    static List<Throwable> watchWorkerFailures(List<Throwable> failures) {
        synchronized (WATCHED) {
            WATCHED.add(failures);
        }
        return failures;
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        synchronized (WATCHED) {
            WATCHED.clear();
        }
        context.getStore(NAMESPACE).put(STARTED_AT, Instant.now());
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        String snapshot = snapshot(context, failure);
        System.out.println(snapshot);
        System.err.println(snapshot);
        append(snapshot);
        throw failure;
    }

    private String snapshot(ExtensionContext context, Throwable failure) {
        StringBuilder text = new StringBuilder();
        text.append(System.lineSeparator())
            .append("== reset mailbox snapshot, taken where the case threw ==").append(System.lineSeparator());
        row(text, "case", context.getDisplayName());
        row(text, "caseId", context.getUniqueId());
        row(text, "run", runIndex());
        row(text, "threads", threads());
        row(text, "startedAt", startedAt(context));
        row(text, "failedAt", Instant.now() + " elapsedMs="
            + Duration.between(startedAt(context), Instant.now()).toMillis());
        row(text, "failure", failure.getClass().getName() + ": " + failure.getMessage());
        row(text, "at", firstFrameOf(failure));
        row(text, "workerFailures", workerFailures());
        row(text, "mailbox", mailbox());
        for (String key : ROWS) {
            row(text, "reading." + key, reading(key));
        }
        row(text, "chain", "ownerBound=" + KernelModule.instance().commitOwnerThreadBound()
            + " sealed=" + KernelModule.instance().commitChainSealed()
            + " pendingReset=" + KernelModule.instance().commitResetPending()
            + " dispositions=" + KernelModule.instance().commitChainDispositions().size());
        text.append("== end of snapshot ==").append(System.lineSeparator());
        return text.toString();
    }

    /** The one read every exit of the queue and every counter behind it comes out of. */
    private String mailbox() {
        try {
            KernelModule.Mailbox mailbox = KernelModule.instance().mailbox();
            return "depth=" + mailbox.depth() + " sources=" + mailbox.sources()
                + " pending=" + mailbox.pending() + " inProgress=" + mailbox.inProgress()
                + " calls=" + mailbox.calls() + " handOffs=" + mailbox.handOffs()
                + " merged=" + mailbox.merged() + " admitted=" + mailbox.admitted()
                + " refusedFull=" + mailbox.refusedFull()
                + " refusedClosed=" + mailbox.refusedClosed();
        } catch (Throwable failure) {
            return "unreadable: " + failure;
        }
    }

    private String reading(String key) {
        try {
            for (String row : KernelReadings.export(KernelModule.instance())) {
                if (row.startsWith(key + "=")) {
                    return row.substring(key.length() + 1);
                }
            }
            return "absent";
        } catch (Throwable failure) {
            return "unreadable: " + failure;
        }
    }

    /** How many threads were alive, and which ones: a case that failed while its producers were
     * still running reads differently from one whose producers had all returned. */
    private String threads() {
        List<String> alive = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive()) {
                alive.add(thread.getName() + "(" + thread.getState()
                    + (thread.isDaemon() ? ",daemon" : "") + ")");
            }
        }
        alive.sort(String::compareTo);
        return "active=" + Thread.activeCount() + " alive=" + alive.size() + " " + alive;
    }

    private String workerFailures() {
        List<List<Throwable>> lists;
        synchronized (WATCHED) {
            lists = new ArrayList<>(WATCHED);
        }
        int total = 0;
        StringBuilder text = new StringBuilder();
        for (List<Throwable> list : lists) {
            List<Throwable> copy;
            synchronized (list) {
                copy = new ArrayList<>(list);
            }
            total += copy.size();
            if (!copy.isEmpty()) {
                text.append(copy);
            }
        }
        return lists.size() + " watched list(s), " + total + " failure(s) " + text;
    }

    /** Which run this is. The repeat harness names it; a run that names none is read as the first,
     * and the origin of the value is printed with it so a default is never taken for a measurement. */
    private String runIndex() {
        String named = System.getProperty("rbflake.run");
        if (named == null || named.isBlank()) {
            return "1 source=default(absent)";
        }
        return named + " source=arg(rbflake.run)";
    }

    private Instant startedAt(ExtensionContext context) {
        Instant started = context.getStore(NAMESPACE).get(STARTED_AT, Instant.class);
        return started == null ? Instant.EPOCH : started;
    }

    /** Where the case itself threw: the first frame of this tree, so the line is the one to read
     * rather than the frame of the assertion helper that raised it. */
    private static String firstFrameOf(Throwable failure) {
        StackTraceElement[] frames = failure.getStackTrace();
        for (StackTraceElement frame : frames) {
            if (frame.getClassName().startsWith("io.izzel.arclight")) {
                return frame.toString();
            }
        }
        return frames.length == 0 ? "no frame" : frames[0].toString();
    }

    /** The snapshot also goes to a file, so a red run in a build whose console was not kept still
     * leaves the state behind. The path is the one the build gives, or the one a run names. */
    private void append(String snapshot) {
        String named = System.getProperty("rbflake.snapshot");
        Path path = Path.of(named == null || named.isBlank()
            ? "build/reports/rbflake-diag/snapshots.txt" : named);
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, snapshot, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException failure) {
            System.err.println("snapshot file not written: " + failure);
        }
    }

    private static void row(StringBuilder text, String key, Object value) {
        text.append(String.format("%-34s %s", key, value)).append(System.lineSeparator());
    }
}
