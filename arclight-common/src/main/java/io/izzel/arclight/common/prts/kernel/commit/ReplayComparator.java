/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compares two replayed runs under the version they declare. A shape that cannot be read is
 * refused with one of the REFUSED_ constants instead of being answered, and an empty sample is
 * refused too: two runs that wrote nothing are no evidence that they wrote the same thing. Those
 * reasons are this class's own words and not refusal codes of the write path, whose set stays
 * closed. A versioned comparison reads the logical columns of a step and never the numbers a run
 * happened to assign, and it reads no namespace column: a collision between two lifecycles is
 * decided without the thread. */
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

    /** The columns a versioned comparison reads, in the order it reports them. The runtime namespace
     * and the line's own report number, tick and handle are deliberately not read. */
    public static final List<String> CANONICAL_FIELDS = List.of("world", "domain", "node", "plan",
        "position", "writeSet", "rows", "epoch", "chunk", "status", "instance", "authorization",
        "effect", "operation", "outcome", "declared", "content", "contentDigest", "disposition");

    public enum Decision {
        EQUAL,
        DIFFERENT,
        REFUSED
    }

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

    /** Wraps a plain replay as a side that declares version 0, which is the only way a three-field
     * comparison is reached. A replay that is not there stays missing. */
    public CommitLog.SchemaReplay legacyDeclared(CommitLog.Replay replay) {
        if (replay == null) {
            return null;
        }
        return new CommitLog.SchemaReplay(CommitLog.SCHEMA_LEGACY, replay.steps(), List.of(), 0);
    }

    /** Compares two sides under the version they declare. An empty sample is refused and does not
     * count as a pair the comparison passed judgement on. */
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

    // Refused, and not counted as a pair the comparison passed judgement on.
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
