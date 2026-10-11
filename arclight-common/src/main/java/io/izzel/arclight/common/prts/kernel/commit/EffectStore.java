/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** The typed effect records one commit line may point at: one immutable record per line that
 * declares one, bounded by the bytes and by the record count the store was built with, and never
 * evicted while a line still holds it.
 *
 * <p>Room is taken before anything is stored: a reservation charges the record it will hold, so a
 * second record cannot be admitted into room the first one already owns, and a reservation that is
 * not used is given back. A store built with no room at all is refused rather than accepted as an
 * empty and always-failing table.
 *
 * <p>The store is in memory only: nothing here writes a file, so nothing here survives a restart.
 * A reference that cannot be resolved is answered as missing and never as an empty record. */
public final class EffectStore {

    /** One effect a producer declares: the operation, the outcome and the rows describing what was
     * published. The rows are normalized when the record is stored, not when they arrive. */
    public record Effect(long effectRef, String operationKind, String outcome, List<String> rows) {

        public Effect {
            operationKind = operationKind == null ? "" : operationKind;
            outcome = outcome == null ? "" : outcome;
            rows = List.copyOf(rows == null ? List.of() : rows);
        }
    }

    /** A stored record: the normalized rows, the field names they declare, the digest of the rows
     * read as bytes, the room it takes and the order it was stored in. */
    public record Recorded(long effectRef, String operationKind, String outcome, List<String> declared,
                           List<String> content, String contentDigest, int bytes, long sequence) {

        public Recorded {
            declared = List.copyOf(declared);
            content = List.copyOf(content);
        }
    }

    /** The room one effect was charged for, before anything was stored. */
    public record Slot(long ticket, long effectRef, int bytes) {
    }

    /** What one reservation or one store did. */
    public record Result(boolean admitted, RejectCode code, Slot slot, Recorded record) {
    }

    private static final int HEADER_BYTES = 48;
    private static final int ROW_BYTES = 4;
    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private final int capacityBytes;
    private final int capacityRecords;
    private final Map<Long, Recorded> records = new LinkedHashMap<>();
    private final Map<Long, Slot> slots = new LinkedHashMap<>();
    private final Map<Long, Integer> pins = new LinkedHashMap<>();
    private int usedBytes;
    private int reservedBytes;
    private long tickets;
    private long sequence;
    private long stored;
    private long refused;
    private long released;
    private long committed;
    private long evicted;
    private long missing;
    private long evictionRefusals;

    /** A store with no byte or no record room is not executable: it is refused here instead of
     * answering every reservation with a refusal while it looks like an empty and healthy table. */
    public EffectStore(int capacityBytes, int capacityRecords) {
        if (capacityBytes <= 0 || capacityRecords <= 0) {
            throw new IllegalArgumentException("an effect store needs room in bytes and in records: "
                + capacityBytes + " bytes, " + capacityRecords + " records");
        }
        this.capacityBytes = capacityBytes;
        this.capacityRecords = capacityRecords;
    }

    /** Charges the room one effect will take. Nothing is stored yet; the slot is handed back by
     * {@link #commit} or by {@link #release}. */
    public Result reserve(Effect effect) {
        if (effect == null || effect.effectRef() <= 0L) {
            refused++;
            return new Result(false, RejectCode.COUNTER_MISSING, null, null);
        }
        List<String> content = normalize(effect.rows());
        int bytes = bytesOf(content);
        if (records.containsKey(effect.effectRef()) || holdsTicketFor(effect.effectRef())
            || records.size() + slots.size() + 1 > capacityRecords
            || usedBytes + reservedBytes + bytes > capacityBytes) {
            refused++;
            return new Result(false, RejectCode.QUEUE_CAP_EXCEEDED, null, null);
        }
        Slot slot = new Slot(++tickets, effect.effectRef(), bytes);
        slots.put(slot.ticket(), slot);
        reservedBytes += bytes;
        return new Result(true, null, slot, null);
    }

    /** Stores the record one reservation was charged for. The content is normalized again: a
     * reservation whose record changed in between is refused and its room is given back. */
    public Result commit(Slot slot, Effect effect) {
        Slot held = slot == null ? null : slots.get(slot.ticket());
        if (held == null || effect == null || held.effectRef() != effect.effectRef()) {
            refused++;
            return new Result(false, RejectCode.COUNTER_MISSING, null, null);
        }
        List<String> content = normalize(effect.rows());
        int bytes = bytesOf(content);
        if (bytes != held.bytes()) {
            release(held);
            refused++;
            return new Result(false, RejectCode.VERSION_MISMATCH, null, null);
        }
        slots.remove(held.ticket());
        reservedBytes -= held.bytes();
        Recorded record = new Recorded(effect.effectRef(), effect.operationKind(), effect.outcome(),
            declaredOf(content), content, digestOf(content), bytes, ++sequence);
        records.put(record.effectRef(), record);
        pins.put(record.effectRef(), 1);
        usedBytes += bytes;
        stored++;
        committed++;
        return new Result(true, null, null, record);
    }

    /** Gives back a reservation that was not used. A ticket that is not held is a no-op. */
    public boolean release(Slot slot) {
        Slot held = slot == null ? null : slots.remove(slot.ticket());
        if (held == null) {
            return false;
        }
        reservedBytes -= held.bytes();
        released++;
        return true;
    }

    /** The stored record one reference names, or null and a missing count when there is none. */
    public Recorded record(long effectRef) {
        Recorded record = records.get(effectRef);
        if (record == null) {
            missing++;
        }
        return record;
    }

    /** Adds one reference to a stored record. The line that points at a record holds one. */
    public boolean pin(long effectRef) {
        if (!records.containsKey(effectRef)) {
            missing++;
            return false;
        }
        pins.merge(effectRef, 1, Integer::sum);
        return true;
    }

    /** Drops one reference from a stored record. */
    public boolean unpin(long effectRef) {
        Integer held = pins.get(effectRef);
        if (held == null || held <= 0) {
            return false;
        }
        if (held == 1) {
            pins.remove(effectRef);
        } else {
            pins.put(effectRef, held - 1);
        }
        return true;
    }

    /** Drops a record and gives its room back. A record a line still holds is not evicted. */
    public boolean evict(long effectRef) {
        Recorded record = records.get(effectRef);
        if (record == null) {
            missing++;
            return false;
        }
        if (pins.getOrDefault(effectRef, 0) > 0) {
            evictionRefusals++;
            return false;
        }
        records.remove(effectRef);
        usedBytes -= record.bytes();
        evicted++;
        return true;
    }

    private boolean holdsTicketFor(long effectRef) {
        for (Slot slot : slots.values()) {
            if (slot.effectRef() == effectRef) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalize(List<String> rows) {
        TreeSet<String> normalized = new TreeSet<>();
        for (String row : rows) {
            if (row == null) {
                continue;
            }
            String trimmed = row.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> declaredOf(List<String> content) {
        TreeSet<String> names = new TreeSet<>();
        for (String row : content) {
            int equals = row.indexOf('=');
            String name = (equals < 0 ? row : row.substring(0, equals)).trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static int bytesOf(List<String> content) {
        int bytes = HEADER_BYTES;
        for (String row : content) {
            bytes += row.getBytes(StandardCharsets.UTF_8).length + ROW_BYTES;
        }
        return bytes;
    }

    private static String digestOf(List<String> content) {
        long hash = OFFSET_BASIS;
        for (String row : content) {
            for (byte value : row.getBytes(StandardCharsets.UTF_8)) {
                hash ^= (value & 0xff);
                hash *= PRIME;
            }
            hash ^= '\n';
            hash *= PRIME;
        }
        return Long.toHexString(hash);
    }

    public int capacityBytes() {
        return capacityBytes;
    }

    public int capacityRecords() {
        return capacityRecords;
    }

    public int records() {
        return records.size();
    }

    public int bytes() {
        return usedBytes;
    }

    public int reservedBytes() {
        return reservedBytes;
    }

    public int reservedRecords() {
        return slots.size();
    }

    public int pins() {
        int held = 0;
        for (Integer count : pins.values()) {
            held += count;
        }
        return held;
    }

    public long tickets() {
        return tickets;
    }

    public long stored() {
        return stored;
    }

    public long committed() {
        return committed;
    }

    public long refused() {
        return refused;
    }

    public long released() {
        return released;
    }

    public long evicted() {
        return evicted;
    }

    public long missing() {
        return missing;
    }

    public long evictionRefusals() {
        return evictionRefusals;
    }

    public List<Long> references() {
        return new ArrayList<>(records.keySet());
    }
}
