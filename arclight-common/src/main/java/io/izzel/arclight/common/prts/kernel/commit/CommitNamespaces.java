/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.concurrent.atomic.AtomicLong;

/** The runtime namespace a producer or a controller runs under: written so a record of an earlier
 * lifecycle is refused, and never read by a comparison between two runs, which decides staleness by
 * the lifetime and the reset generation while the thread is a diagnostic column. The reset
 * generation has an owner here and no production caller: the readings reset of the kernel is the
 * point that will advance it, and it is not wired in this change. */
public final class CommitNamespaces {

    public static final long NO_LIFETIME = 0L;

    public static final long NO_THREAD = -1L;

    public record Namespace(String ownerId, long lifetime, long resetEpoch, long threadId) {

        public static final Namespace NONE = new Namespace("", NO_LIFETIME, 0L, NO_THREAD);

        public Namespace {
            ownerId = ownerId == null ? "" : ownerId;
        }

        public boolean declared() {
            return !ownerId.isEmpty() && lifetime != NO_LIFETIME;
        }

        /** Whether this namespace belongs to an earlier lifecycle: another module lifetime, or
         * another reset generation. The thread column is not read. */
        public boolean staleAgainst(Namespace current) {
            if (current == null) {
                return true;
            }
            return lifetime != current.lifetime || resetEpoch != current.resetEpoch;
        }

        public String text() {
            return ownerId + "/" + lifetime + "/" + resetEpoch + "/" + threadId;
        }
    }

    /** Whether a minted lifetime survives a restart unchanged is not tested here. */
    public static final class Lifetimes {

        private static final long SEED = System.nanoTime() & 0x0000ffffffffffffL;
        private static final AtomicLong NEXT = new AtomicLong(1L);

        private Lifetimes() {
        }

        public static long mint() {
            return SEED + NEXT.getAndIncrement();
        }
    }

    /** Owns the reset generation of one lifecycle and is the single point that enters the next one. */
    public static final class ResetEpochs {

        private final AtomicLong epoch = new AtomicLong();

        public long current() {
            return epoch.get();
        }

        public long advance() {
            return epoch.incrementAndGet();
        }
    }

    private CommitNamespaces() {
    }
}
