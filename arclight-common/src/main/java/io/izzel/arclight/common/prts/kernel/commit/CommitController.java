/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

/** The one control path of a controlled write: validate, reserve, publish, finalize, record. The
 * first two steps happen before the first side effect, so an order or a capacity refusal leaves the
 * action uncalled; the last two happen whatever it did, so every line ends exactly once.
 *
 * <p>The action is handed in, and the controller is confined to the owner thread of the reservations
 * it judges. A build without an action refuses every request before the reservation is touched,
 * instead of reporting a run that never happened. Its reset is a safe point: while an action runs or
 * a line publishes, a destructive reset is refused and owed by the caller, and ending the lifecycle
 * of a log is not expressible here at all. */
public final class CommitController {

    /** What one publish action is. It reports the state it reached and the units it published. */
    @FunctionalInterface
    public interface Publisher {

        CommitTicket.Outcome publish(CommitTicket ticket);
    }

    /** What one controlled write did. */
    public record SubmitResult(boolean admitted, CommitTicket.Terminal terminal, RejectCode code,
                               String diagnostic, int successBatch, long unitCount,
                               CommitTicket ticket) {
    }

    /** What one reset of the lifecycle did, or why it did not happen. */
    public record ResetDecision(boolean granted, String reason, int voided, int publishing, long epoch) {
    }

    private final CommitReservation reservations;
    private final Publisher publisher;
    private int publishDepth;
    private long submits;
    private long notWiredRefusals;
    private long supplierCalls;
    private long acceptedPublished;
    private long publishedUnits;
    private long refusals;
    private long refusedStarts;
    private long publishFailures;
    private long resetReentrantRefusals;
    private long resetWhilePublishing;
    private long resetPendingDispositions;
    private long resetOffThread;
    private long resetHandOffs;
    private long resets;
    private long deferredResets;

    /** The owner is asked of the reservations on every call: a holder that re-bound would otherwise
     * be judged by a thread the controller remembered. */
    public CommitController(CommitReservation reservations, Publisher publisher) {
        this.reservations = reservations;
        this.publisher = publisher;
    }

    public CommitReservation reservations() {
        return reservations;
    }

    /** The publish action this controller runs: a synthetic action in this build. */
    public String publisherWiring() {
        return publisher == null ? "NONE" : "SYNTHETIC";
    }

    /** Whether this build has an action to run: bound and able to run are two columns. */
    public boolean executable() {
        return publisher != null;
    }

    /** Runs the whole path for one controlled write, on the owner thread. A build with no action
     * refuses the request after the owner is judged and before anything is moved. */
    public SubmitResult submit(CommitTicket.ReserveRequest request) {
        if (!reservations.ownsThread()) {
            refusals++;
            return new SubmitResult(false, null, RejectCode.COUNTER_MISSING, "off-owner-thread", 0, 0L,
                null);
        }
        if (publisher == null) {
            notWiredRefusals++;
            return new SubmitResult(false, null, RejectCode.NATIVE_UNDECLARED, "not-wired", 0, 0L,
                null);
        }
        submits++;
        CommitReservation.ReserveResult reserved = reservations.reserve(request);
        if (!reserved.admitted()) {
            refusals++;
            return new SubmitResult(false, null, reserved.code(), reserved.diagnostic(), 0, 0L, null);
        }
        CommitTicket ticket = reserved.ticket();
        if (!reservations.beginPublish(ticket)) {
            // A line the holder will not start gives its reservation back instead of holding it.
            reservations.release(ticket, CommitTicket.ReleaseReason.CANCELLED);
            refusals++;
            refusedStarts++;
            return new SubmitResult(false, null, RejectCode.COMMIT_ORDER_VIOLATION,
                "publish-not-started", 0, 0L, ticket);
        }
        CommitTicket.Outcome outcome = publish(ticket, request);
        CommitReservation.FinalizeResult done = reservations.finalize(ticket, outcome);
        if (done.finalized() && done.terminal() == CommitTicket.Terminal.PUBLISHED) {
            acceptedPublished++;
            publishedUnits += done.unitCount();
        }
        return new SubmitResult(done.finalized(), done.terminal(), done.code(), done.diagnostic(),
            done.successBatch(), done.unitCount(), ticket);
    }

    private CommitTicket.Outcome publish(CommitTicket ticket, CommitTicket.ReserveRequest request) {
        if (publisher == null) {
            // Unreachable: submit refuses such a request before it reserves anything. Stood where a
            // missing action was once turned into a terminal line.
            throw new IllegalStateException("a request was admitted with no publish action to run");
        }
        publishDepth++;
        try {
            supplierCalls++;
            CommitTicket.Outcome reported = publisher.publish(ticket);
            return reported != null ? reported : new CommitTicket.Outcome(CommitTicket.Terminal.FAILED,
                request.failed().operationKind(), request.failed().rows(), 0, "publisher-silent");
        } catch (RuntimeException | Error failure) {
            // An action that threw may already have written what it was doing. What it reached is not
            // known here, so the terminal is the partial one: a failure would claim nothing happened.
            publishFailures++;
            return new CommitTicket.Outcome(CommitTicket.Terminal.PARTIAL,
                request.partial().operationKind(), request.partial().rows(), 0, "publisher-threw");
        } finally {
            publishDepth--;
        }
    }

    /** The owner safe point of a reset, entered only when nothing is publishing and no item waits
     * for a disposition. What the holder of the log does with a granted decision comes after it. */
    public ResetDecision enterReset() {
        ResetDecision refusal = refuseReset();
        if (refusal != null) {
            return refusal;
        }
        CommitReservation.ResetResult onReset = reservations.onReset();
        long epoch = reservations.advanceReset();
        resets++;
        return new ResetDecision(true, "reset-at-safe-point", onReset.voided(), onReset.publishing(),
            epoch);
    }

    private ResetDecision refuseReset() {
        long epoch = epoch();
        if (!reservations.ownsThread()) {
            // Taken over rather than dropped: the caller knows the generation it read has not moved.
            resetOffThread++;
            resetHandOffs++;
            deferredResets++;
            return new ResetDecision(false, "off-owner-thread", 0, reservations.publishing(), epoch);
        }
        if (publishDepth > 0) {
            resetReentrantRefusals++;
            deferredResets++;
            return new ResetDecision(false, "reentrant-publish", 0, reservations.publishing(), epoch);
        }
        if (reservations.publishing() > 0) {
            resetWhilePublishing++;
            deferredResets++;
            return new ResetDecision(false, "publishing-in-flight", 0, reservations.publishing(), epoch);
        }
        if (reservations.pendingCount() > 0) {
            // A reset ends the generation such an item was minted under, and with it the window where
            // its owner can still hand it a line. The reset waits for that disposition.
            resetPendingDispositions++;
            deferredResets++;
            return new ResetDecision(false, "pending-dispositions", 0, reservations.publishing(), epoch);
        }
        return null;
    }

    /** Ends this controller: nothing new is taken, every reservation that never published is given
     * back, and the items whose side effect has run keep their room and stay readable. */
    public CommitReservation.CloseResult close() {
        return reservations.close();
    }

    public long epoch() {
        return reservations.epoch();
    }

    public long submits() {
        return submits;
    }

    /** How often a request was refused because this build has no publish action to run it. */
    public long notWiredRefusals() {
        return notWiredRefusals;
    }

    /** How often the publish action was called. In this build that action is synthetic. */
    public long supplierCalls() {
        return supplierCalls;
    }

    public long acceptedPublished() {
        return acceptedPublished;
    }

    /** The physical units the accepted lines reported, summed from the unit count they declared. */
    public long publishedUnits() {
        return publishedUnits;
    }

    public long refusals() {
        return refusals;
    }

    /** The side effects that were not started because the holder refused to begin them. */
    public long refusedStarts() {
        return refusedStarts;
    }

    public long publishFailures() {
        return publishFailures;
    }

    public long resetReentrantRefusals() {
        return resetReentrantRefusals;
    }

    public long resetWhilePublishing() {
        return resetWhilePublishing;
    }

    /** How often a reset was refused for an item that still waits for its terminal line. */
    public long resetPendingDispositions() {
        return resetPendingDispositions;
    }

    public long resetOffThread() {
        return resetOffThread;
    }

    public long resetHandOffs() {
        return resetHandOffs;
    }

    public long resets() {
        return resets;
    }

    /** How often a reset was refused where it was asked for and left to be carried out later. */
    public long deferredResets() {
        return deferredResets;
    }
}
