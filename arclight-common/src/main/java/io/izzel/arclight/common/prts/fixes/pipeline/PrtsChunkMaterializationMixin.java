/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.pipeline;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsChunkMaterialization;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Reports one completed chunk materialization at the full step's own completion, the one place both
 * a generated chunk and a chunk read back from storage pass through. The completion's callback is
 * also asked which thread ran it: the action stays non-async, so the future's result, its handle,
 * its executor and the propagation of a failure are untouched, and the report is an observation that
 * may not throw, block, load a world or run work elsewhere. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ChunkStatusTasks.class)
public abstract class PrtsChunkMaterializationMixin {

    @Inject(method = "full", at = @At("RETURN"))
    private static void prts$countMaterializedChunk(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PrtsChunkMaterialization.installed()) {
            return;
        }
        String worldId = context.level().dimension().location().toString();
        long chunkPos = chunk.getPos().toLong();
        CompletableFuture<ChunkAccess> future = cir.getReturnValue();
        // A non-async completion runs on the thread that completes the future, or on this thread
        // when the future was already complete here: two different facts, reported apart.
        boolean registeredLate = future.isDone();
        future.whenComplete((materialized, failure) -> prts$reportCallback(context, cache, chunk,
            worldId, chunkPos, registeredLate, materialized, failure));
    }

    /** Reports one completion and the role of the thread it ran on: the identity is read from the
     * running server itself, and a report that cannot be made is counted where it happened, so a
     * broken observation neither hides the completion nor reaches the dependent stage. */
    private static void prts$reportCallback(WorldGenContext context,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, String worldId,
            long chunkPos, boolean registeredLate, ChunkAccess materialized, Throwable failure) {
        Thread thread = Thread.currentThread();
        boolean serverOwned = false;
        boolean identityUnknown = false;
        try {
            MinecraftServer server = context.level().getServer();
            serverOwned = server != null && server.getRunningThread() == thread;
        } catch (Throwable thrown) {
            identityUnknown = true;
        }
        try {
            if (failure == null && materialized != null) {
                GenerationChunkHolder holder = cache.get(chunk.getPos().x, chunk.getPos().z);
                PrtsChunkMaterialization.materialized(PrtsChunkMaterialization.nextTicket(), worldId,
                    chunkPos, PrtsChunkMaterialization.STATUS_FULL,
                    holder == null ? 0 : holder.getGenerationRefCount());
            }
        } catch (Throwable thrown) {
            PrtsChunkMaterialization.callbackReportFailed();
        }
        PrtsChunkMaterialization.callbackThread(worldId, thread.threadId(), serverOwned,
            registeredLate, failure != null, identityUnknown);
    }
}
