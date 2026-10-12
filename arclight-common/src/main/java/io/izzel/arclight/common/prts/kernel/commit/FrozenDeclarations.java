/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The declarations one freeze accepted, sealed once inside that freeze and read-only afterwards.
 * An entry exists only where the frozen plan's own commit order gave a place to a node a domain had
 * declared with the artefact it would publish, so a lookup answers with the plan's final seat and
 * never the order the declarations arrived in. A table that sealed nothing reads {@link #NOT_WIRED}
 * instead of an empty success. */
public final class FrozenDeclarations {

    /** A table that sealed at least one declaration carrying its artefact. */
    public static final String SEALED = "SEALED";

    /** No declaration reached the table with the artefact a witness needs. */
    public static final String NOT_WIRED = "NOT_WIRED";

    /** A seal that refused: the table it would have written is not there. */
    public static final String REFUSED = "REFUSED";

    /** The chunk one declaration publishes to. Presence is this object and never a numeric
     * sentinel: the origin packs to the same value as "no chunk", and both are legal. */
    public record DeclaredChunk(long packedPos) {
    }

    /** What a domain hands in before the plan is frozen: the logical node it declares and the
     * artefact that node would publish, and no position, which is the frozen plan's to give. */
    public record Candidate(String worldId, String domainId, String nodeKey, DeclaredChunk chunk,
                            String targetStatus) {

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
     * nodes give it, and the artefact it publishes. */
    public record Entry(Seat seat, String nodeKey, int role, long packedChunkPos, String targetStatus) {
    }

    /** What a seal produced: the table, or the piece that refused it. A refusal writes no table. */
    public record Sealed(FrozenDeclarations declarations, String refusal) {

        public boolean sealed() {
            return declarations != null;
        }
    }

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private static final FrozenDeclarations NONE = new FrozenDeclarations(-1L, NOT_WIRED, Map.of(), 0L);

    private final long planSequence;
    private final String state;
    private final Map<Seat, Entry> entries;
    private final long digest;

    private FrozenDeclarations(long planSequence, String state, Map<Seat, Entry> entries, long digest) {
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
     * gave them. A node the plan carried without a declaration, or with one carrying no artefact,
     * takes no entry. */
    public static Sealed seal(long planSequence, List<Candidate> candidates,
                              List<Assignment> assignments) {
        List<Candidate> declared = candidates == null ? List.of() : candidates;
        List<Assignment> places = assignments == null ? List.of() : assignments;
        Map<String, Candidate> byNode = new LinkedHashMap<>();
        for (Candidate candidate : declared) {
            if (candidate == null) {
                continue;
            }
            String node = nodeKeyOf(candidate.worldId(), candidate.domainId(), candidate.nodeKey());
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
        Map<String, Integer> roles = new LinkedHashMap<>();
        for (Assignment place : ordered) {
            roles.putIfAbsent(nodeKeyOf(place.worldId(), place.domainId(), place.nodeKey()),
                roles.size() + 1);
        }
        Map<Seat, Entry> sealed = new LinkedHashMap<>();
        for (Assignment place : places) {
            String node = nodeKeyOf(place.worldId(), place.domainId(), place.nodeKey());
            Candidate candidate = byNode.get(node);
            if (candidate == null || !candidate.artefactDeclared()) {
                continue;
            }
            Seat seat = new Seat(place.worldId(), place.domainId(), planSequence, place.position());
            Entry entry = new Entry(seat, place.nodeKey(), roles.getOrDefault(node, 0),
                candidate.chunk().packedPos(), candidate.targetStatus());
            if (sealed.putIfAbsent(seat, entry) != null) {
                return new Sealed(null, "declaration-place-duplicate");
            }
        }
        String state = sealed.isEmpty() ? NOT_WIRED : SEALED;
        return new Sealed(new FrozenDeclarations(planSequence, state, sealed, digestOf(sealed)),
            "");
    }

    /** The table of a seal that already happened, rebuilt from the entries a reader holds: how a
     * replay closure names the freeze its records belong to without carrying the run. */
    public static FrozenDeclarations of(long planSequence, List<Entry> entries) {
        Map<Seat, Entry> table = new LinkedHashMap<>();
        for (Entry entry : entries == null ? List.<Entry>of() : entries) {
            if (entry != null && entry.seat() != null) {
                table.put(entry.seat(), entry);
            }
        }
        return new FrozenDeclarations(planSequence, table.isEmpty() ? NOT_WIRED : SEALED, table,
            digestOf(table));
    }

    private static String nodeKeyOf(String worldId, String domainId, String nodeKey) {
        return worldId + "/" + domainId + "/" + nodeKey;
    }

    private static long digestOf(Map<Seat, Entry> entries) {
        long hash = OFFSET_BASIS;
        for (Entry entry : entries.values()) {
            hash = fold(hash, entry.seat().worldId() + "/" + entry.seat().domainId() + "/"
                + entry.seat().planSequence() + "/" + entry.seat().position() + "/" + entry.nodeKey()
                + "/" + entry.role() + "/" + entry.packedChunkPos() + "/" + entry.targetStatus());
        }
        return hash;
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
}
