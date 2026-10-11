/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** The claim table of the controlled write channel. A slot is declared for one world, domain and
 * level and names the spatial range its writes fall in; a write no active range covers is not a
 * domain write and stays on the default path. A write that is covered is judged against the
 * contract the slot carries, and only a covered write whose contract still holds is captured.
 * Nothing here decides a write by itself: the guard hands a covered write to the judged path it
 * already had. */
public final class WriteControlledSlots {

    public enum Verdict {
        DEFAULT_PATH,
        CAPTURED,
        REJECTED
    }

    /** Why a slot was refused when it was declared. These are declaration labels, not write-path
     * reject codes: a refused slot never reaches the write path. */
    public enum Refusal {
        NONE,
        WILDCARD_KEY,
        EMPTY_KEY,
        EMPTY_WRITE_SET,
        PLAN_SEQUENCE_INVALID,
        DUPLICATE_KEY
    }

    /** The granularity one declared spatial range is named at: a section is the 16x16x16 block
     * volume the game itself addresses chunks by. */
    public enum CellKind {
        CHUNK,
        SECTION,
        BLOCK
    }

    /** The block position one write is made at, or the absence of one. A caller that cannot read
     * the position says so here, and such a write is never matched against a range. */
    public record WritePosition(boolean readable, int x, int y, int z) {

        public static final WritePosition NONE = new WritePosition(false, 0, 0, 0);

        public static WritePosition at(int x, int y, int z) {
            return new WritePosition(true, x, y, z);
        }
    }

    /** One declared spatial range: the granularity its cells are named at, the cell keys themselves
     * in ascending order, and the fold that names the range. The cells are what membership is
     * decided on; the fold only names them. */
    public record WriteSpace(CellKind kind, long[] cells, String digest) {

        /** A declared bound, not a measured one: a range that names more is refused. */
        public static final int MAX_CELLS = 16;

        /** No range at all: what a declaration that names none carries. */
        public static final WriteSpace NONE = new WriteSpace(null, new long[0], "");

        public WriteSpace {
            cells = canonical(cells);
            digest = kind == null || cells.length == 0 ? "" : foldCells(kind, cells);
        }

        public static WriteSpace of(CellKind kind, long... cells) {
            return new WriteSpace(kind, cells, "");
        }

        /** The cells, ascending, as a copy: a reader that verifies membership reads these. */
        @Override
        public long[] cells() {
            return cells.clone();
        }

        /** Whether one write falls inside this range. The position is folded at this range's own
         * granularity, so a range declared in sections is never asked about a block. */
        public boolean covers(WritePosition position) {
            return wellFormed() && position != null && position.readable()
                && Arrays.binarySearch(cells, cellOf(kind, position)) >= 0;
        }

        public boolean contains(long cellKey) {
            return wellFormed() && Arrays.binarySearch(cells, cellKey) >= 0;
        }

        public int size() {
            return cells.length;
        }

        public boolean wellFormed() {
            return kind != null && cells.length >= 1 && cells.length <= MAX_CELLS;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof WriteSpace space && kind == space.kind
                && Arrays.equals(cells, space.cells);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(kind) + Arrays.hashCode(cells);
        }

        private static long[] canonical(long[] input) {
            if (input == null || input.length == 0) {
                return new long[0];
            }
            long[] sorted = input.clone();
            Arrays.sort(sorted);
            int kept = 0;
            for (int index = 0; index < sorted.length; index++) {
                if (index == 0 || sorted[index] != sorted[index - 1]) {
                    sorted[kept++] = sorted[index];
                }
            }
            return kept == sorted.length ? sorted : Arrays.copyOf(sorted, kept);
        }
    }

    /** The key of one controlled slot. The segment is the fixed token of the spatial range the slot
     * was frozen for, so a key whose segment is not that token names no placeable range. */
    public record SlotKey(String worldId, String domainId, WriteLevel level, String segment,
                          long planSequence) {

        public boolean wildcard() {
            return isWildcard(worldId) || isWildcard(domainId) || isWildcard(segment);
        }

        public boolean incomplete() {
            return blank(worldId) || blank(domainId) || blank(segment) || level == null;
        }

        /** The stable name of one key, so a reader that needs a fixed order over slots has one and
         * does not depend on the iteration order of the table. It is a reading: the table no longer
         * chooses between slots that cover the same write by it. */
        public String rank() {
            return worldId + "|" + domainId + "|" + level + "|" + segment + "|" + planSequence;
        }

        /** The fixed encoding of one range as the segment of a slot key: the granularity and every
         * cell key in ascending order, so a key names its range exactly and not by a fold of it. A
         * range that cannot be written into a key spells nothing. */
        public static String segmentOf(WriteSpace space) {
            if (space == null || !space.wellFormed()) {
                return "";
            }
            long[] cells = space.cells();
            StringBuilder token = new StringBuilder(cells.length * 17 + 8);
            token.append(space.kind()).append(':');
            for (int index = 0; index < cells.length; index++) {
                if (index > 0) {
                    token.append(',');
                }
                token.append(Long.toUnsignedString(cells[index], 16));
            }
            return token.toString();
        }

        private static boolean isWildcard(String value) {
            return "*".equals(value);
        }

        private static boolean blank(String value) {
            return value == null || value.isEmpty();
        }
    }

    /** The fold of a declared write set, over the keys of the set: two sets that declare the same
     * keys fold to the same digest, so the digest names a set wherever it is read - in the slot a
     * claim carries, and in the review that compares a declared set with the set actually held. */
    public static String fold(Collection<String> writeSet) {
        return Long.toHexString(foldValue(writeSet));
    }

    /** The same fold as a value: a producer that reports a batch to the commit log names the write
     * set of the batch by this number, and the two forms of one set are the same number. */
    public static long foldValue(Collection<String> writeSet) {
        long hash = FOLD_BASIS;
        for (String key : new TreeSet<>(writeSet == null ? Set.of() : writeSet)) {
            for (int index = 0; index < key.length(); index++) {
                hash = (hash ^ key.charAt(index)) * FOLD_PRIME;
            }
        }
        return hash;
    }

    /** The fold of the cells of one range, over the canonical cell tokens: the same range folds to
     * the same number wherever it is read. The fold names the range; whether a write is inside it
     * is always decided on the cells themselves. */
    private static String foldCells(CellKind kind, long[] cells) {
        List<String> tokens = new ArrayList<>(cells.length);
        for (long cell : cells) {
            tokens.add(kind.name() + ":" + Long.toUnsignedString(cell, 16));
        }
        return fold(tokens);
    }

    /** The key of one position at one granularity. A section is 16 blocks on a side and its key is
     * the packing the game itself uses, so a negative coordinate needs no bias of its own. */
    private static long cellOf(CellKind kind, WritePosition position) {
        return switch (kind) {
            case CHUNK -> ChunkPos.asLong(position.x() >> 4, position.z() >> 4);
            case SECTION -> SectionPos.asLong(position.x() >> 4, position.y() >> 4, position.z() >> 4);
            case BLOCK -> BlockPos.asLong(position.x(), position.y(), position.z());
        };
    }

    /** One declared slot: the spatial range it covers, the write set it declares and the contract a
     * covered write has to satisfy. A slot that names no range is refused at declaration. */
    public record Slot(SlotKey key, Object levelRef, String planNodeKey, int position,
                       long expectedVersion, long slotGeneration, long worldEpoch, long expireTick,
                       HolderKind holderKind, String holderSiteId, Set<String> writeSet,
                       WriteSpace space) {

        /** A slot that names no spatial range: it is refused when declared, never covering a write. */
        public Slot(SlotKey key, Object levelRef, String planNodeKey, int position,
                    long expectedVersion, long slotGeneration, long worldEpoch, long expireTick,
                    HolderKind holderKind, String holderSiteId, Set<String> writeSet) {
            this(key, levelRef, planNodeKey, position, expectedVersion, slotGeneration, worldEpoch,
                expireTick, holderKind, holderSiteId, writeSet, WriteSpace.NONE);
        }

        public Slot {
            writeSet = writeSet == null ? Set.of() : Set.copyOf(writeSet);
            space = space == null ? WriteSpace.NONE : space;
        }

        public String pathDigest() {
            return fold(writeSet);
        }

        /** The name the readings gave the write set fold; it is the same number. */
        public String writeSetDigest() {
            return pathDigest();
        }

        /** The fold of the declared range, reported beside the path digest. */
        public String spaceDigest() {
            return space.digest();
        }

        public boolean covers(WritePosition position) {
            return space.covers(position);
        }
    }

    /** The facts the table cannot read from its own registries: which world a level belongs to, and
     * which step the frozen plan of the recent ticks holds for a slot. While nothing is wired, no
     * world is named and no step is held, so a covered write is refused instead of captured. */
    public interface Source {

        /** The world a write is happening in, as the platform names it; empty while nothing is wired. */
        String worldOf(Object levelRef);

        /** The step the plan holds for a slot, or null while the plan does not carry it. */
        Step stepOf(String worldId, String domainId, String planNodeKey);

        /** A source that names no world and holds no step. */
        Source NONE = new Source() {

            @Override
            public String worldOf(Object levelRef) {
                return "";
            }

            @Override
            public Step stepOf(String worldId, String domainId, String planNodeKey) {
                return null;
            }
        };

        record Step(long planSequence, int position) {
        }
    }

    /** One judgement. A captured write carries the plan identity the contract resolved; a rejected
     * one names the term that failed. The two folds are reported apart: the path categories the
     * declaration claimed and the spatial range it named. */
    public record Decision(Verdict verdict, SlotKey key, String planNodeKey, long planSequence,
                           int position, String pathDigest, String spaceDigest, int covering,
                           String reason) {

        /** The name the readings gave the path digest; it is the same number. */
        public String writeSetDigest() {
            return pathDigest;
        }

        private static Decision defaultPath(String reason) {
            return new Decision(Verdict.DEFAULT_PATH, null, "", 0L, -1, "", "", 0, reason);
        }
    }

    /** The refusal of one covered write, with the elements a diagnosis needs: the world, the tick,
     * the holder, the version that was expected and the one that was carried, the slot, the path
     * categories and the spatial range, and how many active ranges covered the write. */
    public record WriteRefusal(String reason, String worldId, String domainId, WriteLevel level,
                          String segment, long planSequence, String planNodeKey, String siteId,
                          HolderKind holderKind, long tickIndex, long expectedVersion,
                          long carriedVersion, long worldEpoch, long slotGeneration,
                          String pathDigest, String spaceDigest, int covering) {

        /** The name the readings gave the path digest; it is the same number. */
        public String writeSetDigest() {
            return pathDigest;
        }
    }

    private static final long FOLD_BASIS = 0xcbf29ce484222325L;
    private static final long FOLD_PRIME = 0x100000001b3L;
    private static final String REASON_UNREADABLE_POSITION = "segment";
    private static final String REASON_AMBIGUOUS_RANGE = "owner";

    /** The instance a guard asks while no claim table is wired into it. */
    public static final WriteControlledSlots NOTHING = new WriteControlledSlots(new OwnerRegistry(),
        WriteVersionSlots.NOTHING, new WorldEpochs(), Source.NONE);

    private final OwnerRegistry owners;
    private final WriteVersionSlots versions;
    private final WorldEpochs epochs;
    private final Source source;
    private final Map<SlotKey, Slot> slots = new ConcurrentHashMap<>();
    private final Map<SlotKey, Long> generations = new ConcurrentHashMap<>();
    private final AtomicLong registered = new AtomicLong();
    private final AtomicLong renewed = new AtomicLong();
    private final AtomicLong advanced = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final Map<Refusal, AtomicLong> refusals = new ConcurrentHashMap<>();
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong defaultPath = new AtomicLong();
    private final AtomicLong captured = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong notExecutable = new AtomicLong();
    private volatile boolean enabled;
    private volatile Decision lastDecision;
    private volatile WriteRefusal lastRefusal;
    private volatile Slot lastRefusingSlot;
    private volatile Slot lastCoveredSlot;

    /** Creates the table. */
    public WriteControlledSlots(OwnerRegistry owners, WriteVersionSlots versions, WorldEpochs epochs,
                                Source source) {
        this.owners = owners;
        this.versions = versions;
        this.epochs = epochs;
        this.source = source;
    }

    /** Decides whether the claim table is consulted at all. Off, every write of the short path is
     * counted as a default-path write and no slot is ever looked up. */
    public void refresh(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Declares one slot. A slot that names no usable range, or whose key does not spell the range
     * it carries, declares an empty effective write set and is refused here, when it is declared,
     * and not repaired when a write arrives. */
    public Refusal register(Slot slot) {
        Refusal refusal = refusalOf(slot);
        if (refusal != Refusal.NONE) {
            noteRefusal(refusal);
            return refusal;
        }
        SlotKey key = slot.key();
        Long seated = generations.get(key);
        if (seated != null && seated == slot.slotGeneration()) {
            noteRefusal(Refusal.DUPLICATE_KEY);
            return Refusal.DUPLICATE_KEY;
        }
        if (seated != null) {
            renewed.incrementAndGet();
        }
        slots.put(key, slot);
        generations.put(key, slot.slotGeneration());
        registered.incrementAndGet();
        return Refusal.NONE;
    }

    /** Retires the slot of one key. The writes it covered fall back to the default path. */
    public boolean retire(SlotKey key) {
        generations.remove(key);
        return slots.remove(key) != null;
    }

    /** Retires the generation of one key without seating a new slot: everything the seated slot
     * covers is refused from here on, until a slot of the new generation is declared. */
    public long advance(SlotKey key) {
        long minted = generations.merge(key, 1L, Long::sum);
        advanced.incrementAndGet();
        return minted;
    }

    /** Judges one write of one write path against the table. The position the write is made at is
     * what decides whether an active range covers it; a caller that cannot name it says so and the
     * write is refused rather than matched at world level. */
    public Decision judge(Object levelRef, String domainKey, WritePosition position, long tickIndex) {
        if (levelRef == null || domainKey == null || domainKey.isEmpty()) {
            notExecutable.incrementAndGet();
            return lastDecision = Decision.defaultPath("NOT-EXECUTABLE");
        }
        attempts.incrementAndGet();
        if (!enabled) {
            defaultPath.incrementAndGet();
            return lastDecision = Decision.defaultPath("");
        }
        List<Slot> candidates = candidates(levelRef, domainKey);
        if (candidates.isEmpty()) {
            defaultPath.incrementAndGet();
            return lastDecision = Decision.defaultPath("");
        }
        if (position == null || !position.readable()) {
            // The write could be inside a declared range and the position it is made at cannot be
            // read: refusing is the only answer that does not match it against a whole world.
            return refuse(candidates.get(0), REASON_UNREADABLE_POSITION, 0, tickIndex);
        }
        Map<String, Slot> covering = new LinkedHashMap<>();
        for (Slot slot : candidates) {
            if (slot.space().covers(position)) {
                covering.putIfAbsent(identityOf(slot), slot);
            }
        }
        if (covering.isEmpty()) {
            defaultPath.incrementAndGet();
            return lastDecision = Decision.defaultPath("");
        }
        if (covering.size() > 1) {
            // Two active ranges cover the same write, so which job wrote it cannot be decided and
            // neither the first one found nor the lowest ranked one may stand for both.
            return refuse(covering.values().iterator().next(), REASON_AMBIGUOUS_RANGE,
                covering.size(), tickIndex);
        }
        Slot slot = covering.values().iterator().next();
        lastCoveredSlot = slot;
        String world = source.worldOf(levelRef);
        Source.Step step = source.stepOf(slot.key().worldId(), slot.key().domainId(),
            slot.planNodeKey());
        WriteRefusal refusal = inspect(slot, world, step, tickIndex, covering.size());
        if (refusal != null) {
            rejected.incrementAndGet();
            lastRefusal = refusal;
            lastRefusingSlot = slot;
            lastCoveredSlot = null;
            return lastDecision = new Decision(Verdict.REJECTED, slot.key(), slot.planNodeKey(), 0L,
                -1, slot.pathDigest(), slot.spaceDigest(), covering.size(), refusal.reason());
        }
        captured.incrementAndGet();
        return lastDecision = new Decision(Verdict.CAPTURED, slot.key(), slot.planNodeKey(),
            step.planSequence(), step.position(), slot.pathDigest(), slot.spaceDigest(),
            covering.size(), "");
    }

    /** Judges one write whose caller cannot name the position it is made at. A write no active
     * range could cover keeps the path it had; one that could is refused. */
    public Decision judge(Object levelRef, String domainKey, long tickIndex) {
        return judge(levelRef, domainKey, WritePosition.NONE, tickIndex);
    }

    /** The active slots that could cover one write: they were declared for the very level the write
     * arrives on, their write set names the write path it takes, and the range they carry is
     * usable. A slot with no usable range covers nothing. */
    private List<Slot> candidates(Object levelRef, String domainKey) {
        List<Slot> found = new ArrayList<>();
        for (Slot slot : slots.values()) {
            if (slot.levelRef() == levelRef && slot.writeSet().contains(domainKey)
                && slot.space().wellFormed()) {
                found.add(slot);
            }
        }
        return found;
    }

    /** The identity a covering slot is counted under: two entries that name the same slot instance
     * are one covering slot and not two, so only genuinely distinct ranges make a write ambiguous. */
    private static String identityOf(Slot slot) {
        return slot.key().rank() + "#" + slot.slotGeneration();
    }

    /** The exact range one seat was declared with, so a reader can verify whether a write is inside
     * it against the cells themselves instead of against the fold. */
    public WriteSpace spaceOf(SlotKey key) {
        Slot slot = key == null ? null : slots.get(key);
        return slot == null ? WriteSpace.NONE : slot.space();
    }

    private Decision refuse(Slot slot, String reason, int covering, long tickIndex) {
        SlotKey key = slot.key();
        long carried = versions.carried(key.worldId(), key.level(), key.domainId());
        WriteRefusal refusal = refusal(slot, reason, carried, tickIndex, covering);
        rejected.incrementAndGet();
        lastRefusal = refusal;
        lastRefusingSlot = slot;
        lastCoveredSlot = null;
        return lastDecision = new Decision(Verdict.REJECTED, key, slot.planNodeKey(), 0L, -1,
            slot.pathDigest(), slot.spaceDigest(), covering, reason);
    }

    /** The contract of a covered write: the world, the holder the token names, the version the slot
     * and the token expect, the generation, the expiry and the plan step the slot was frozen with.
     * A term that cannot be read at all fails the same way a wrong one does. */
    private WriteRefusal inspect(Slot slot, String world, Source.Step step, long tickIndex,
                                 int covering) {
        SlotKey key = slot.key();
        long carried = versions.carried(key.worldId(), key.level(), key.domainId());
        if (world.isEmpty() || !world.equals(key.worldId())) {
            return refusal(slot, "world", carried, tickIndex, covering);
        }
        OwnerToken token = owners.lookup(key.worldId(), key.level(), key.domainId()).orElse(null);
        if (token == null || token.holderKind() != slot.holderKind()
            || !token.holderSiteId().equals(slot.holderSiteId())) {
            return refusal(slot, "owner", carried, tickIndex, covering);
        }
        if (token.expiredAt(tickIndex) || slot.expireTick() <= 0L || tickIndex >= slot.expireTick()) {
            return refusal(slot, "expiry", carried, tickIndex, covering);
        }
        if (carried <= WriteVersionSlots.NOT_CARRIED || carried != slot.expectedVersion()
            || token.expectedVersion() != slot.expectedVersion()) {
            return refusal(slot, "version", carried, tickIndex, covering);
        }
        Long generation = generations.get(key);
        if (generation == null || generation != slot.slotGeneration()) {
            return refusal(slot, "generation", carried, tickIndex, covering);
        }
        long live = epochs.epochOf(key.worldId());
        if (live <= 0L || live != slot.worldEpoch()) {
            return refusal(slot, "world_epoch", carried, tickIndex, covering);
        }
        if (slot.planNodeKey().isEmpty() || step == null) {
            return refusal(slot, "plan", carried, tickIndex, covering);
        }
        if (step.planSequence() != key.planSequence()) {
            return refusal(slot, "plan_sequence", carried, tickIndex, covering);
        }
        return null;
    }

    private WriteRefusal refusal(Slot slot, String reason, long carried, long tickIndex,
                                 int covering) {
        SlotKey key = slot.key();
        return new WriteRefusal(reason, key.worldId(), key.domainId(), key.level(), key.segment(),
            key.planSequence(), slot.planNodeKey(), slot.holderSiteId(), slot.holderKind(), tickIndex,
            slot.expectedVersion(), carried, slot.worldEpoch(), slot.slotGeneration(),
            slot.pathDigest(), slot.spaceDigest(), covering);
    }

    private static Refusal refusalOf(Slot slot) {
        if (slot == null || slot.key() == null) {
            return Refusal.EMPTY_KEY;
        }
        if (slot.key().wildcard()) {
            return Refusal.WILDCARD_KEY;
        }
        if (!namesItsSpace(slot)) {
            return Refusal.EMPTY_WRITE_SET;
        }
        if (slot.key().incomplete()) {
            return Refusal.EMPTY_KEY;
        }
        if (slot.writeSet().isEmpty()) {
            return Refusal.EMPTY_WRITE_SET;
        }
        if (slot.key().planSequence() <= 0L) {
            return Refusal.PLAN_SEQUENCE_INVALID;
        }
        return Refusal.NONE;
    }

    /** Whether a slot carries a range that can be written into a key and whether its key spells
     * that very range. A slot whose segment is not the token of the range it carries, or that
     * carries no range at all, has an empty effective write set. */
    private static boolean namesItsSpace(Slot slot) {
        WriteSpace space = slot.space();
        return space.wellFormed() && SlotKey.segmentOf(space).equals(slot.key().segment());
    }

    private void noteRefusal(Refusal refusal) {
        refused.incrementAndGet();
        refusals.computeIfAbsent(refusal, key -> new AtomicLong()).incrementAndGet();
    }

    public int slots() {
        return slots.size();
    }

    public long registeredCount() {
        return registered.get();
    }

    public long renewedCount() {
        return renewed.get();
    }

    public long advancedCount() {
        return advanced.get();
    }

    public long refusedCount() {
        return refused.get();
    }

    public long refusedCount(Refusal refusal) {
        AtomicLong cell = refusals.get(refusal);
        return cell == null ? 0L : cell.get();
    }

    public long attemptsCount() {
        return attempts.get();
    }

    public long defaultPathCount() {
        return defaultPath.get();
    }

    public long capturedCount() {
        return captured.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    /** How often a caller handed the table nothing to judge. It is counted on its own so the three
     * verdicts stay the three a write can leave with. */
    public long notExecutableCount() {
        return notExecutable.get();
    }

    /** The conservation of the claim face: every write the table judged left by exactly one of the
     * three verdicts, so a claim cannot hide a write and no write is judged twice. */
    public boolean conservationHolds() {
        return captured.get() + defaultPath.get() + rejected.get() == attempts.get();
    }

    public Decision lastDecision() {
        return lastDecision;
    }

    public WriteRefusal lastRefusal() {
        return lastRefusal;
    }

    /** The slot the last refusal was taken against, so a reader can name the slot as a whole. */
    public Slot lastRefusingSlot() {
        return lastRefusingSlot;
    }

    /** The slot the newest judgement was taken against, captured or refused. The thread that made
     * the write reads it to carry the claim of the write it just handed to the judged path. */
    public Slot lastCoveredSlot() {
        return lastCoveredSlot;
    }

    /** Clears the counters and the slot table. */
    public void reset() {
        slots.clear();
        generations.clear();
        registered.set(0L);
        renewed.set(0L);
        advanced.set(0L);
        refused.set(0L);
        refusals.clear();
        attempts.set(0L);
        defaultPath.set(0L);
        captured.set(0L);
        rejected.set(0L);
        notExecutable.set(0L);
        lastDecision = null;
        lastRefusal = null;
        lastRefusingSlot = null;
        lastCoveredSlot = null;
    }
}
