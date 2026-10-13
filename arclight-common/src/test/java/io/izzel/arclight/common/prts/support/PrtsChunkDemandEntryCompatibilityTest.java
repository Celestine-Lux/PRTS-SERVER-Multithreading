/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two entries that name the chunk, read by name and by shape: a tap written before them keeps
 * one count per ask, because the entry it does not implement is the default that carries the count
 * for it. The lookup is by reflection so that a tree without the entry fails this case instead of
 * failing to build it. */
class PrtsChunkDemandEntryCompatibilityTest {

    private static final Class<?>[] CHUNK_SHAPE =
        {String.class, int.class, int.class, String.class, boolean.class};

    @AfterEach
    void detach() {
        PrtsChunkDemand.install(null);
    }

    /** The entry of one ask, in the shape that names the chunk. */
    private static Method entry(String name) throws NoSuchMethodException {
        return PrtsChunkDemand.DemandTap.class.getMethod(name, CHUNK_SHAPE);
    }

    /** A tap written against the seam as it was: the world and the status, and nothing else. */
    private static final class LegacyTap implements PrtsChunkDemand.DemandTap {

        final List<String> events = new ArrayList<>();
        int blocking;

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
            events.add("started " + worldId + " " + status);
            if (blocking) {
                this.blocking++;
            }
        }

        @Override
        public void demandFinished(String worldId, String status, boolean blocking, boolean satisfied) {
        }

        @Override
        public void futureOpened(String worldId, String status) {
            events.add("opened " + worldId + " " + status);
        }

        @Override
        public long futureOpenedAt() {
            return -1L;
        }

        @Override
        public void futureTaken(String worldId, String status) {
        }

        @Override
        public void futureCompleted(String worldId, String status, long startedNanos,
                                    boolean satisfied) {
        }
    }

    @Test
    void bothAsksHaveAnEntryThatNamesTheChunkAndKeepsTheOldCount() throws Exception {
        assertTrue(entry("demandStarted").isDefault(),
            "a tap that only knows the world and the status is not forced to change");
        assertTrue(entry("demandDescriptor").isDefault(),
            "a tap that only knows the world and the status is not forced to change");
    }

    @Test
    void aTapWrittenBeforeTheChunkIsToldOncePerAskAndNoMore() throws Exception {
        LegacyTap tap = new LegacyTap();
        PrtsChunkDemand.install(tap);
        entry("demandDescriptor").invoke(tap, "minecraft:overworld", 3, -7, "full", true);
        entry("demandStarted").invoke(tap, "minecraft:overworld", 3, -7, "full", false);

        assertEquals(List.of("opened minecraft:overworld full", "started minecraft:overworld full"),
            tap.events, "one ask reaches the old entry once, through the default that dropped the chunk");
    }

    @Test
    void theFlagTheAskCarriedReachesTheOldEntry() throws Exception {
        LegacyTap tap = new LegacyTap();
        PrtsChunkDemand.install(tap);
        entry("demandStarted").invoke(tap, "minecraft:the_end", 0, 0, "features", true);

        assertEquals(1, tap.blocking, "the flag is copied with the ask, not read from anywhere else");
    }
}
