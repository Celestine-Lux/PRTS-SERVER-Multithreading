/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/** The single write entry of one tick. Every producer that reaches a commit reports it here, the log
 * resolves which step of which plan ordered it, and it accepts the report only while the order of
 * each (world, domain) pair keeps rising. The buffers are one ring per pair; the traversal that
 * closes the tick walks them in a fixed order and folds what it read, which is what a replayed run is
 * compared on. Nothing here sorts: the order is the plan's, and a report that contradicts it is
 * counted and dropped rather than reordered.
 *
 * <p>A report may also declare a schema version, the identity of the instance it published and the
 * effect record it points at. That path takes the ring slot and the room of the record before it
 * stores anything, and it copies the record into the replay before it releases the reference, while
 * a report of the plain path keeps writing exactly the columns it always did. */
public final class CommitLog {

    /** One commit a producer reached. The batch is the only shape that reaches the log, so no
     * producer can hand it a pre-ordered sequence of its own. */
    public record Batch(String worldId, String domainId, String nodeKey, Kind kind,
                        long expectedVersion, long writeSetDigest, int rows) {

        /** What the producer did with the write. The log records the kind; it never rewrites it. */
        public enum Kind {
            APPLY,
            MERGE,
            INTENT,
            RETRY,
            DROP
        }

        public Batch {
            if (worldId == null) {
                throw new IllegalArgumentException("a commit needs a world");
            }
            if (domainId == null || domainId.isEmpty()) {
                throw new IllegalArgumentException("a commit needs a domain");
            }
            if (kind == null) {
                throw new IllegalArgumentException("a commit needs a kind");
            }
        }
    }

    /** The schema a line declares. Version 0 is the plain protocol: the plan order, the position
     * and the write set of a step. Version 1 adds the typed columns. A line that declares nothing
     * carries {@link #SCHEMA_UNDECLARED} and is never read as version 0. */
    public static final int SCHEMA_UNDECLARED = -1;
    public static final int SCHEMA_LEGACY = 0;
    public static final int SCHEMA_TYPED = 1;

    /** The declared default of the instance column: no product instance is numbered zero. */
    public static final long NO_INSTANCE = 0L;

    /** The declared default of the effect column: a line that points at no record. */
    public static final long NO_EFFECT = 0L;

    /** The declared default of the world generation column. */
    public static final long NO_WORLD_EPOCH = 0L;

    /** The declared default of the chunk column, read only under a declared schema. */
    public static final long NO_CHUNK_POSITION = 0L;

    /** The declared default of the authorization column: a write right that was not carried. */
    public static final long NO_AUTHORIZATION_GENERATION = 0L;

    /** Whether this build knows a schema version. */
    public static boolean supportedSchema(int version) {
        return version == SCHEMA_LEGACY || version == SCHEMA_TYPED;
    }

    /** Whether a world generation column names a world that is live. The tracked world set gives an
     * absent world a negative generation and an untracked one zero, so a generation that names a
     * world is positive, and the column is read for more than "not the default". */
    public static boolean liveWorldEpoch(long worldEpoch) {
        return worldEpoch > NO_WORLD_EPOCH;
    }

    /** The typed columns of one line. A line of the plain path carries {@link Identity#NONE}, which
     * declares nothing: no schema, no instance, no effect. */
    public record Identity(int schemaVersion, long instanceId, long worldEpoch, long chunkPos,
                           String targetStatus, long authorizationGeneration, long effectRef,
                           CommitNamespaces.Namespace producer,
                           CommitNamespaces.Namespace controller) {

        public static final Identity NONE = new Identity(SCHEMA_UNDECLARED, NO_INSTANCE, NO_WORLD_EPOCH,
            NO_CHUNK_POSITION, "", NO_AUTHORIZATION_GENERATION, NO_EFFECT,
            CommitNamespaces.Namespace.NONE, CommitNamespaces.Namespace.NONE);

        public Identity {
            targetStatus = targetStatus == null ? "" : targetStatus;
            producer = producer == null ? CommitNamespaces.Namespace.NONE : producer;
            controller = controller == null ? CommitNamespaces.Namespace.NONE : controller;
        }

        /** Whether every column a versioned line has to declare is there. A missing column is a
         * refusal, not a default that reads as a value. */
        public boolean declaredTyped() {
            return schemaVersion == SCHEMA_TYPED && instanceId != NO_INSTANCE
                && liveWorldEpoch(worldEpoch) && !targetStatus.isEmpty()
                && authorizationGeneration != NO_AUTHORIZATION_GENERATION && effectRef != NO_EFFECT
                && producer.declared();
        }
    }

    /** One versioned report: the plain report, the columns it declares and the effect it points at. */
    public record TypedBatch(Batch batch, Identity identity, EffectStore.Effect effect) {
    }

    /** One step of the versioned projection: the plain step, the typed columns of the line, the
     * columns of the batch it came from and the effect record copied while the ring was read. The
     * batch columns are carried so a comparison can read the relation between the two halves of the
     * line: the authorization generation is the expected version, the rows are the units of the
     * record, and the node is the logical instance of the frozen plan. A null effect could not be
     * resolved. */
    public record TypedStep(long planSequence, int position, long writeSetDigest, String worldId,
                            String domainId, String nodeKey, long expectedVersion, int rows,
                            Identity identity, Entry.Disposition disposition,
                            EffectStore.Recorded effect) {

        public boolean effectResolved() {
            return effect != null;
        }
    }

    /** What closing one tick read in the versioned projection: the steps of the lines of the same
     * traversal that declared no version, and the typed steps with the record each line pointed at.
     * A versioned side that carries plain steps as well read two protocols in one tick and is
     * refused by a comparison instead of being read as one of them. */
    public record SchemaReplay(int schemaVersion, List<Entry.Step> plainSteps,
                               List<TypedStep> typedSteps, int effectMissing) {

        public SchemaReplay {
            plainSteps = List.copyOf(plainSteps == null ? List.of() : plainSteps);
            typedSteps = List.copyOf(typedSteps == null ? List.of() : typedSteps);
        }
    }

    /** One ring slot handed out before anything is stored in it. The handle carries the log
     * lifetime and the reset generation it was handed out under, so a handle of an earlier lifecycle,
     * of another log or of another pair is answered as unknown instead of releasing a later slot. */
    public record RingSlot(String worldId, String domainId, long ticket, long lifetime,
                           long resetEpoch) {
    }

    /** The one entry a producer reaches. A producer with no sink bound keeps its own path unchanged;
     * a producer with one reports what it did and is answered whether that was the planned order. The
     * answer is a verdict, not an interception. */
    @FunctionalInterface
    public interface Sink {

        void reach(Batch batch);
    }

    /** Where the order of a step comes from. The log asks; it never keeps a plan of its own. */
    public interface OrderSource {

        Resolved resolve(String worldId, String domainId, String nodeKey);
    }

    /** The order one commit was given by the planning period. A {@code null} answer means no plan
     * ordered this key. */
    public record Resolved(long planSequence, int position, boolean intent) {
    }

    /** One line of the log: the order the step had, what the producer did and what the log decided. */
    public record Entry(long sequence, long tickIndex, long planSequence, int position,
                        String worldId, String domainId, String nodeKey, long handle, Batch.Kind kind,
                        long expectedVersion, long writeSetDigest, int rows, Disposition disposition,
                        RejectCode code, boolean intent, Identity identity) {

        /** The line of a report of the plain path: the same columns as before, with the versioned
         * ones at their declared defaults. */
        public Entry(long sequence, long tickIndex, long planSequence, int position, String worldId,
                     String domainId, String nodeKey, long handle, Batch.Kind kind, long expectedVersion,
                     long writeSetDigest, int rows, Disposition disposition, RejectCode code,
                     boolean intent) {
            this(sequence, tickIndex, planSequence, position, worldId, domainId, nodeKey, handle, kind,
                expectedVersion, writeSetDigest, rows, disposition, code, intent, Identity.NONE);
        }

        /** What the log did with one reached commit. */
        public enum Disposition {
            ACCEPTED,
            MERGED,
            INTENT,
            DROPPED,
            RETRIED
        }

        /** The pair a replayed log is compared on: the order the plan assigned and the fold of the
         * values written under it. */
        public record Step(long planSequence, int position, long writeSetDigest) {
        }

        public Step step() {
            return new Step(planSequence, position, writeSetDigest);
        }
    }

    /** What the log did with one report. */
    public record Verdict(boolean logged, Entry.Disposition disposition, RejectCode code,
                          long sequence, int position) {
    }

    /** What closing one tick read. The steps are the pairs a replayed run is compared on. */
    public record Replay(long tickIndex, int loggedSteps, int orderViolations, boolean orderMatches,
                         long logDigest, List<Entry.Step> steps, int watermarks) {

        public Replay {
            steps = List.copyOf(steps);
        }

        /** Asked of the run that happened and of the run that was replayed: zero means the two wrote
         * the same values in the same order. */
        public static int compare(Replay left, Replay right) {
            if (left == null || right == null) {
                return -1;
            }
            int differences = Math.abs(left.loggedSteps() - right.loggedSteps());
            int shared = Math.min(left.steps().size(), right.steps().size());
            for (int index = 0; index < shared; index++) {
                Entry.Step a = left.steps().get(index);
                Entry.Step b = right.steps().get(index);
                if (a.planSequence() != b.planSequence() || a.position() != b.position()
                    || a.writeSetDigest() != b.writeSetDigest()) {
                    differences++;
                }
            }
            return differences;
        }
    }

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private final IntSupplier ringCapacity;
    private final OrderSource source;
    private final Map<String, CommitRing> rings = new LinkedHashMap<>();
    private final Map<String, Long> lastPlanOfRing = new LinkedHashMap<>();
    private final Map<String, Integer> lastPositionOfRing = new LinkedHashMap<>();
    private long sequence;
    private long tickIndex;
    private long accepted;
    private long merged;
    private long intents;
    private long dropped;
    private long retried;
    private long orderViolations;
    private long refusedFull;
    private long unplanned;
    private long ticks;
    private int watermarks;
    private Replay last;
    private long firstDivergencePosition = -1L;
    private final EffectStore effects;
    private final long lifetime = CommitNamespaces.Lifetimes.mint();
    private final Map<Long, RingSlot> heldSlots = new LinkedHashMap<>();
    private final Map<String, Integer> slotsByKey = new LinkedHashMap<>();
    private final Map<String, String> instanceNamespaces = new LinkedHashMap<>();
    private SchemaReplay lastSchema;
    private SchemaReplay lastReset;
    private long slotTickets;
    private long resetEpoch;
    private long reservationsTaken;
    private long reservationsReleased;
    private long reservationsConsumed;
    private long reservationRefusals;
    private long typedStored;
    private long typedRefused;
    private long identityCollisions;
    private long effectMissingSteps;

    public CommitLog(IntSupplier ringCapacity, OrderSource source) {
        this(ringCapacity, source, null);
    }

    /** The same log with an effect store bound. A log with no store cannot execute a versioned
     * report: it answers a refusal instead of writing a line whose effect nothing could resolve,
     * which is the state of the running server today. */
    public CommitLog(IntSupplier ringCapacity, OrderSource source, EffectStore effects) {
        this.ringCapacity = ringCapacity;
        this.source = source;
        this.effects = effects;
    }

    /** Opens one tick. The rings keep what a previous tick did not read, so a slow reader never loses
     * an entry to a new tick. */
    public void beginTick(long tick) {
        this.tickIndex = tick;
        firstDivergencePosition = -1L;
    }

    /** The one entry. */
    public Verdict reach(Batch batch) {
        if (batch == null) {
            return new Verdict(false, Entry.Disposition.DROPPED, RejectCode.COUNTER_MISSING, 0L, -1);
        }
        Resolved resolved = source == null ? null
            : source.resolve(batch.worldId(), batch.domainId(), batch.nodeKey());
        long position = resolved == null ? -1 : resolved.position();
        long planSequence = resolved == null ? 0L : resolved.planSequence();
        boolean intent = resolved != null ? resolved.intent()
            : batch.kind() == Batch.Kind.INTENT;
        Entry.Disposition disposition;
        RejectCode code = null;
        if (resolved == null && !intent) {
            // A write no plan ordered is not put in front of the planned ones: it is counted as
            // unplanned and dropped from the ordered sequence, and the reason is readable.
            unplanned++;
            disposition = Entry.Disposition.DROPPED;
            code = RejectCode.COMMIT_ORDER_VIOLATION;
            dropped++;
        } else if (resolved != null && !ascending(batch.worldId(), batch.domainId(), planSequence,
            position)) {
            orderViolations++;
            if (firstDivergencePosition < 0L) {
                firstDivergencePosition = position;
            }
            disposition = Entry.Disposition.DROPPED;
            code = RejectCode.COMMIT_ORDER_VIOLATION;
            dropped++;
        } else {
            if (resolved != null) {
                lastPlanOfRing.put(key(batch.worldId(), batch.domainId()), planSequence);
                lastPositionOfRing.put(key(batch.worldId(), batch.domainId()), (int) position);
            }
            disposition = dispositionOf(batch.kind());
            switch (disposition) {
                case ACCEPTED -> accepted++;
                case MERGED -> merged++;
                case INTENT -> intents++;
                case RETRIED -> retried++;
                case DROPPED -> dropped++;
            }
        }
        Entry entry = new Entry(++sequence, tickIndex, planSequence, (int) position,
            batch.worldId(), batch.domainId(), batch.nodeKey() == null ? "" : batch.nodeKey(), 0L,
            batch.kind(), batch.expectedVersion(), batch.writeSetDigest(), batch.rows(),
            disposition, code, intent);
        CommitRing ring = ring(batch.worldId(), batch.domainId());
        if (freeSlots(ring) <= 0) {
            // The room a ring has is what its capacity leaves after the slots already handed out, so
            // a plain report never takes a slot a versioned commit is holding. With no slot held the
            // answer is the one the ring itself gives, refusal code and counters included.
            refusedFull++;
            dropped++;
            return new Verdict(false, Entry.Disposition.DROPPED, RejectCode.QUEUE_CAP_EXCEEDED,
                sequence, (int) position);
        }
        CommitRing.Push push = ring.push(entry);
        if (!push.stored()) {
            refusedFull++;
            dropped++;
            return new Verdict(false, Entry.Disposition.DROPPED, push.code(), sequence,
                (int) position);
        }
        watermarks = Math.max(watermarks, ring.depth());
        return new Verdict(true, disposition, code, sequence, (int) position);
    }

    /** Closes the tick: the rings are walked in a fixed order and what they read is folded. */
    public Replay closeTick() {
        ticks++;
        List<String> keys = new ArrayList<>(rings.keySet());
        keys.sort(String::compareTo);
        List<Entry.Step> steps = new ArrayList<>();
        List<Entry.Step> plain = new ArrayList<>();
        List<TypedStep> typed = new ArrayList<>();
        long hash = OFFSET_BASIS;
        int read = 0;
        for (String key : keys) {
            CommitRing ring = rings.get(key);
            Entry entry;
            while ((entry = ring.pop()) != null) {
                steps.add(entry.step());
                hash = fold(hash, entry.planSequence() + ":" + entry.position() + ":"
                    + entry.writeSetDigest() + ":" + entry.disposition());
                if (entry.identity().effectRef() != NO_EFFECT) {
                    typed.add(typedStep(entry));
                } else {
                    plain.add(entry.step());
                }
                read++;
            }
        }
        Replay replay = new Replay(tickIndex, read, (int) orderViolations, orderViolations == 0L,
            hash, steps, watermarks);
        last = replay;
        lastSchema = new SchemaReplay(typed.isEmpty() ? SCHEMA_UNDECLARED : SCHEMA_TYPED, plain, typed,
            unresolvedOf(typed));
        return replay;
    }

    private static int unresolvedOf(List<TypedStep> typed) {
        int unresolved = 0;
        for (TypedStep step : typed) {
            if (!step.effectResolved()) {
                unresolved++;
            }
        }
        return unresolved;
    }

    /** The replay of the newest closed tick. */
    public Replay last() {
        return last;
    }

    /** The versioned projection of the newest closed tick: the version the records declare, the
     * plain steps of the same traversal, and the typed steps with the record each line pointed at. */
    public SchemaReplay lastTyped() {
        return lastSchema;
    }

    /** The effect store bound to this log, or null when nothing is bound. */
    public EffectStore effects() {
        return effects;
    }

    /** The number of steps two runs of the same input differ in; -1 means one of them is missing. */
    public static int compareRuns(Replay left, Replay right) {
        return Replay.compare(left, right);
    }

    /** The versioned report: the plain report plus the columns it declares and the effect it points
     * at. The ring slot and the room of the effect are taken before anything is stored, and a report
     * that is refused leaves neither a line nor a record behind.
     *
     * <p>A refusal of this path takes no report number of its own: the ordinal counts the reports the
     * log took, and a report that never reached the store is not one of them. */
    public Verdict reachTyped(TypedBatch typed) {
        if (typed == null || typed.batch() == null || typed.identity() == null
            || typed.effect() == null) {
            return typedRefusal(RejectCode.COUNTER_MISSING, -1);
        }
        if (effects == null) {
            return typedRefusal(RejectCode.COUNTER_MISSING, -1);
        }
        Batch batch = typed.batch();
        Identity identity = typed.identity();
        // A versioned line declares every column and its two halves agree: the authorization
        // generation is the expected version of the batch, the line names the record it carries, and
        // it names a node of the frozen plan it was ordered from.
        if (identity.schemaVersion() != SCHEMA_TYPED || !identity.declaredTyped()
            || identity.effectRef() != typed.effect().effectRef()
            || identity.authorizationGeneration() != batch.expectedVersion()
            || batch.nodeKey() == null || batch.nodeKey().isEmpty()) {
            return typedRefusal(RejectCode.VERSION_MISMATCH, -1);
        }
        Resolved resolved = source == null ? null
            : source.resolve(batch.worldId(), batch.domainId(), batch.nodeKey());
        if (resolved == null) {
            return typedRefusal(RejectCode.COMMIT_ORDER_VIOLATION, -1);
        }
        int position = resolved.position();
        if (!ascending(batch.worldId(), batch.domainId(), resolved.planSequence(), position)) {
            return typedRefusal(RejectCode.COMMIT_ORDER_VIOLATION, position);
        }
        String instance = instanceOf(identity);
        String instanceKey = key(batch.worldId(), batch.domainId()) + "/" + identity.instanceId();
        String held = instanceNamespaces.get(instanceKey);
        if (held != null && !held.equals(instance)) {
            // One instance number names one instance: the lifecycle, the chunk and the published
            // state it carries. Another lifecycle, another chunk or another state under that number
            // is a second instance wearing the name of the first, and the number alone says nothing.
            identityCollisions++;
            return typedRefusal(RejectCode.VERSION_MISMATCH, position);
        }
        CommitRing ring = ring(batch.worldId(), batch.domainId());
        RingSlot slot = reserveSlot(batch.worldId(), batch.domainId());
        if (slot == null) {
            return typedRefusal(RejectCode.QUEUE_CAP_EXCEEDED, position);
        }
        EffectStore.Result charged = effects.reserve(typed.effect());
        if (!charged.admitted()) {
            releaseSlot(slot);
            return typedRefusal(charged.code(), position);
        }
        EffectStore.Result stored = effects.commit(charged.slot(), typed.effect());
        if (!stored.admitted()) {
            releaseSlot(slot);
            return typedRefusal(stored.code(), position);
        }
        if (stored.record().content().size() != batch.rows()) {
            // The batch declares how many rows its write set has and the record carries exactly
            // those: a line whose halves count different things is not stored at all.
            giveBack(identity.effectRef(), slot);
            return typedRefusal(RejectCode.VERSION_MISMATCH, position);
        }
        Entry.Disposition disposition = dispositionOf(batch.kind());
        Entry entry = new Entry(++sequence, tickIndex, resolved.planSequence(), position,
            batch.worldId(), batch.domainId(), batch.nodeKey() == null ? "" : batch.nodeKey(), 0L,
            batch.kind(), batch.expectedVersion(), batch.writeSetDigest(), batch.rows(), disposition,
            null, resolved.intent(), identity);
        CommitRing.Push push = ring.push(entry);
        if (!push.stored()) {
            // A slot was held for this line, so a ring that still refuses it is a defect and not a
            // shortage: the record and the reservation go with it and no half line stays behind.
            giveBack(identity.effectRef(), slot);
            return typedRefusal(push.code(), position);
        }
        consumeSlot(slot);
        lastPlanOfRing.put(key(batch.worldId(), batch.domainId()), resolved.planSequence());
        lastPositionOfRing.put(key(batch.worldId(), batch.domainId()), position);
        instanceNamespaces.put(instanceKey, instance);
        switch (disposition) {
            case ACCEPTED -> accepted++;
            case MERGED -> merged++;
            case INTENT -> intents++;
            case RETRIED -> retried++;
            case DROPPED -> dropped++;
        }
        typedStored++;
        watermarks = Math.max(watermarks, ring.depth());
        return new Verdict(true, disposition, null, sequence, position);
    }

    /** Hands out one ring slot of a pair before anything is stored in it. While the slot is held the
     * pair has that much less room, so a plain report cannot take it; a slot that is never filled is
     * given back by {@link #releaseSlot}. */
    public RingSlot reserveSlot(String worldId, String domainId) {
        CommitRing ring = ring(worldId, domainId);
        if (freeSlots(ring) <= 0) {
            reservationRefusals++;
            return null;
        }
        RingSlot slot = new RingSlot(worldId, domainId, ++slotTickets, lifetime, resetEpoch);
        heldSlots.put(slot.ticket(), slot);
        slotsByKey.merge(key(worldId, domainId), 1, Integer::sum);
        reservationsTaken++;
        return slot;
    }

    /** Gives back a slot that was not filled. The handle is answered against the slot this log saved
     * under its ticket, whole: a handle of another log, of another pair or of a lifecycle this log
     * has left is not a slot of this one, and giving it back would release a later reservation. */
    public boolean releaseSlot(RingSlot slot) {
        if (slot == null) {
            return false;
        }
        RingSlot held = heldSlots.get(slot.ticket());
        if (held == null || !held.equals(slot) || held.lifetime() != lifetime
            || held.resetEpoch() != resetEpoch) {
            return false;
        }
        heldSlots.remove(held.ticket());
        dropHeld(held);
        reservationsReleased++;
        return true;
    }

    /** Fills a held slot with the line that was reserved for it. */
    private void consumeSlot(RingSlot slot) {
        RingSlot held = heldSlots.get(slot.ticket());
        if (held == null || !held.equals(slot)) {
            return;
        }
        heldSlots.remove(held.ticket());
        dropHeld(held);
        reservationsConsumed++;
    }

    /** Drops the room one saved slot held, by the pair the log saved with it. */
    private void dropHeld(RingSlot held) {
        String key = key(held.worldId(), held.domainId());
        Integer count = slotsByKey.get(key);
        if (count == null || count <= 1) {
            slotsByKey.remove(key);
        } else {
            slotsByKey.put(key, count - 1);
        }
    }

    /** Gives back the record and the ring slot of a line that is not stored after all. */
    private void giveBack(long effectRef, RingSlot slot) {
        effects.unpin(effectRef);
        effects.evict(effectRef);
        releaseSlot(slot);
    }

    private int freeSlots(CommitRing ring) {
        return ring.capacity() - ring.depth() - slotsByKey.getOrDefault(ring.key(), 0);
    }

    private Verdict typedRefusal(RejectCode code, int position) {
        typedRefused++;
        return new Verdict(false, Entry.Disposition.DROPPED, code, sequence, position);
    }

    /** The lifecycle of a versioned line: the owner, the lifetime and the reset generation of both
     * namespaces. The thread column is a diagnostic and is not part of it. */
    private static String lifecycleKeyOf(Identity identity) {
        return identity.producer().lifecycleKey() + "|" + identity.controller().lifecycleKey();
    }

    /** One instance number names one instance in one log: its lifecycle, the chunk it publishes and
     * the state it publishes there. The same instance met on another thread is the same instance. */
    private static String instanceOf(Identity identity) {
        return lifecycleKeyOf(identity) + "|" + identity.chunkPos() + "|" + identity.targetStatus();
    }

    private static Entry.Disposition dispositionOf(Batch.Kind kind) {
        return switch (kind) {
            case APPLY -> Entry.Disposition.ACCEPTED;
            case MERGE -> Entry.Disposition.MERGED;
            case INTENT -> Entry.Disposition.INTENT;
            case RETRY -> Entry.Disposition.RETRIED;
            case DROP -> Entry.Disposition.DROPPED;
        };
    }

    private TypedStep typedStep(Entry entry) {
        long effectRef = entry.identity().effectRef();
        EffectStore.Recorded record = effects == null ? null : effects.record(effectRef);
        if (record == null) {
            effectMissingSteps++;
        } else {
            // The full normalized record is copied here, before the reference the line held is
            // released: a reader of the replay never needs the store to still be holding it.
            effects.unpin(effectRef);
        }
        return new TypedStep(entry.planSequence(), entry.position(), entry.writeSetDigest(),
            entry.worldId(), entry.domainId(), entry.nodeKey(), entry.expectedVersion(), entry.rows(),
            entry.identity(), entry.disposition(), record);
    }

    private boolean ascending(String worldId, String domainId, long planSequence, long position) {
        String key = key(worldId, domainId);
        Long plan = lastPlanOfRing.get(key);
        Integer lastPosition = lastPositionOfRing.get(key);
        if (plan == null || lastPosition == null) {
            return true;
        }
        if (planSequence > plan) {
            return true;
        }
        return planSequence == plan && position >= lastPosition;
    }

    private CommitRing ring(String worldId, String domainId) {
        return rings.computeIfAbsent(key(worldId, domainId),
            key -> new CommitRing(worldId, domainId, ringCapacity.getAsInt()));
    }

    private static String key(String worldId, String domainId) {
        return worldId + "/" + domainId;
    }

    private static long fold(long hash, String text) {
        long value = hash;
        for (int index = 0; index < text.length(); index++) {
            value ^= text.charAt(index);
            value *= PRIME;
        }
        return value;
    }

    /** The terminal projection of the lifecycle that ended at the last reset: what the rings still
     * carried then, with the records copied. A further reset replaces it instead of piling up. */
    public SchemaReplay lastResetTyped() {
        return lastReset;
    }

    private SchemaReplay drain() {
        List<Entry.Step> plain = new ArrayList<>();
        List<TypedStep> typed = new ArrayList<>();
        List<String> keys = new ArrayList<>(rings.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            Entry entry;
            while ((entry = rings.get(key).pop()) != null) {
                if (entry.identity().effectRef() != NO_EFFECT) {
                    typed.add(typedStep(entry));
                } else {
                    plain.add(entry.step());
                }
            }
        }
        return new SchemaReplay(typed.isEmpty() ? SCHEMA_UNDECLARED : SCHEMA_TYPED, plain, typed,
            unresolvedOf(typed));
    }

    public long sequence() {
        return sequence;
    }

    public long accepted() {
        return accepted;
    }

    public long merged() {
        return merged;
    }

    public long intents() {
        return intents;
    }

    public long dropped() {
        return dropped;
    }

    public long retried() {
        return retried;
    }

    public long orderViolations() {
        return orderViolations;
    }

    public long refusedFull() {
        return refusedFull;
    }

    public long unplanned() {
        return unplanned;
    }

    public long ticks() {
        return ticks;
    }

    public int watermarks() {
        return watermarks;
    }

    public long firstDivergencePosition() {
        return firstDivergencePosition;
    }

    public int ringCount() {
        return rings.size();
    }

    public int ringDepth() {
        int depth = 0;
        for (CommitRing ring : rings.values()) {
            depth += ring.depth();
        }
        return depth;
    }

    public int ringCapacity() {
        return Math.max(1, ringCapacity.getAsInt());
    }

    /** The rings in a fixed order, for a reader that publishes them. */
    public List<CommitRing> rings() {
        List<String> keys = new ArrayList<>(rings.keySet());
        keys.sort(String::compareTo);
        List<CommitRing> ordered = new ArrayList<>(keys.size());
        for (String key : keys) {
            ordered.add(rings.get(key));
        }
        return ordered;
    }

    /** The number of accepted writes still held by the rings. */
    public int held() {
        return ringDepth();
    }

    /** The ring slots handed out and neither filled nor given back. */
    public int reservedSlots() {
        return heldSlots.size();
    }

    public long reservationsTaken() {
        return reservationsTaken;
    }

    public long reservationsReleased() {
        return reservationsReleased;
    }

    public long reservationsConsumed() {
        return reservationsConsumed;
    }

    public long reservationRefusals() {
        return reservationRefusals;
    }

    /** The versioned reports the log took. */
    public long typedStored() {
        return typedStored;
    }

    /** The versioned reports the log refused, which took no report number of their own. */
    public long typedRefused() {
        return typedRefused;
    }

    /** The versioned reports refused because their instance number is already held by another
     * lifecycle. */
    public long identityCollisions() {
        return identityCollisions;
    }

    /** The typed steps whose effect could not be resolved when the ring was read. */
    public long effectMissingSteps() {
        return effectMissingSteps;
    }

    /** Ends this lifecycle. The lines the rings still carry are folded into the terminal projection
     * of that lifecycle first, and the record a versioned line points at is copied before the one
     * reference the line holds is given back: a reset that is repeated cannot leave a reference
     * behind and cannot exhaust the store, while a reference held by someone else stays theirs. */
    public void reset() {
        lastReset = drain();
        rings.clear();
        lastPlanOfRing.clear();
        lastPositionOfRing.clear();
        sequence = 0L;
        tickIndex = 0L;
        accepted = 0L;
        merged = 0L;
        intents = 0L;
        dropped = 0L;
        retried = 0L;
        orderViolations = 0L;
        refusedFull = 0L;
        unplanned = 0L;
        ticks = 0L;
        watermarks = 0;
        last = null;
        firstDivergencePosition = -1L;
        lastSchema = null;
        heldSlots.clear();
        slotsByKey.clear();
        instanceNamespaces.clear();
        resetEpoch++;
        reservationsTaken = 0L;
        reservationsReleased = 0L;
        reservationsConsumed = 0L;
        reservationRefusals = 0L;
        typedStored = 0L;
        typedRefused = 0L;
        identityCollisions = 0L;
        effectMissingSteps = 0L;
    }
}
