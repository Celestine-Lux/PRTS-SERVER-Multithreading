/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.prts.kernel;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.observe.CloseSealWriter;
import io.izzel.arclight.common.prts.kernel.wiring.KernelWiring;
import io.izzel.arclight.common.prts.support.PrtsLoadProbe;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the kernel scaffolding from the platform tick event; listener and write/wait taps exist only
 * while the category is enabled, and a reload can add or remove them without restarting the process.
 */
public final class PrtsKernelEvents {

    private static final PrtsKernelEvents INSTANCE = new PrtsKernelEvents();
    private static boolean reloadHookInstalled;
    private static boolean subscribed;

    private PrtsKernelEvents() {
    }

    /** Binds the kernel domains, installs the configuration reload hooks and synchronizes the
     * platform subscription once. */
    public static synchronized void register() {
        KernelWiring.install();
        PrtsEntityOwnershipEvents.registerIfDeclared();
        if (!reloadHookInstalled) {
            PrtsConfigManager.addReloadListener(PrtsKernelEvents::syncSubscription);
            PrtsConfigManager.addReloadListener(PrtsKernelEvents::requestReloadReset);
            reloadHookInstalled = true;
        }
        syncSubscription();
    }

    private static synchronized void syncSubscription() {
        boolean wanted = KernelSettings.enabled();
        if (wanted == subscribed) {
            return;
        }
        KernelModule module = KernelModule.instance();
        if (wanted) {
            module.installWritePathTap();
            module.installWaitSiteTap();
            module.installLoadProbe();
            NeoForge.EVENT_BUS.register(INSTANCE);
            subscribed = true;
            return;
        }
        module.removeWritePathTap();
        module.removeWaitSiteTap();
        module.removeLoadProbe();
        NeoForge.EVENT_BUS.unregister(INSTANCE);
        subscribed = false;
    }

    /** Asks the kernel for a whole reset of its readings, named after this session. */
    private static void requestReloadReset() {
        if (!KernelSettings.enabled()) {
            return;
        }
        KernelModule.instance().requestReadingsReset(KernelModule.HOST_RELOAD_RESET_SOURCE);
    }

    /** Opens the tick window the load sampler classifies its waits against. */
    @SubscribeEvent
    public void onServerTickPre(ServerTickEvent.Pre event) {
        PrtsLoadProbe.tickEntered();
        KernelModule module = KernelModule.instance();
        module.noteTickSource(event.getServer());
        module.noteTickThread(event.getServer().getRunningThread());
    }

    /** Advances the kernel by one tick and then answers the requests queued for the owner while it ran;
     * the safe point stands after the whole tick, so it never clears a reading this tick judged. The
     * one chain of the process is adopted at that same boundary, after the safe point of the tick that
     * adopted it has run: the owner is bound inside the tick and a chain is taken at most once. */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        PrtsLoadProbe.tickLeft();
        List<String> worlds = new ArrayList<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            worlds.add(level.dimension().location().toString());
        }
        KernelModule module = KernelModule.instance();
        module.serverTick(worlds);
        module.markSafePoint();
        module.adoptCommitChainOnce();
    }

    /** The server is stopping: the entry snapshot is written before any world is touched, every world
     * it still carries is then flushed through the four step set, the chain is closed, and the terminal
     * snapshot is written whether those two succeeded or failed. Which of the flush and the close came
     * first is recorded by the kernel, not assumed here, and the unload event is not delivered on this
     * path, so the worlds are walked here or nowhere. */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        KernelModule module = KernelModule.instance();
        List<String> worlds = new ArrayList<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            worlds.add(level.dimension().location().toString());
        }
        CloseSealWriter seal = CloseSealWriter.forRun();
        seal.entry(worlds, module);
        try {
            seal.flushing();
            for (String world : worlds) {
                module.flushWorldAtStop(world);
            }
            seal.closed(module.closeCommitChain());
        } catch (RuntimeException error) {
            seal.errored(error);
            throw error;
        } finally {
            seal.terminal(worlds, module);
        }
    }
}
