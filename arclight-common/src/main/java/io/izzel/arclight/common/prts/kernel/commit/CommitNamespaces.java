/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.concurrent.atomic.AtomicLong;

/** The runtime namespace a producer or a controller runs under: written so a record of an earlier
 * lifecycle is refused. Staleness and collisions are decided on {@link Namespace#lifecycleKey()}
 * alone; the thread column is a diagnostic and is never read. */
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

        public boolean staleAgainst(Namespace current) {
            if (current == null) {
                return true;
            }
            return lifetime != current.lifetime || resetEpoch != current.resetEpoch;
        }

        /** The lifecycle this namespace belongs to: owner, lifetime and reset generation. */
        public String lifecycleKey() {
            return ownerId + "/" + lifetime + "/" + resetEpoch;
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
