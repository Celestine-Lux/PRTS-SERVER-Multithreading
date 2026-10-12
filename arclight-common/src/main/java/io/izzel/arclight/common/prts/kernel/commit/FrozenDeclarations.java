/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The declarations one freeze accepted, sealed once inside that freeze and read-only afterwards.
 * An entry exists only where the frozen plan's own commit order gave a place to a node a domain had
 * declared with the artefact it would publish and the mapping its own handle mints an identity from,
 * so a lookup answers with the plan's final seat and never the order the declarations arrived in. A
 * table that sealed nothing reads {@link #NOT_WIRED} instead of an empty success. */
public final class FrozenDeclarations {

    /** A table that sealed at least one declaration carrying its artefact. */
    public static final String SEALED = "SEALED";

    /** No declaration reached the table with the artefact a witness needs. */
    public static final String NOT_WIRED = "NOT_WIRED";

    /** A seal that refused: the table it would have written is not there. */
    public static final String REFUSED = "REFUSED";

    /** What a declaration and an assignment are joined by: the three names as themselves. Splicing
     * them into one text would read ("a/b", "c") and ("a", "b/c") as the same node and connect a
     * declaration to a seat it never declared. */
    public record NodeKey(String worldId, String domainId, String nodeKey) {

        public NodeKey {
            worldId = worldId == null ? "" : worldId;
            domainId = domainId == null ? "" : domainId;
            nodeKey = nodeKey == null ? "" : nodeKey;
        }

        public boolean declared() {
            return !worldId.isEmpty() && !domainId.isEmpty() && !nodeKey.isEmpty();
        }
    }

    /** The chunk one declaration publishes to. Presence is this object and never a numeric
     * sentinel: the origin packs to the same value as "no chunk", and both are legal. */
    public record DeclaredChunk(long packedPos) {
    }

    /** The lifecycle one freeze belongs to: its own generation beside the lifecycle key it was taken
     * under. The plan counter is not one of these, because a reset hands it back and it therefore
     * cannot tell the freeze of one lifecycle from the freeze of the next. */
    public record FreezeKey(String lifecycleKey, long generation) {

        public static final FreezeKey NONE = new FreezeKey("", 0L);

        public FreezeKey {
            lifecycleKey = lifecycleKey == null ? "" : lifecycleKey;
        }

        /** Whether this key names a freeze at all: a seal under no key is refused, not defaulted. */
        public boolean declared() {
            return generation > 0L;
        }

        public boolean sameLifecycle(FreezeKey other) {
            return other != null && lifecycleKey.equals(other.lifecycleKey);
        }

        /** The freeze after this one: another generation, never the same key twice. */
        public FreezeKey next() {
            return new FreezeKey(lifecycleKey, generation + 1L);
        }
    }

    /** What a domain hands in before the plan is frozen: the logical node it declares, the artefact
     * that node would publish and the mapping its own handle mints this run's identity from. It
     * carries no position, which is the frozen plan's to give. */
    public record Candidate(String worldId, String domainId, String nodeKey, DeclaredChunk chunk,
                            String targetStatus, long instanceId, long worldEpoch) {

        public Candidate(String worldId, String domainId, String nodeKey, DeclaredChunk chunk,
                         String targetStatus) {
            this(worldId, domainId, nodeKey, chunk, targetStatus, 0L, 0L);
        }

        public Candidate {
            worldId = worldId == null ? "" : worldId;
            domainId = domainId == null ? "" : domainId;
            nodeKey = nodeKey == null ? "" : nodeKey;
            targetStatus = targetStatus == null ? "" : targetStatus;
        }

        /** Whether the artefact half of the declaration is there. */
        public boolean artefactDeclared() {
            return !worldId.isEmpty() && !domainId.isEmpty() && !nodeKey.isEmpty() && chunk != null
                && !targetStatus.isEmpty();
        }

        /** Whether the handle also carries the mapping a line is identified by: the instance this
         * side authorizes, read through the one positive-domain check the write side shares. */
        public boolean bindingDeclared() {
            return CommitLog.liveInstance(instanceId) && CommitLog.liveWorldEpoch(worldEpoch);
        }
    }

    /** The place the frozen plan gave one declared node: half of what a lookup is keyed by. */
    public record Seat(String worldId, String domainId, long planSequence, int position) {

        public Seat {
            worldId = worldId == null ? "" : worldId;
            domainId = domainId == null ? "" : domainId;
        }
    }

    /** One node of the frozen plan's commit order, read from the plan and not from a declaration. */
    public record Assignment(String worldId, String domainId, String nodeKey, int position) {

        public Assignment {
            worldId = worldId == null ? "" : worldId;
            domainId = domainId == null ? "" : domainId;
            nodeKey = nodeKey == null ? "" : nodeKey;
        }
    }

    /** One accepted declaration: the seat, the node it belongs to, the role the plan's declared
     * nodes give it, the artefact it publishes and the frozen mapping its identity is read from. */
    public record Entry(Seat seat, String nodeKey, int role, long packedChunkPos, String targetStatus,
                        long instanceId, long worldEpoch) {

        /** Whether this entry carries the mapping a line has to wear: the positive domain of a seal. */
        public boolean bindingDeclared() {
            return CommitLog.liveInstance(instanceId) && CommitLog.liveWorldEpoch(worldEpoch);
        }
    }

    /** What a seal produced: the table, or the piece that refused it. A refusal writes no table. */
    public record Sealed(FrozenDeclarations declarations, String refusal) {

        public boolean sealed() {
            return declarations != null;
        }
    }

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private static final FrozenDeclarations NONE = new FrozenDeclarations(FreezeKey.NONE, -1L, NOT_WIRED,
        Map.of(), 0L);

    private final FreezeKey freeze;
    private final long planSequence;
    private final String state;
    private final Map<Seat, Entry> entries;
    private final long digest;

    private FrozenDeclarations(FreezeKey freeze, long planSequence, String state, Map<Seat, Entry> entries,
                               long digest) {
        this.freeze = freeze;
        this.planSequence = planSequence;
        this.state = state;
        this.entries = entries;
        this.digest = digest;
    }

    /** The table of a build whose producer never sealed one: a named state, not an empty success. */
    public static FrozenDeclarations none() {
        return NONE;
    }

    /** Joins the declarations a domain handed in with the places the frozen plan's own commit order
     * gave them. A node the plan carried without a declaration, or with one carrying no artefact or
     * no frozen mapping, takes no entry: a seat nothing can be identified at is not a seat. */
    public static Sealed seal(FreezeKey freeze, long planSequence, List<Candidate> candidates,
                              List<Assignment> assignments) {
        if (freeze == null || !freeze.declared()) {
            return new Sealed(null, "declaration-freeze-missing");
        }
        List<Candidate> declared = candidates == null ? List.of() : candidates;
        List<Assignment> places = assignments == null ? List.of() : assignments;
        Map<NodeKey, Candidate> byNode = new LinkedHashMap<>();
        for (Candidate candidate : declared) {
            if (candidate == null) {
                continue;
            }
            NodeKey node = keyOf(candidate.worldId(), candidate.domainId(), candidate.nodeKey());
            if (byNode.putIfAbsent(node, candidate) != null) {
                // One node of one pair is declared once inside one freeze: a second declaration of
                // the same node is refused rather than merged, because two of them cannot both be
                // the source of one line.
                return new Sealed(null, "declaration-duplicate");
            }
        }
        List<Assignment> ordered = new ArrayList<>(places);
        ordered.sort(Comparator.comparing(Assignment::worldId).thenComparing(Assignment::domainId)
            .thenComparing(Assignment::nodeKey));
        Map<NodeKey, Integer> roles = new LinkedHashMap<>();
        for (Assignment place : ordered) {
            roles.putIfAbsent(keyOf(place.worldId(), place.domainId(), place.nodeKey()),
                roles.size() + 1);
        }
        Map<Seat, Entry> sealed = new LinkedHashMap<>();
        for (Assignment place : places) {
            NodeKey node = keyOf(place.worldId(), place.domainId(), place.nodeKey());
            Candidate candidate = byNode.get(node);
            if (candidate == null || !candidate.artefactDeclared() || !candidate.bindingDeclared()) {
                continue;
            }
            Seat seat = new Seat(place.worldId(), place.domainId(), planSequence, place.position());
            Entry entry = new Entry(seat, place.nodeKey(), roles.getOrDefault(node, 0),
                candidate.chunk().packedPos(), candidate.targetStatus(), candidate.instanceId(),
                candidate.worldEpoch());
            if (sealed.putIfAbsent(seat, entry) != null) {
                return new Sealed(null, "declaration-place-duplicate");
            }
        }
        String state = sealed.isEmpty() ? NOT_WIRED : SEALED;
        return new Sealed(new FrozenDeclarations(freeze, planSequence, state, sealed,
            digestOf(freeze, planSequence, sealed)), "");
    }

    /** The table of a seal that already happened, rebuilt from the entries a reader holds: how a
     * replay closure names the freeze its records belong to without carrying the run. The entries
     * are judged by the constraints a seal itself works under, so this entry point is not a weaker
     * source than the seal: a table no seal could have written is refused instead of rebuilt. */
    public static Sealed of(FreezeKey freeze, long planSequence, List<Entry> entries) {
        if (freeze == null || !freeze.declared()) {
            return new Sealed(null, "declaration-freeze-missing");
        }
        Map<Seat, Entry> table = new LinkedHashMap<>();
        Map<NodeKey, Seat> nodes = new LinkedHashMap<>();
        Map<Integer, Seat> roles = new LinkedHashMap<>();
        for (Entry entry : entries == null ? List.<Entry>of() : entries) {
            if (entry == null || entry.seat() == null) {
                return new Sealed(null, "declaration-entry-missing");
            }
            Seat seat = entry.seat();
            NodeKey node = keyOf(seat.worldId(), seat.domainId(), entry.nodeKey());
            if (!node.declared() || entry.targetStatus() == null || entry.targetStatus().isEmpty()) {
                // A null state is a field this table does not carry, not a fault of the reader.
                return new Sealed(null, "declaration-field-missing");
            }
            if (seat.planSequence() != planSequence) {
                return new Sealed(null, "declaration-plan-mismatch");
            }
            if (entry.role() <= 0) {
                return new Sealed(null, "declaration-role-illegal");
            }
            if (!entry.bindingDeclared()) {
                return new Sealed(null, "declaration-binding-missing");
            }
            if (nodes.putIfAbsent(node, seat) != null || roles.putIfAbsent(entry.role(), seat) != null) {
                // A seal gives one node one entry and one rank, so a repeated node or a repeated
                // rank is a table the seal could not have produced. Gaps between ranks are legal:
                // a node the plan carried without an artefact takes a rank and no entry.
                return new Sealed(null, "declaration-duplicate");
            }
            if (table.putIfAbsent(seat, entry) != null) {
                return new Sealed(null, "declaration-seat-duplicate");
            }
        }
        return new Sealed(new FrozenDeclarations(freeze, planSequence,
            table.isEmpty() ? NOT_WIRED : SEALED, table, digestOf(freeze, planSequence, table)), "");
    }

    private static NodeKey keyOf(String worldId, String domainId, String nodeKey) {
        return new NodeKey(worldId, domainId, nodeKey);
    }

    private static long digestOf(FreezeKey freeze, long planSequence, Map<Seat, Entry> entries) {
        long hash = fold(OFFSET_BASIS, segment(freeze.lifecycleKey())
            + segment(Long.toString(freeze.generation())) + segment(Long.toString(planSequence)));
        for (Entry entry : entries.values()) {
            hash = fold(hash, segment(entry.seat().worldId()) + segment(entry.seat().domainId())
                + segment(Long.toString(entry.seat().planSequence()))
                + segment(Integer.toString(entry.seat().position())) + segment(entry.nodeKey())
                + segment(Integer.toString(entry.role()))
                + segment(Long.toString(entry.packedChunkPos())) + segment(entry.targetStatus())
                + segment(Long.toString(entry.instanceId()))
                + segment(Long.toString(entry.worldEpoch())));
        }
        return hash;
    }

    private static String segment(String value) {
        String text = value == null ? "" : value;
        return text.length() + ":" + text;
    }

    private static long fold(long hash, String text) {
        long value = hash;
        for (int index = 0; index < text.length(); index++) {
            value ^= text.charAt(index);
            value *= PRIME;
        }
        return value;
    }

    /** The place of the entry this seat holds, or null when the seal wrote none there. */
    public Entry entryAt(Seat seat) {
        return seat == null ? null : entries.get(seat);
    }

    public FreezeKey freeze() {
        return freeze;
    }

    public long planSequence() {
        return planSequence;
    }

    public String state() {
        return state;
    }

    public int size() {
        return entries.size();
    }

    public long digest() {
        return digest;
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** The freezes one holder has replaced, kept whole as read-only snapshots so a ticket, a log or
     * an export that outlived its freeze still names the declarations its records were judged under.
     * The retention is bounded, an eviction is counted, and a table an obligation still names is not
     * evicted at all: the bound is applied to the tables nothing owes. The current table is the only
     * one that authorizes; a snapshot answers evidence and nothing else. */
    public static final class History {

        private final int cap;
        private FrozenDeclarations current = FrozenDeclarations.none();
        private final Deque<FrozenDeclarations> retained = new ArrayDeque<>();
        /** What still owes a freeze: a ticket before its terminal, an unread line or a pending item,
         * keyed by the table itself and not by a plan number two lifecycles may share. */
        private final Map<FrozenDeclarations, Integer> owed = new IdentityHashMap<>();
        private long retired;
        private long evicted;
        private long withheld;

        public History(int cap) {
            this.cap = Math.max(1, cap);
        }

        public synchronized FrozenDeclarations current() {
            return current;
        }

        /** The table that may authorize a seat of this plan: the freeze this holder is in, and no
         * older one, whatever plan number the older table happens to carry. */
        public synchronized FrozenDeclarations authorizing(long planSequence) {
            return current.planSequence() == planSequence ? current : FrozenDeclarations.none();
        }

        /** The read-only snapshot of one named freeze, or a table that answers nothing. */
        public synchronized FrozenDeclarations snapshot(FreezeKey freeze, long planSequence) {
            for (FrozenDeclarations table : retained) {
                if (table.planSequence() == planSequence && table.freeze().equals(freeze)) {
                    return table;
                }
            }
            return FrozenDeclarations.none();
        }

        /** The newest replaced freeze with this plan number, for a reader that did not name its
         * freeze; a caller that names the freeze reads {@link #snapshot(FreezeKey, long)}. */
        public synchronized FrozenDeclarations newestSnapshot(long planSequence) {
            FrozenDeclarations found = FrozenDeclarations.none();
            for (FrozenDeclarations table : retained) {
                if (table.planSequence() == planSequence) {
                    found = table;
                }
            }
            return found;
        }

        /** Keeps the table of one verified freeze readable while an obligation still names it: the
         * ticket taken under it, the line its terminal writes, an unread line or a pending item. It
         * holds the read-only table itself, and a table that sealed nothing backs no seat to keep. */
        public synchronized IdentityWitness.Obligation oblige(FrozenDeclarations table) {
            if (table == null || table.size() <= 0) {
                return IdentityWitness.Obligation.NONE;
            }
            owed.merge(table, 1, Integer::sum);
            return new IdentityWitness.Obligation() {

                private boolean settled;

                @Override
                public synchronized void settle() {
                    if (settled) {
                        return;
                    }
                    settled = true;
                    History.this.settle(table);
                }
            };
        }

        private synchronized void settle(FrozenDeclarations table) {
            Integer held = owed.get(table);
            if (held == null) {
                return;
            }
            if (held <= 1) {
                owed.remove(table);
                return;
            }
            owed.put(table, held - 1);
        }

        /** Takes a sealed table as the current one and retires the freeze it replaces. */
        public synchronized void seal(FrozenDeclarations table) {
            retire();
            current = table == null ? FrozenDeclarations.none() : table;
        }

        /** Ends the freeze this holder is in: the table becomes a snapshot that authorizes nothing.
         * A table with no entry is not kept, because nothing was ever judged at it. */
        public synchronized void retire() {
            FrozenDeclarations held = current;
            current = FrozenDeclarations.none();
            if (held.size() <= 0) {
                return;
            }
            retained.addLast(held);
            retired++;
            trim();
        }

        /** Applies the bound from the oldest table on: what nothing owes is given up, and a table an
         * obligation still names is left and counted instead. The bound runs when the next freeze
         * replaces one, so the freeze of a terminal line is still there to be exported after it. */
        private void trim() {
            Iterator<FrozenDeclarations> walk = retained.iterator();
            while (retained.size() > cap && walk.hasNext()) {
                FrozenDeclarations candidate = walk.next();
                if (owed.containsKey(candidate)) {
                    withheld++;
                    continue;
                }
                walk.remove();
                evicted++;
            }
        }

        public synchronized int retained() {
            return retained.size();
        }

        public synchronized long retired() {
            return retired;
        }

        public synchronized long evicted() {
            return evicted;
        }

        /** How many evictions an obligation did not allow: "given up" and "still owed" differ. */
        public synchronized long withheld() {
            return withheld;
        }
    }
}
