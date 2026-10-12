/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.CommitReservation;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A controlled host drive: the module entries are called in the order the platform calls them, from
 * the thread this fixture drives the tick on, with reset requests arriving from a thread that is not
 * that one. It is a reproduction of the sequence and not a server: the thread here is not the answer
 * of the host's own running thread, no event bus delivers anything, and neither the real stopping
 * path nor the world unload path is exercised. What it shows is the semantics of this sequence, and
 * a case that only passes here has not been shown to pass on a server.
 */
final class HostDriveFixture {

    static final String WORLD = "world-a";
    static final String SECOND_WORLD = "world-b";
    static final String DOMAIN = "entity";
    static final String NODE = "a/0";
    static final long EFFECT = 9L;

    /** A source that names this fixture. A fixture is not one of the sessions a real request comes
     * from, and this name is what keeps the two apart in a reading. */
    static final String FIXTURE_SOURCE = "fixture-queue";

    final KernelModule module = KernelModule.instance();

    /** ServerTickEvent.Pre: the host answers about the thread it runs the server on. */
    void preTick() {
        module.noteTickThread(Thread.currentThread());
    }

    /** ServerTickEvent.Post: the whole tick, and then the safe point that answers the queue. */
    void postTick(List<String> worlds) {
        module.serverTick(worlds);
        module.markSafePoint();
    }

    /** One whole tick of the host sequence. */
    void tick(List<String> worlds) {
        preTick();
        postTick(worlds);
    }

    /** The stopping path the platform walks: every world through the four step set, then the close. */
    void stop(List<String> worlds) {
        for (String world : worlds) {
            module.flushWorldAtStop(world);
        }
        module.closeCommitChain();
    }

    /** One request from a thread that is not the one the tick is driven on, with the identity of the
     * thread that made it, so a reading can be compared against it. */
    static Asked requestFromAnotherThread(KernelModule module, String source) {
        AtomicReference<KernelModule.ResetRequest> answer = new AtomicReference<>();
        AtomicReference<Long> id = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            id.set(Thread.currentThread().threadId());
            answer.set(module.requestReadingsReset(source));
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        return new Asked(answer.get(), id.get());
    }

    /** What one request from another thread was, and which thread made it. */
    record Asked(KernelModule.ResetRequest request, long threadId) {
    }

    static String reading(String key) {
        for (String row : KernelReadings.export(KernelModule.instance())) {
            if (row.startsWith(key + "=")) {
                return row.substring(key.length() + 1);
            }
        }
        return "<missing>";
    }

    static long counter(String key) {
        return Long.parseLong(reading(key));
    }

    /** The chain a caller builds before it asks the module to adopt it. */
    static final class Chain {

        final CommitLog log;
        final CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        final CommitReservation reservations;
        final CommitController controller;

        Chain(KernelModule module, CommitController.Publisher publisher) {
            EffectStore store = new EffectStore(4096, 8);
            log = new CommitLog(() -> 8, module.commitOrderSource(), store);
            reservations = new CommitReservation(log, store,
                (worldId, domainId, nodeKey) -> new CommitLog.Resolved(7L, 0, false), "owner-a", epochs);
            controller = new CommitController(reservations, publisher);
            KernelModule.CommitBinding binding = module.installCommitChain(log, controller);
            if (!binding.bound()) {
                throw new AssertionError("the fixture chain was not adopted: " + binding.refusal()
                    + " / owner bound " + module.commitOwnerThreadBound());
            }
        }

        CommitTicket.ReserveRequest request(long instance) {
            CommitNamespaces.Namespace producer = new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L);
            CommitNamespaces.Namespace namespace = reservations.controller();
            CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instance, 5L, 3L,
                "published", 7L, EFFECT, producer, namespace);
            EffectStore.Effect variant =
                new EffectStore.Effect(EFFECT, "publish", "PUBLISHED", List.of("a=1", "b=2"));
            return new CommitTicket.ReserveRequest(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY, 7L, 0,
                7L, 4242L, 2, identity, namespace, variant, variant, variant);
        }
    }
}
