/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import java.util.concurrent.atomic.LongAdder;

/** The seam the chunk cache reports its demand side through: what was asked of it, what the ask was
 * answered with, and the futures it handed out. The calls are not changed and nothing is cancelled,
 * delayed or reordered; every call is a no-op while no tap is installed. A tap that has no use for
 * the chunk implements the entries that do not name it, and is told about each ask once. A tap
 * that throws is not the ask failing: its count is dropped, and a stamp it cannot answer reads -1,
 * which is no observation time to report -- not a refusal code, not a failure of the host and not a
 * new reading key. A drop is not silent: every observation a guard drops is tallied, so a watch
 * that has failed says so by the tally alone, and the host path decides nothing by it. */
public final class PrtsChunkDemand {

    public interface DemandTap {

        /** One ask entered the cache. @param blocking whether the caller waits for the answer */
        void demandStarted(String worldId, String status, boolean blocking);

        /** One ask entered the cache, with the chunk it named; the default keeps the old count.
         * @param blocking whether the caller waits for the answer, as the host's own ask carried it */
        default void demandStarted(String worldId, int chunkX, int chunkZ, String status,
                                   boolean blocking) {
            demandStarted(worldId, status, blocking);
        }

        /** The ask returned. @param satisfied whether it carried a chunk at the wanted status */
        void demandFinished(String worldId, String status, boolean blocking, boolean satisfied);

        /** A future-returning ask entered the cache. */
        void futureOpened(String worldId, String status);

        /** A future-returning ask entered the cache, with the chunk it named; the default keeps the
         * old count.
         * @param load whether the future may load the chunk when it is not there, as the host's own
         *             ask carried it */
        default void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                      boolean load) {
            futureOpened(worldId, status);
        }

        /** @return the stamp the matching open left, or -1 when there is no observation time */
        long futureOpenedAt();

        /** The future was handed to the caller; it is outstanding until it completes. */
        void futureTaken(String worldId, String status);

        /** The future completed, satisfied or not. */
        void futureCompleted(String worldId, String status, long startedNanos, boolean satisfied);
    }

    private static volatile DemandTap tap;

    /** One count per observation a guard dropped because the watch threw. Kept for the life of
     * the class: an install replaces the watch and moves no tally. */
    private static final LongAdder dropped = new LongAdder();

    private PrtsChunkDemand() {
    }

    public static void install(DemandTap watcher) {
        tap = watcher;
    }

    public static boolean installed() {
        return tap != null;
    }

    public static DemandTap watcher() {
        return tap;
    }

    /** How many observations the guards have dropped because a watch threw. The tally says a
     * watch has failed; it decides nothing for the host path, and it reads the same while no
     * watch is installed. */
    public static long droppedObservations() {
        return dropped.sum();
    }

    public static void demandStarted(String worldId, String status, boolean blocking) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.demandStarted(worldId, status, blocking);
        } catch (Throwable ignored) {
            // A watcher that throws is not the ask failing: the count is dropped, and tallied.
            dropped.increment();
        }
    }

    public static void demandStarted(String worldId, int chunkX, int chunkZ, String status,
                                     boolean blocking) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.demandStarted(worldId, chunkX, chunkZ, status, blocking);
        } catch (Throwable ignored) {
            dropped.increment();
        }
    }

    public static void demandFinished(String worldId, String status, boolean blocking,
                                      boolean satisfied) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.demandFinished(worldId, status, blocking, satisfied);
        } catch (Throwable ignored) {
            dropped.increment();
        }
    }

    public static void futureOpened(String worldId, String status) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.futureOpened(worldId, status);
        } catch (Throwable ignored) {
            dropped.increment();
        }
    }

    public static void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                        boolean load) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.demandDescriptor(worldId, chunkX, chunkZ, status, load);
        } catch (Throwable ignored) {
            dropped.increment();
        }
    }

    public static long futureOpenedAt() {
        DemandTap watcher = tap;
        if (watcher == null) {
            return -1L;
        }
        try {
            return watcher.futureOpenedAt();
        } catch (Throwable ignored) {
            // A watch that cannot answer leaves no observation time: -1, which is not a refusal.
            dropped.increment();
            return -1L;
        }
    }

    public static void futureTaken(String worldId, String status) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.futureTaken(worldId, status);
        } catch (Throwable ignored) {
            dropped.increment();
        }
    }

    public static void futureCompleted(String worldId, String status, long startedNanos,
                                       boolean satisfied) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.futureCompleted(worldId, status, startedNanos, satisfied);
        } catch (Throwable ignored) {
            dropped.increment();
        }
    }
}
