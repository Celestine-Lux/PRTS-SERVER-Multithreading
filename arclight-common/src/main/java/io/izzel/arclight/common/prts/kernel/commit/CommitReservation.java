/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The one holder that mints a ticket. It takes the three reservations a controlled write needs
 * before the first side effect — the place the frozen plan gave the line, a ring slot and the room of
 * the terminal record — and it is the only place that ends one: a ticket is filled by
 * {@link #finalize}, given back by {@link #release} or voided by a reset of its lifecycle.
 *
 * <p>Every control operation is confined to the owner thread that bound itself here. A call from
 * another thread is refused and counted: the reservation never steals the thread of the owner, and
 * the ledgers are left exactly where they were.
 *
 * <p>A refusal takes nothing: the ring slot and the room are given back, the three levels read what
 * they read before the call, and no publish action has run. */
public final class CommitReservation {

    /** What one reservation did. A refusal says which check refused it. */
    public record ReserveResult(boolean admitted, RejectCode code, String diagnostic,
                                CommitTicket ticket) {
    }

    /** What one terminal did. */
    public record FinalizeResult(boolean finalized, RejectCode code, String diagnostic,
                                 CommitTicket.Terminal terminal, int successBatch, long unitCount) {
    }

    /** What one give-back did. */
    public record ReleaseResult(boolean released, String diagnostic) {
    }

    /** What a reset of the lifecycle did: how many reservations were voided, and how many lines
     * were publishing and were left alone. */
    public record ResetResult(int voided, int publishing) {
    }

    /** One entry of the bounded terminal ledger: every ticket that ended, with the state it ended
     * in and the units it published. */
    public record TerminalEntry(long ticketId, long effectRef, String state, long planSequence,
                                int position, int unitCount, int successBatch, String note) {
    }

    private enum Phase {
        RESERVED,
        PUBLISHING,
        FINISHED
    }

    private record Refusal(RejectCode code, String diagnostic) {
    }

    private static final int LEDGER_LIMIT = 64;

    private final CommitLog log;
    private final EffectStore store;
    private final CommitLog.OrderSource plans;
    private final CommitNamespaces.Namespace controller;
    private final CommitNamespaces.ResetEpochs epochs;
    private final Map<Long, CommitTicket> tickets = new LinkedHashMap<>();
    private final Map<Long, Phase> phases = new LinkedHashMap<>();
    private final Deque<TerminalEntry> ledger = new ArrayDeque<>();
    private Thread ownerThread = Thread.currentThread();
    private long ticketIds;
    private long reserveSuccess;
    private long finalized;
    private long released;
    private long publishedTotal;
    private long partialTotal;
    private long failedTotal;
    private long unitTotal;
    private long doubleFinalize;
    private long unknownTicket;
    private long crossLogRefusals;
    private long staleRefusals;
    private long offThreadRefusals;
    private long requestRefusals;
    private long reservationRefusals;
    private long resetVoided;
    private long resetDuringPublish;
    private long ledgerEvicted;

    public CommitReservation(CommitLog log, EffectStore store, CommitLog.OrderSource plans,
                             String ownerId, CommitNamespaces.ResetEpochs epochs) {
        this.log = log;
        this.store = store;
        this.plans = plans;
        this.epochs = epochs;
        this.controller = new CommitNamespaces.Namespace(ownerId, CommitNamespaces.Lifetimes.mint(),
            epochs.current(), CommitNamespaces.NO_THREAD);
    }

    /** Binds the thread every control operation of this holder must run on. */
    public void bindOwnerThread(Thread thread) {
        this.ownerThread = thread == null ? Thread.currentThread() : thread;
    }

    public Thread ownerThread() {
        return ownerThread;
    }

    public boolean ownsThread() {
        return Thread.currentThread() == ownerThread;
    }

    public CommitNamespaces.Namespace controller() {
        return new CommitNamespaces.Namespace(controller.ownerId(), controller.lifetime(),
            epochs.current(), CommitNamespaces.NO_THREAD);
    }

    public CommitLog log() {
        return log;
    }

    /** The reset generation of the lifecycle a reservation is minted under. */
    public long epoch() {
        return epochs.current();
    }

    /** Enters the next lifecycle. Called by the controller that reached the owner safe point. */
    public long advanceReset() {
        return epochs.advance();
    }

    public EffectStore store() {
        return store;
    }

    /** Takes the place, the ring slot and the room, or none of them. */
    public ReserveResult reserve(CommitTicket.ReserveRequest request) {
        if (Thread.currentThread() != ownerThread) {
            offThreadRefusals++;
            return new ReserveResult(false, RejectCode.COUNTER_MISSING, "off-owner-thread", null);
        }
        Refusal refusal = validate(request);
        if (refusal != null) {
            requestRefusals++;
            return new ReserveResult(false, refusal.code(), refusal.diagnostic(), null);
        }
        CommitTicket.Covered covered = covered(request);
        CommitLog.RingSlot ringSlot = log.reserveSlot(request.worldId(), request.domainId(),
            request.planSequence(), request.position());
        if (ringSlot == null) {
            reservationRefusals++;
            return new ReserveResult(false, RejectCode.QUEUE_CAP_EXCEEDED, "ring-room", null);
        }
        EffectStore.CapacityResult room = store.reserveCapacity(request.identity().effectRef(),
            covered.maxRecords(), covered.maxBytes());
        if (!room.admitted()) {
            log.releaseSlot(ringSlot);
            reservationRefusals++;
            return new ReserveResult(false, room.code() == null ? RejectCode.QUEUE_CAP_EXCEEDED
                : room.code(), "effect-room", null);
        }
        CommitTicket ticket = new CommitTicket(++ticketIds, log.logLifetime(),
            new CommitLog.Batch(request.worldId(), request.domainId(), request.nodeKey(),
                request.kind(), request.expectedVersion(), request.writeSetDigest(), request.rows()),
            request.identity(), request.planSequence(), request.position(), ringSlot, room.slot(),
            controller());
        tickets.put(ticket.ticketId(), ticket);
        phases.put(ticket.ticketId(), Phase.RESERVED);
        reserveSuccess++;
        return new ReserveResult(true, null, "reserved", ticket);
    }

    private Refusal validate(CommitTicket.ReserveRequest request) {
        if (request == null || request.identity() == null || request.worldId() == null
            || request.domainId() == null || request.worldId().isEmpty() || request.domainId().isEmpty()
            || request.nodeKey() == null || request.nodeKey().isEmpty() || request.kind() == null) {
            return new Refusal(RejectCode.COUNTER_MISSING, "request-incomplete");
        }
        if (store.halted()) {
            return new Refusal(RejectCode.QUEUE_CAP_EXCEEDED, "log-integrity-fault");
        }
        CommitLog.Identity identity = request.identity();
        if (!identity.declaredTyped() || !CommitLog.supportedSchema(identity.schemaVersion())) {
            return new Refusal(RejectCode.VERSION_MISMATCH, "identity-undeclared");
        }
        if (!CommitLog.liveWorldEpoch(identity.worldEpoch())) {
            return new Refusal(RejectCode.VERSION_MISMATCH, "world-epoch-not-live");
        }
        if (identity.authorizationGeneration() != request.expectedVersion()
            || identity.authorizationGeneration() == CommitLog.NO_AUTHORIZATION_GENERATION) {
            return new Refusal(RejectCode.VERSION_MISMATCH, "authorization-mismatch");
        }
        CommitLog.Resolved resolved = plans == null ? null
            : plans.resolve(request.worldId(), request.domainId(), request.nodeKey());
        if (resolved == null || resolved.planSequence() != request.planSequence()
            || resolved.position() != request.position()) {
            return new Refusal(RejectCode.COMMIT_ORDER_VIOLATION, "plan-ref-mismatch");
        }
        if (log.instanceCollision(request.worldId(), request.domainId(), identity)) {
            return new Refusal(RejectCode.VERSION_MISMATCH, "instance-collision");
        }
        if (placeHeld(request.worldId(), request.domainId(), request.planSequence(),
            request.position())) {
            return new Refusal(RejectCode.COMMIT_ORDER_VIOLATION, "duplicate-place");
        }
        for (CommitTicket.Terminal terminal : CommitTicket.Terminal.values()) {
            EffectStore.Effect variant = request.variantOf(terminal);
            if (variant == null || variant.effectRef() != identity.effectRef()
                || EffectStore.normalizedRows(variant.rows()).size() != request.rows()) {
                return new Refusal(RejectCode.VERSION_MISMATCH, "terminal-payload-mismatch");
            }
        }
        return null;
    }

    /** Whether a reservation in flight already holds this place of this pair. */
    public boolean placeHeld(String worldId, String domainId, long planSequence, int position) {
        for (CommitTicket held : tickets.values()) {
            if (held.planSequence() == planSequence && held.position() == position
                && held.batch().worldId().equals(worldId) && held.batch().domainId().equals(domainId)) {
                return true;
            }
        }
        return false;
    }

    /** The bound the reservation charges: every terminal the line may report with every note its
     * outcome column may carry. */
    static CommitTicket.Covered covered(CommitTicket.ReserveRequest request) {
        long bytes = 0L;
        int records = 0;
        for (CommitTicket.Terminal terminal : CommitTicket.Terminal.values()) {
            EffectStore.Effect variant = request.variantOf(terminal);
            records = Math.max(records, EffectStore.normalizedRows(variant.rows()).size());
            for (String note : CommitTicket.NOTES) {
                String label = note.isEmpty() ? terminal.name() : terminal.name() + "|" + note;
                bytes = Math.max(bytes, EffectStore.chargeOf(variant.operationKind(), label,
                    variant.rows()));
            }
        }
        return new CommitTicket.Covered(records, (int) Math.min(Integer.MAX_VALUE, bytes));
    }

    /** Says that the side effect of this ticket is about to run. */
    public boolean beginPublish(CommitTicket ticket) {
        if (ticket == null || phases.get(ticket.ticketId()) != Phase.RESERVED) {
            return false;
        }
        phases.put(ticket.ticketId(), Phase.PUBLISHING);
        return true;
    }

    /** Writes the terminal line of a publishing ticket from the reservations it holds. */
    public FinalizeResult finalize(CommitTicket ticket, CommitTicket.Outcome outcome) {
        if (Thread.currentThread() != ownerThread) {
            offThreadRefusals++;
            return refused(RejectCode.COUNTER_MISSING, "off-owner-thread", null);
        }
        if (ticket == null || outcome == null) {
            unknownTicket++;
            return refused(RejectCode.COUNTER_MISSING, "unknown-ticket", null);
        }
        if (tickets.get(ticket.ticketId()) != ticket) {
            unknownTicket++;
            return refused(RejectCode.COUNTER_MISSING, "unknown-ticket", outcome.terminal());
        }
        if (ticket.logLifetime() != log.logLifetime() || ticket.effectSlot().storeId() != store.storeId()
            || ticket.controller().lifetime() != controller.lifetime()) {
            crossLogRefusals++;
            return refused(RejectCode.COUNTER_MISSING, "cross-log-handle", outcome.terminal());
        }
        if (ticket.controller().resetEpoch() != epochs.current()) {
            staleRefusals++;
            return refused(RejectCode.COUNTER_MISSING, "stale-ticket", outcome.terminal());
        }
        if (phases.get(ticket.ticketId()) == Phase.FINISHED) {
            doubleFinalize++;
            return refused(RejectCode.COUNTER_MISSING, "already-finalized", outcome.terminal());
        }
        if (phases.get(ticket.ticketId()) != Phase.PUBLISHING) {
            requestRefusals++;
            return refused(RejectCode.COMMIT_ORDER_VIOLATION, "not-publishing", outcome.terminal());
        }
        CommitLog.Verdict verdict = log.recordTicket(ticket, outcome);
        if (!verdict.logged()) {
            return refused(verdict.code() == null ? RejectCode.COUNTER_MISSING : verdict.code(),
                "record-refused", outcome.terminal());
        }
        phases.put(ticket.ticketId(), Phase.FINISHED);
        tickets.remove(ticket.ticketId());
        finalized++;
        int successBatch = outcome.terminal() == CommitTicket.Terminal.PUBLISHED ? 1 : 0;
        switch (outcome.terminal()) {
            case PUBLISHED -> publishedTotal++;
            case PARTIAL -> partialTotal++;
            case FAILED -> failedTotal++;
        }
        unitTotal += outcome.unitCount();
        append(new TerminalEntry(ticket.ticketId(), ticket.identity().effectRef(),
            outcome.terminal().name(), ticket.planSequence(), ticket.position(),
            outcome.unitCount(), successBatch, outcome.note()));
        return new FinalizeResult(true, null, "recorded", outcome.terminal(), successBatch,
            outcome.unitCount());
    }

    /** Gives a reservation back without a line. Only a ticket that never published can go back. */
    public ReleaseResult release(CommitTicket ticket, CommitTicket.ReleaseReason reason) {
        if (Thread.currentThread() != ownerThread) {
            offThreadRefusals++;
            return new ReleaseResult(false, "off-owner-thread");
        }
        if (ticket == null || tickets.get(ticket.ticketId()) != ticket) {
            unknownTicket++;
            return new ReleaseResult(false, "unknown-ticket");
        }
        if (ticket.controller().resetEpoch() != epochs.current()) {
            staleRefusals++;
            return new ReleaseResult(false, "stale-ticket");
        }
        Phase phase = phases.get(ticket.ticketId());
        if (phase == Phase.FINISHED) {
            doubleFinalize++;
            return new ReleaseResult(false, "already-finalized");
        }
        if (phase != Phase.RESERVED) {
            requestRefusals++;
            return new ReleaseResult(false, "publishing");
        }
        giveBack(ticket);
        released++;
        append(new TerminalEntry(ticket.ticketId(), ticket.identity().effectRef(),
            "RELEASED", ticket.planSequence(), ticket.position(), 0, 0,
            reason == null ? "" : reason.name()));
        return new ReleaseResult(true, "released");
    }

    /** Asks the frozen plan again for a ticket that has not published. A plan that no longer gives
     * this line its place is not a reason to move the line: the reservation is given back. */
    public ReleaseResult revalidate(CommitTicket ticket) {
        if (Thread.currentThread() != ownerThread) {
            offThreadRefusals++;
            return new ReleaseResult(false, "off-owner-thread");
        }
        if (ticket == null || tickets.get(ticket.ticketId()) != ticket
            || phases.get(ticket.ticketId()) != Phase.RESERVED) {
            return new ReleaseResult(false, "not-reserved");
        }
        CommitLog.Resolved resolved = plans == null ? null
            : plans.resolve(ticket.batch().worldId(), ticket.batch().domainId(), ticket.batch().nodeKey());
        if (resolved != null && resolved.planSequence() == ticket.planSequence()
            && resolved.position() == ticket.position()) {
            return new ReleaseResult(false, "still-planned");
        }
        ReleaseResult gone = release(ticket, CommitTicket.ReleaseReason.PLAN_REBUILT);
        return new ReleaseResult(gone.released(), gone.released() ? "plan-rebuilt" : gone.diagnostic());
    }

    private void giveBack(CommitTicket ticket) {
        store.releaseCapacity(ticket.effectSlot());
        log.releaseSlot(ticket.ringSlot());
        phases.remove(ticket.ticketId());
        tickets.remove(ticket.ticketId());
    }

    private FinalizeResult refused(RejectCode code, String diagnostic, CommitTicket.Terminal terminal) {
        return new FinalizeResult(false, code, diagnostic, terminal, 0, 0L);
    }

    private void append(TerminalEntry entry) {
        ledger.addLast(entry);
        while (ledger.size() > LEDGER_LIMIT) {
            ledger.removeFirst();
            ledgerEvicted++;
        }
    }

    /** Ends the lifecycle: a reservation that never published is given back, a line that is
     * publishing is left alone, and the caller is told how many of each there were. */
    public ResetResult onReset() {
        if (Thread.currentThread() != ownerThread) {
            offThreadRefusals++;
            return new ResetResult(0, publishing());
        }
        List<Long> voided = new ArrayList<>();
        for (Map.Entry<Long, Phase> entry : phases.entrySet()) {
            if (entry.getValue() == Phase.RESERVED) {
                voided.add(entry.getKey());
            }
        }
        for (Long id : voided) {
            CommitTicket ticket = tickets.get(id);
            if (ticket == null) {
                continue;
            }
            giveBack(ticket);
            resetVoided++;
            append(new TerminalEntry(id, ticket.identity().effectRef(), "VOIDED", ticket.planSequence(),
                ticket.position(), 0, 0, "reset"));
        }
        int publishing = publishing();
        resetDuringPublish += publishing;
        return new ResetResult(voided.size(), publishing);
    }

    /** The reservations still in flight, by the state they are in. */
    public int outstanding() {
        return tickets.size();
    }

    public int publishing() {
        int count = 0;
        for (Phase phase : phases.values()) {
            if (phase == Phase.PUBLISHING) {
                count++;
            }
        }
        return count;
    }

    /** Whether every reservation ever taken is finalized, given back, or still in flight. */
    public boolean conservationHolds() {
        return reserveSuccess == finalized + released + outstanding();
    }

    public List<TerminalEntry> terminalLedger() {
        return List.copyOf(ledger);
    }

    public long reserveSuccess() {
        return reserveSuccess;
    }

    public long finalized() {
        return finalized;
    }

    public long releasedCount() {
        return released;
    }

    public long publishedTotal() {
        return publishedTotal;
    }

    public long partialTotal() {
        return partialTotal;
    }

    public long failedTotal() {
        return failedTotal;
    }

    /** The physical units the published lines reported. It is never read out of the rows of a
     * record or the field names it declares. */
    public long unitTotal() {
        return unitTotal;
    }

    public long doubleFinalize() {
        return doubleFinalize;
    }

    public long unknownTicket() {
        return unknownTicket;
    }

    public long crossLogRefusals() {
        return crossLogRefusals;
    }

    public long staleRefusals() {
        return staleRefusals;
    }

    public long offThreadRefusals() {
        return offThreadRefusals;
    }

    public long requestRefusals() {
        return requestRefusals;
    }

    public long reservationRefusals() {
        return reservationRefusals;
    }

    public long resetVoided() {
        return resetVoided;
    }

    public long resetDuringPublish() {
        return resetDuringPublish;
    }

    public long ledgerEvicted() {
        return ledgerEvicted;
    }
}
