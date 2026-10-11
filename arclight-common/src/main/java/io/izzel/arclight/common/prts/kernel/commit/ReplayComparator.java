/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compares two replayed runs under the version the records declare.
 *
 * <p>A record that declares no version is refused instead of being read as a legacy one, a version
 * this build does not know is refused instead of being answered "equal", a legacy side and a typed
 * side are refused instead of being compared across two protocols, and a typed line whose effect
 * cannot be resolved is refused instead of being read as an empty one. The reasons are the
 * comparator's own words and are not refusal codes of the write path: the code set is closed and
 * this class adds nothing to it.
 *
 * <p>A versioned comparison reads the logical columns of a step, never the numbers a run happened
 * to assign: a runtime counter is read as the place it was met in within its own run, so two runs
 * that assigned other numbers but met the same things in the same order compare equal. No namespace
 * column (owner, lifetime, reset generation, thread) is read at all. */
public final class ReplayComparator {

    public static final String REFUSED_SIDE_MISSING = "side-missing";
    public static final String REFUSED_UNKNOWN_SCHEMA = "unknown-schema";
    public static final String REFUSED_UNDECLARED_SCHEMA = "undeclared-schema";
    public static final String REFUSED_MIXED_PROTOCOL = "mixed-protocol";
    public static final String REFUSED_MIXED_WITHIN_SIDE = "mixed-within-side";
    public static final String REFUSED_ORPHAN_EFFECT = "orphan-effect";
    public static final String REFUSED_EFFECT_MISSING = "effect-missing";
    public static final String REFUSED_INSTANCE_COLLISION = "instance-collision";

    /** The columns a versioned comparison reads, in the order it reports them. */
    public static final List<String> CANONICAL_FIELDS = List.of("world", "domain", "plan", "position",
        "writeSet", "epoch", "chunk", "status", "instance", "authorization", "effect", "operation",
        "outcome", "declared", "content", "contentDigest", "disposition");

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
     * comparison is reached, so a record that declares no version is never read as a legacy one. */
    public CommitLog.SchemaReplay legacyDeclared(CommitLog.Replay replay) {
        return new CommitLog.SchemaReplay(CommitLog.SCHEMA_LEGACY,
            replay == null ? List.of() : replay.steps(), List.of(), 0);
    }

    /** Compares two sides under the version they declare. */
    public Verdict compare(CommitLog.SchemaReplay left, CommitLog.SchemaReplay right) {
        compared++;
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
            return comparePlain(left.plainSteps(), right.plainSteps());
        }
        if (mixedWithin(left)) {
            return refuse(REFUSED_MIXED_WITHIN_SIDE, "left declares version 1 and carries plain steps");
        }
        if (mixedWithin(right)) {
            return refuse(REFUSED_MIXED_WITHIN_SIDE, "right declares version 1 and carries plain steps");
        }
        String broken = integrity(left);
        if (broken != null) {
            return refuse(broken, "left");
        }
        broken = integrity(right);
        if (broken != null) {
            return refuse(broken, "right");
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

    /** A versioned side refuses a line whose effect is not there, a line pointing at a record that
     * is not the one it names, and a line that reuses the instance number of another lifecycle. */
    private static String integrity(CommitLog.SchemaReplay side) {
        if (side.effectMissing() > 0) {
            return REFUSED_EFFECT_MISSING;
        }
        Map<String, String> namespaces = new LinkedHashMap<>();
        for (CommitLog.TypedStep step : side.typedSteps()) {
            long effectRef = step.identity().effectRef();
            if (effectRef <= 0L || step.effect() == null || step.effect().effectRef() != effectRef) {
                return step.effect() == null ? REFUSED_EFFECT_MISSING : REFUSED_ORPHAN_EFFECT;
            }
            String pair = step.worldId() + "/" + step.domainId() + "/" + step.identity().instanceId();
            String held = namespaces.putIfAbsent(pair, namespaceOf(step.identity()));
            if (held != null && !held.equals(namespaceOf(step.identity()))) {
                return REFUSED_INSTANCE_COLLISION;
            }
        }
        return null;
    }

    private static boolean mixedWithin(CommitLog.SchemaReplay side) {
        return !side.plainSteps().isEmpty() && !side.typedSteps().isEmpty();
    }

    private static String namespaceOf(CommitLog.Identity identity) {
        CommitNamespaces.Namespace producer = identity.producer();
        CommitNamespaces.Namespace controller = identity.controller();
        return producer.text() + "|" + controller.text();
    }

    private static Map<String, String> columns(Canonical ordinals, CommitLog.TypedStep step) {
        CommitLog.Identity identity = step.identity();
        EffectStore.Recorded effect = step.effect();
        String pair = step.worldId() + "/" + step.domainId();
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("world", String.valueOf(step.worldId()));
        columns.put("domain", String.valueOf(step.domainId()));
        columns.put("plan", String.valueOf(ordinals.ordinal("plan/" + step.planSequence())));
        columns.put("position", String.valueOf(step.position()));
        columns.put("writeSet", Long.toHexString(step.writeSetDigest()));
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
        if (differences == 0) {
            equal++;
            return new Verdict(Decision.EQUAL, 0, "", List.of(), note);
        }
        different++;
        return new Verdict(Decision.DIFFERENT, differences, "", fields, note);
    }

    private Verdict refuse(String reason, String note) {
        refused++;
        refusals.merge(reason, 1L, Long::sum);
        return new Verdict(Decision.REFUSED, 0, reason, List.of(), note);
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
