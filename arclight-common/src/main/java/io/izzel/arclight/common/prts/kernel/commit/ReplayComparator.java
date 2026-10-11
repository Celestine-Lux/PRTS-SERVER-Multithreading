/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compares two replayed runs under the version they declare.
 *
 * <p>A record that declares no version is refused instead of being read as a legacy one, a version
 * this build does not know is refused instead of being answered "equal", a legacy side and a typed
 * side are refused instead of being compared across two protocols, a side that carries lines of both
 * protocols is refused instead of being read as one of them, a line whose columns are not all
 * declared is refused instead of being mapped into a number, and an empty sample is refused instead
 * of being answered "equal": two runs that wrote nothing are not evidence that they wrote the same
 * thing. The reasons are the comparator's own words and are not refusal codes of the write path: the
 * code set is closed and this class adds nothing to it.
 *
 * <p>A versioned comparison reads the logical columns of a step, never the numbers a run happened
 * to assign: a runtime counter is read as the place it was met in within its own run, so two runs
 * that assigned other numbers but met the same things in the same order compare equal. The line
 * itself is read first: every column has to be declared, the world generation has to be one a live
 * world carries, the authorization generation has to be the expected version of the batch, the
 * declared rows have to be the units of the record, the node has to be named and one reference has
 * to keep one content. No namespace column (owner, lifetime, reset generation, thread) is read at
 * all, and a collision between two lifecycles is decided without the thread.
 *
 * <p>The renumbering of the runtime counters is a reading of the place a number was met in and not a
 * proof that two records are the same instance: nothing is mapped before the line it came from was
 * found to be well formed, so a column that carries no usable value is refused instead of being
 * turned into an ordinal that would compare equal to another unusable one. */
public final class ReplayComparator {

    public static final String REFUSED_SIDE_MISSING = "side-missing";
    public static final String REFUSED_UNKNOWN_SCHEMA = "unknown-schema";
    public static final String REFUSED_UNDECLARED_SCHEMA = "undeclared-schema";
    public static final String REFUSED_MIXED_PROTOCOL = "mixed-protocol";
    public static final String REFUSED_MIXED_WITHIN_SIDE = "mixed-within-side";
    public static final String REFUSED_ORPHAN_EFFECT = "orphan-effect";
    public static final String REFUSED_EFFECT_MISSING = "effect-missing";
    public static final String REFUSED_INSTANCE_COLLISION = "instance-collision";
    public static final String REFUSED_EMPTY_SAMPLE = "empty-sample";
    public static final String REFUSED_ILLEGAL_IDENTITY = "illegal-identity";

    /** The columns a versioned comparison reads, in the order it reports them. What it deliberately
     * does not read: the runtime namespace (owner, lifetime, reset generation, thread), the expected
     * version of the batch (the integrity check requires it to be the authorization column, which is
     * read), and the columns of a line that are not part of a step at all (its report number, its
     * tick and its handle). */
    public static final List<String> CANONICAL_FIELDS = List.of("world", "domain", "node", "plan",
        "position", "writeSet", "rows", "epoch", "chunk", "status", "instance", "authorization",
        "effect", "operation", "outcome", "declared", "content", "contentDigest", "disposition");

    /** What a comparison decided. */
    public enum Decision {
        EQUAL,
        DIFFERENT,
        REFUSED
    }

    /** The answer of one comparison: the decision, how many steps differ, the reason of a refusal
     * and the columns that differed. */
    public record Verdict(Decision decision, int differences, String reason, List<String> fields,
                          String note) {

        public Verdict {
            reason = reason == null ? "" : reason;
            fields = List.copyOf(fields == null ? List.of() : fields);
            note = note == null ? "" : note;
        }

        public boolean equal() {
            return decision == Decision.EQUAL;
        }

        public boolean refused() {
            return decision == Decision.REFUSED;
        }
    }

    private static final String LEGACY_NOTE = "declared version 0: the plan order, the position and "
        + "the write set of a plain step only; the typed columns of these records are not read";

    private final Map<String, Long> refusals = new LinkedHashMap<>();
    private long compared;
    private long equal;
    private long different;
    private long refused;

    /** Wraps a plain replay as a side that declares version 0. It is the only way a three-field
     * comparison is reached, so a record that declares no version is never read as a legacy one. A
     * replay that is not there stays missing: it is not rebuilt as an empty sample that would answer
     * "equal" against anything. */
    public CommitLog.SchemaReplay legacyDeclared(CommitLog.Replay replay) {
        if (replay == null) {
            return null;
        }
        return new CommitLog.SchemaReplay(CommitLog.SCHEMA_LEGACY, replay.steps(), List.of(), 0);
    }

    /** Compares two sides under the version they declare. An empty sample is answered with its own
     * reason and does not count as a compared pair. */
    public Verdict compare(CommitLog.SchemaReplay left, CommitLog.SchemaReplay right) {
        if (left == null || right == null) {
            return refuse(REFUSED_SIDE_MISSING, "");
        }
        if (left.schemaVersion() != right.schemaVersion()) {
            return refuse(REFUSED_MIXED_PROTOCOL, "left=" + left.schemaVersion()
                + " right=" + right.schemaVersion());
        }
        int version = left.schemaVersion();
        if (version == CommitLog.SCHEMA_UNDECLARED) {
            return refuse(REFUSED_UNDECLARED_SCHEMA, "the records declare no version");
        }
        if (!CommitLog.supportedSchema(version)) {
            return refuse(REFUSED_UNKNOWN_SCHEMA, "version=" + version);
        }
        if (version == CommitLog.SCHEMA_LEGACY) {
            if (!left.typedSteps().isEmpty() || !right.typedSteps().isEmpty()) {
                return refuse(REFUSED_MIXED_WITHIN_SIDE, "a version 0 side carries typed steps");
            }
            if (left.plainSteps().isEmpty() || right.plainSteps().isEmpty()) {
                return emptySample("a version 0 side carries no plain step");
            }
            return comparePlain(left.plainSteps(), right.plainSteps());
        }
        if (!left.plainSteps().isEmpty() || !right.plainSteps().isEmpty()) {
            return refuse(REFUSED_MIXED_WITHIN_SIDE, "a version 1 side carries plain steps");
        }
        String broken = integrity(left);
        if (broken != null) {
            return refuse(broken, "left");
        }
        broken = integrity(right);
        if (broken != null) {
            return refuse(broken, "right");
        }
        if (left.typedSteps().isEmpty() || right.typedSteps().isEmpty()) {
            return emptySample("a version 1 side carries no typed step");
        }
        return compareTyped(left.typedSteps(), right.typedSteps());
    }

    /** Compares the three fields of two plain projections, which is all version 0 carries. */
    private Verdict comparePlain(List<CommitLog.Entry.Step> left, List<CommitLog.Entry.Step> right) {
        int differences = Math.abs(left.size() - right.size());
        int shared = Math.min(left.size(), right.size());
        List<String> fields = new ArrayList<>();
        for (int index = 0; index < shared; index++) {
            CommitLog.Entry.Step a = left.get(index);
            CommitLog.Entry.Step b = right.get(index);
            boolean stepDiffers = false;
            if (a.planSequence() != b.planSequence()) {
                stepDiffers = true;
                add(fields, "plan");
            }
            if (a.position() != b.position()) {
                stepDiffers = true;
                add(fields, "position");
            }
            if (a.writeSetDigest() != b.writeSetDigest()) {
                stepDiffers = true;
                add(fields, "writeSet");
            }
            if (stepDiffers) {
                differences++;
            }
        }
        return decide(differences, fields, LEGACY_NOTE);
    }

    /** Compares the logical columns of two versioned projections. */
    private Verdict compareTyped(List<CommitLog.TypedStep> left, List<CommitLog.TypedStep> right) {
        Canonical leftOrdinals = new Canonical();
        Canonical rightOrdinals = new Canonical();
        int differences = Math.abs(left.size() - right.size());
        int shared = Math.min(left.size(), right.size());
        List<String> fields = new ArrayList<>();
        for (int index = 0; index < shared; index++) {
            Map<String, String> a = columns(leftOrdinals, left.get(index));
            Map<String, String> b = columns(rightOrdinals, right.get(index));
            boolean stepDiffers = false;
            for (String field : CANONICAL_FIELDS) {
                if (!Objects.equals(a.get(field), b.get(field))) {
                    stepDiffers = true;
                    add(fields, field);
                }
            }
            if (stepDiffers) {
                differences++;
            }
        }
        return decide(differences, fields, "");
    }

    /** A versioned side refuses a line that declares another schema, a line whose columns are not all
     * declared or do not agree with each other, a line whose effect is not there or is not the one it
     * names, one reference that carries two contents, and one instance number that is worn by two
     * lifecycles, two chunks or two states. */
    private static String integrity(CommitLog.SchemaReplay side) {
        if (side.effectMissing() > 0) {
            return REFUSED_EFFECT_MISSING;
        }
        Map<Long, String> references = new LinkedHashMap<>();
        Map<String, String> instances = new LinkedHashMap<>();
        for (CommitLog.TypedStep step : side.typedSteps()) {
            CommitLog.Identity identity = step.identity();
            EffectStore.Recorded effect = step.effect();
            long effectRef = identity.effectRef();
            if (effect == null) {
                return REFUSED_EFFECT_MISSING;
            }
            if (effectRef <= 0L || effect.effectRef() != effectRef) {
                return REFUSED_ORPHAN_EFFECT;
            }
            if (identity.schemaVersion() != CommitLog.SCHEMA_TYPED) {
                return REFUSED_MIXED_WITHIN_SIDE;
            }
            if (!identity.declaredTyped() || step.nodeKey() == null || step.nodeKey().isEmpty()
                || step.expectedVersion() != identity.authorizationGeneration()
                || step.rows() != effect.content().size()) {
                return REFUSED_ILLEGAL_IDENTITY;
            }
            String mapping = effect.contentDigest() + "/" + effect.operationKind() + "/"
                + effect.outcome();
            String carried = references.putIfAbsent(effectRef, mapping);
            if (carried != null && !carried.equals(mapping)) {
                return REFUSED_ORPHAN_EFFECT;
            }
            String instance = lifecycleOf(identity) + "|" + identity.chunkPos() + "|"
                + identity.targetStatus();
            String pair = step.worldId() + "/" + step.domainId() + "/" + identity.instanceId();
            String held = instances.putIfAbsent(pair, instance);
            if (held != null && !held.equals(instance)) {
                return REFUSED_INSTANCE_COLLISION;
            }
        }
        return null;
    }

    /** The lifecycle of a versioned line: the owner, the lifetime and the reset generation of both
     * namespaces. The thread column is a diagnostic and never decides a collision. */
    private static String lifecycleOf(CommitLog.Identity identity) {
        CommitNamespaces.Namespace producer = identity.producer();
        CommitNamespaces.Namespace controller = identity.controller();
        return producer.lifecycleKey() + "|" + controller.lifecycleKey();
    }

    private static Map<String, String> columns(Canonical ordinals, CommitLog.TypedStep step) {
        CommitLog.Identity identity = step.identity();
        EffectStore.Recorded effect = step.effect();
        String pair = step.worldId() + "/" + step.domainId();
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("world", String.valueOf(step.worldId()));
        columns.put("domain", String.valueOf(step.domainId()));
        columns.put("node", String.valueOf(step.nodeKey()));
        columns.put("plan", String.valueOf(ordinals.ordinal("plan/" + step.planSequence())));
        columns.put("position", String.valueOf(step.position()));
        columns.put("writeSet", Long.toHexString(step.writeSetDigest()));
        columns.put("rows", String.valueOf(step.rows()));
        columns.put("epoch", String.valueOf(ordinals.ordinal("epoch/" + step.worldId() + "/"
            + identity.worldEpoch())));
        columns.put("chunk", String.valueOf(identity.chunkPos()));
        columns.put("status", identity.targetStatus());
        columns.put("instance", String.valueOf(ordinals.ordinal("instance/" + pair + "/"
            + identity.instanceId())));
        columns.put("authorization", String.valueOf(ordinals.ordinal("authorization/" + pair + "/"
            + identity.authorizationGeneration())));
        columns.put("effect", String.valueOf(ordinals.ordinal("effect/" + identity.effectRef())));
        columns.put("operation", effect.operationKind());
        columns.put("outcome", effect.outcome());
        columns.put("declared", String.join(",", effect.declared()));
        columns.put("content", String.join("\n", effect.content()));
        columns.put("contentDigest", effect.contentDigest());
        columns.put("disposition", String.valueOf(step.disposition()));
        return columns;
    }

    private Verdict decide(int differences, List<String> fields, String note) {
        compared++;
        if (differences == 0) {
            equal++;
            return new Verdict(Decision.EQUAL, 0, "", List.of(), note);
        }
        different++;
        return new Verdict(Decision.DIFFERENT, differences, "", fields, note);
    }

    private Verdict refuse(String reason, String note) {
        compared++;
        refused++;
        refusals.merge(reason, 1L, Long::sum);
        return new Verdict(Decision.REFUSED, 0, reason, List.of(), note);
    }

    /** An empty sample is a state of its own: it is refused, and it is not counted as a pair the
     * comparison passed judgement on. */
    private Verdict emptySample(String note) {
        refused++;
        refusals.merge(REFUSED_EMPTY_SAMPLE, 1L, Long::sum);
        return new Verdict(Decision.REFUSED, 0, REFUSED_EMPTY_SAMPLE, List.of(), note);
    }

    private static void add(List<String> fields, String field) {
        if (!fields.contains(field)) {
            fields.add(field);
        }
    }

    /** The run-local renumbering of the runtime counters a versioned comparison reads. */
    private static final class Canonical {

        private final Map<String, Integer> ordinals = new LinkedHashMap<>();

        int ordinal(String key) {
            Integer held = ordinals.get(key);
            if (held != null) {
                return held;
            }
            int next = ordinals.size() + 1;
            ordinals.put(key, next);
            return next;
        }
    }

    public long compared() {
        return compared;
    }

    public long equal() {
        return equal;
    }

    public long different() {
        return different;
    }

    public long refused() {
        return refused;
    }

    public long refusals(String reason) {
        return refusals.getOrDefault(reason, 0L);
    }

    public Map<String, Long> refusalReasons() {
        return new LinkedHashMap<>(refusals);
    }
}
