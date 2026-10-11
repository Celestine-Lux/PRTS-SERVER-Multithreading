/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.List;

/** One reservation taken before the first side effect, and the only thing a terminal line may be
 * written from: the frozen order of the line, the two slots the reservation holds, the lifecycle
 * handle it was minted under and the identity it publishes. It is immutable, spent once, and it
 * carries no thread: the lifecycle handle is the owner, the lifetime and the reset generation.
 *
 * <p>Its room covers every terminal the line may report with every note its outcome column may
 * carry ({@link #NOTES}), and a record that grows past that bound faults. */
public record CommitTicket(long ticketId, long logLifetime, CommitLog.Batch batch,
                           CommitLog.Identity identity, long planSequence, int position,
                           CommitLog.RingSlot ringSlot, EffectStore.CapacitySlot effectSlot,
                           CommitNamespaces.Namespace controller) {

    /** The notes the outcome column may carry beside the state of the terminal. */
    public static final List<String> NOTES = List.of("", "reset-during-publish", "plan-rebuilt",
        "publisher-threw", "publisher-silent");

    public CommitTicket {
        if (batch == null || identity == null || ringSlot == null || effectSlot == null
            || controller == null) {
            throw new IllegalArgumentException("a ticket needs a batch, an identity, two slots and a "
                + "lifecycle handle");
        }
        controller = withoutThread(controller);
        identity = new CommitLog.Identity(identity.schemaVersion(), identity.instanceId(),
            identity.worldEpoch(), identity.chunkPos(), identity.targetStatus(),
            identity.authorizationGeneration(), identity.effectRef(), withoutThread(identity.producer()),
            withoutThread(identity.controller()));
    }

    private static CommitNamespaces.Namespace withoutThread(CommitNamespaces.Namespace namespace) {
        return new CommitNamespaces.Namespace(namespace.ownerId(), namespace.lifetime(),
            namespace.resetEpoch(), CommitNamespaces.NO_THREAD);
    }

    public long ringSlotTicket() {
        return ringSlot.ticket();
    }

    public long effectStoreId() {
        return effectSlot.storeId();
    }

    public long effectRef() {
        return identity.effectRef();
    }

    public String worldId() {
        return batch.worldId();
    }

    public String domainId() {
        return batch.domainId();
    }

    public String nodeKey() {
        return batch.nodeKey();
    }

    public int rows() {
        return batch.rows();
    }

    public EffectStore.Effect effectOf(Outcome outcome) {
        return outcome.asEffect(effectRef());
    }

    /** The three terminal records a reservation may end in. */
    public enum Terminal {
        PUBLISHED,
        PARTIAL,
        FAILED
    }

    /** Why a reservation was given back without a line. */
    public enum ReleaseReason {
        CANCELLED,
        PLAN_REBUILT,
        LIFECYCLE_ENDED
    }

    /** What one reservation asks for: the frozen place of the line, the identity it publishes
     * under, and the payload of each terminal it may reach. */
    public record ReserveRequest(String worldId, String domainId, String nodeKey,
                                 CommitLog.Batch.Kind kind, long planSequence, int position,
                                 long expectedVersion, long writeSetDigest, int rows,
                                 CommitLog.Identity identity, CommitNamespaces.Namespace controller,
                                 EffectStore.Effect published, EffectStore.Effect partial,
                                 EffectStore.Effect failed) {

        public List<EffectStore.Effect> variants() {
            return List.of(published, partial, failed);
        }

        public EffectStore.Effect variantOf(Terminal terminal) {
            return switch (terminal) {
                case PUBLISHED -> published;
                case PARTIAL -> partial;
                case FAILED -> failed;
            };
        }
    }

    /** The room a reservation promises: the largest terminal the line may report. */
    public record Covered(int maxRecords, int maxBytes) {
    }

    /** What a publish action reported. The unit count is its own number: it is never read out of
     * the rows of the record or the field names it declares. */
    public record Outcome(Terminal terminal, String operationKind, List<String> rows, int unitCount,
                          String note) {

        public Outcome {
            operationKind = operationKind == null ? "" : operationKind;
            rows = List.copyOf(rows == null ? List.of() : rows);
            note = note == null ? "" : note;
        }

        public String label() {
            return note.isEmpty() ? terminal.name() : terminal.name() + "|" + note;
        }

        public EffectStore.Effect asEffect(long effectRef) {
            return new EffectStore.Effect(effectRef, operationKind, label(), rows);
        }
    }
}
