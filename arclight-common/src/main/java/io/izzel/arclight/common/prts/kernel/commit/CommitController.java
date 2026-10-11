/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

/** The one control path of a controlled write: validate, reserve, publish, finalize, record. The
 * first two steps happen before the first side effect, so an order refusal or a capacity refusal
 * leaves the publish action uncalled; the last two happen whatever the publish action did, so a
 * published line, a partial one and a failed one each end exactly once.
 *
 * <p>The publish action is a function handed in here. In this build it is a synthetic action and
 * nothing else: no host supplier is bound, no world is written, and the controller has no production
 * caller. The column that says so reads SYNTHETIC.
 *
 * <p>The controller is confined to the owner thread of the reservations it judges, and its reset is a
 * safe point: while a publish action is running, or while a line is publishing, a destructive reset is
 * refused and remembered as pending. It is carried out at the next safe point, after the terminal line
 * of every in-flight reservation was recorded, and the reset generation moves by one only when that
 * happens. A request that arrives from another thread is refused there and taken over to that same
 * safe point instead of running on the caller's thread. */
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
    private volatile boolean pendingReset;
    private long submits;
    private long supplierCalls;
    private long acceptedPublished;
    private long publishedUnits;
    private long refusals;
    private long refusedStarts;
    private long publishFailures;
    private long resetReentrantRefusals;
    private long resetWhilePublishing;
    private long resetOffThread;
    private long resetHandOffs;
    private long resets;
    private long deferredResets;

    /** The owner thread is the one the reservations were bound to and is asked of them on every
     * call: a holder that re-bound would otherwise be judged by a thread the controller remembered. */
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

    /** Runs the whole path for one controlled write, on the owner thread. */
    public SubmitResult submit(CommitTicket.ReserveRequest request) {
        if (!reservations.ownsThread()) {
            refusals++;
            return new SubmitResult(false, null, RejectCode.COUNTER_MISSING, "off-owner-thread", 0, 0L,
                null);
        }
        drainPendingReset();
        submits++;
        CommitReservation.ReserveResult reserved = reservations.reserve(request);
        if (!reserved.admitted()) {
            refusals++;
            return new SubmitResult(false, null, reserved.code(), reserved.diagnostic(), 0, 0L, null);
        }
        CommitTicket ticket = reserved.ticket();
        if (!reservations.beginPublish(ticket)) {
            // The side effect does not run for a line the holder will not start: the reservation goes
            // back rather than holding a slot for a line that never ran, and the caller is told so.
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
        drainPendingReset();
        return new SubmitResult(done.finalized(), done.terminal(), done.code(), done.diagnostic(),
            done.successBatch(), done.unitCount(), ticket);
    }

    private CommitTicket.Outcome publish(CommitTicket ticket, CommitTicket.ReserveRequest request) {
        if (publisher == null) {
            return new CommitTicket.Outcome(CommitTicket.Terminal.FAILED,
                request.failed().operationKind(), request.failed().rows(), 0, "publisher-threw");
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

    /** The owner safe point of a reset: the lifecycle is entered here, and only when nothing is
     * publishing. The caller that owns the log runs its own reset after a granted decision. */
    public ResetDecision enterReset() {
        ResetDecision refusal = refuseReset();
        if (refusal != null) {
            return refusal;
        }
        CommitReservation.ResetResult onReset = reservations.onReset();
        long epoch = reservations.advanceReset();
        pendingReset = false;
        resets++;
        return new ResetDecision(true, "reset-at-safe-point", onReset.voided(), onReset.publishing(),
            epoch);
    }

    /** The same safe point where the controller also ends the lifecycle of the log it was built on. */
    public ResetDecision resetAtSafePoint() {
        ResetDecision decision = enterReset();
        if (decision.granted()) {
            reservations.log().reset();
        }
        return decision;
    }

    private ResetDecision refuseReset() {
        long epoch = epoch();
        if (!reservations.ownsThread()) {
            // Not run here and not dropped either: the request is taken over and lands at the next
            // owner safe point, so the caller knows the generation it just read has not moved.
            resetOffThread++;
            resetHandOffs++;
            pendingReset = true;
            return new ResetDecision(false, "off-owner-thread", 0, reservations.publishing(), epoch);
        }
        if (publishDepth > 0) {
            resetReentrantRefusals++;
            pendingReset = true;
            return new ResetDecision(false, "reentrant-publish", 0, reservations.publishing(), epoch);
        }
        if (reservations.publishing() > 0) {
            resetWhilePublishing++;
            pendingReset = true;
            return new ResetDecision(false, "publishing-in-flight", 0, reservations.publishing(), epoch);
        }
        return null;
    }

    /** The whole reset the module asked for, run once at the owner safe point: a request taken over
     * from another thread and a request refused inside a publish action both land here. */
    public ResetDecision resetCarriedOut() {
        if (!pendingReset) {
            return new ResetDecision(false, "nothing-pending", 0, reservations.publishing(), epoch());
        }
        deferredResets++;
        return resetAtSafePoint();
    }

    /** Ends this controller: nothing new is taken, every reservation that never published is given
     * back, and the items whose side effect has run keep their room and stay readable. */
    public CommitReservation.CloseResult close() {
        return reservations.close();
    }

    private void drainPendingReset() {
        if (!pendingReset || publishDepth > 0 || reservations.publishing() > 0) {
            return;
        }
        deferredResets++;
        resetAtSafePoint();
    }

    public boolean pendingReset() {
        return pendingReset;
    }

    public long epoch() {
        return reservations.epoch();
    }

    public long submits() {
        return submits;
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

    public long resetOffThread() {
        return resetOffThread;
    }

    public long resetHandOffs() {
        return resetHandOffs;
    }

    public long resets() {
        return resets;
    }

    public long deferredResets() {
        return deferredResets;
    }
}
