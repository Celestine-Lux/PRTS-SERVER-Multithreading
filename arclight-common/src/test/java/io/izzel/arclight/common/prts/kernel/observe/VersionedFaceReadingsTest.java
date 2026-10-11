/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The keys the versioned face adds to the readout, and the keys of the plain log beside them. Every
 * added key reads zero or a dash while the face is off, and the wiring column says of itself that
 * nothing is bound in this build. */
class VersionedFaceReadingsTest {

    private static final List<String> ADDED = List.of("product.commit.observation_only",
        "product.commit.enabled", "product.commit.executable", "product.commit.schema_version",
        "product.commit.effect_capacity_bytes", "product.commit.effect_capacity_records",
        "product.commit.effect_records", "product.commit.effect_bytes",
        "product.commit.effect_reserved_bytes", "product.commit.effect_pins",
        "product.commit.effect_evicted", "product.commit.effect_missing", "product.commit.typed_stored",
        "product.commit.typed_refused", "product.commit.identity_collisions",
        "product.commit.effect_missing_steps", "product.commit.reserved_slots",
        "product.commit.reservation_refusals", "product.commit.refusal",
        "product.commit.store_wiring");

    @AfterEach
    void clear() {
        System.clearProperty("arclight.prts.kernel");
        KernelModule.instance().resetReadings();
    }

    private static List<String> export() {
        return KernelReadings.export(KernelModule.instance());
    }

    private static String line(List<String> export, String key) {
        List<String> hits = new ArrayList<>();
        for (String row : export) {
            if (row.startsWith(key + "=")) {
                hits.add(row);
            }
        }
        assertEquals(1, hits.size(), key + " is one line of the readout");
        return hits.get(0);
    }

    @Test
    void everyAddedKeyIsThereOnceAndReadsZeroOrADashWhileTheFaceIsOff() {
        System.setProperty("arclight.prts.kernel", "true");
        List<String> export = export();

        for (String key : ADDED) {
            assertTrue(line(export, key).startsWith(key + "="), key);
        }
        assertEquals("product.commit.observation_only=1", line(export, "product.commit.observation_only"));
        assertEquals("product.commit.enabled=0", line(export, "product.commit.enabled"));
        assertEquals("product.commit.executable=0", line(export, "product.commit.executable"));
        assertEquals("product.commit.schema_version=-1", line(export, "product.commit.schema_version"));
        assertEquals("product.commit.effect_capacity_bytes=0", line(export, "product.commit.effect_capacity_bytes"));
        assertEquals("product.commit.effect_capacity_records=0",
            line(export, "product.commit.effect_capacity_records"));
        assertEquals("product.commit.effect_records=0", line(export, "product.commit.effect_records"));
        assertEquals("product.commit.effect_bytes=0", line(export, "product.commit.effect_bytes"));
        assertEquals("product.commit.effect_pins=0", line(export, "product.commit.effect_pins"));
        assertEquals("product.commit.typed_stored=0", line(export, "product.commit.typed_stored"));
        assertEquals("product.commit.typed_refused=0", line(export, "product.commit.typed_refused"));
        assertEquals("product.commit.identity_collisions=0", line(export, "product.commit.identity_collisions"));
        assertEquals("product.commit.reserved_slots=0", line(export, "product.commit.reserved_slots"));
        assertEquals("product.commit.refusal=-", line(export, "product.commit.refusal"));
        assertEquals("product.commit.store_wiring=NOT_WIRED", line(export, "product.commit.store_wiring"),
            "no effect store is bound in this build, and the readout says so");
    }

    @Test
    void theKeysOfThePlainLogAreStillThereAndReadWhatTheyAlwaysRead() {
        System.setProperty("arclight.prts.kernel", "true");
        List<String> export = export();

        assertEquals("commit.observation_only=1", line(export, "commit.observation_only"));
        assertEquals("commit.planes=0", line(export, "commit.planes"));
        assertEquals("commit.ring_depth=0", line(export, "commit.ring_depth"));
        assertEquals("commit.entries=0", line(export, "commit.entries"));
        assertEquals("commit.accepted=0", line(export, "commit.accepted"));
        assertEquals("commit.dropped=0", line(export, "commit.dropped"));
        assertEquals("commit.refused_full=0", line(export, "commit.refused_full"));
        assertEquals("commit.order_violations=0", line(export, "commit.order_violations"));
        assertEquals("commit.replay_order_matches=-1", line(export, "commit.replay_order_matches"));
        assertFalse(export.contains("commit.typed_stored="), "the added keys are their own face");
        assertFalse(export.contains("product.commit.effect_records=NOT"), "a count is not a word");
    }
}
