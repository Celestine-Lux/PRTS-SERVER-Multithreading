/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What one freeze reads out of the chunk demands of its own window: the columns of a candidate are
 * the parameters of the call that asked for the chunk and the freeze that read it, a chunk is judged
 * as the object it is, an operation seen to be done before the freeze is refused, and a window that
 * is complete is still not sealed - a build without an execution witness has no table to put it in.
 *
 * <p>The tap is installed here as a fixture: in a build the demand observer owns the seam, and no
 * production entry installs this reader, so nothing in this class is a reading of a running server.
 */
class DemandShadowCandidatesTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String OTHER_WORLD = "minecraft:the_nether";
    private static final String STATUS = "full";
    private static final long EPOCH = 11L;
    private static final FrozenDeclarations.FreezeKey KEY =
        new FrozenDeclarations.FreezeKey("lifecycle-a", 4L);

    private final DemandShadowCandidates candidates = new DemandShadowCandidates();

    @BeforeEach
    void installTheReader() {
        PrtsChunkDemand.install(candidates);
    }

    @AfterEach
    void removeTheReader() {
        PrtsChunkDemand.install(null);
    }

    private static DemandShadowCandidates.LiveWorldEpoch live(long epoch) {
        return worldId -> epoch;
    }

    private static DemandShadowCandidates.Freeze freeze(long planSequence) {
        return new DemandShadowCandidates.Freeze(planSequence, 2L, KEY);
    }

    private List<DemandShadowCandidates.Shadow> read(long planSequence) {
        return candidates.readFor(freeze(planSequence), live(EPOCH));
    }

    @Test
    void theParametersOfOneCallBecomeTheColumnsOfOneCandidateOfThatFreeze() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);

        List<DemandShadowCandidates.Shadow> shadows = read(5L);

        assertEquals(1, shadows.size());
        DemandShadowCandidates.Shadow shadow = shadows.get(0);
        assertEquals(WORLD, shadow.worldId(), "the world is the one the call named");
        assertEquals(DemandShadowCandidates.DOMAIN, shadow.domainId());
        assertEquals(ChunkPos.asLong(3, -7), shadow.chunk().packedPos(),
            "the chunk is packed from the coordinates of this very call");
        assertEquals(STATUS, shadow.targetStatus());
        assertTrue(shadow.requireChunk(), "a parameter of the call is carried as it was given");
        assertEquals(EPOCH, shadow.worldEpoch(), "the generation was read once at the freeze");
        assertEquals(5L, shadow.planSequence());
        assertEquals(2L, shadow.planGeneration());
        assertEquals(KEY, shadow.freeze(), "the candidate names the freeze that read it");
        assertFalse(shadow.late());
        assertTrue(shadow.declared(), "every column a declaration carries is there");
        assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, shadow.state(),
            "complete and still not to be sealed: the freeze has no execution witness");
        assertEquals("", shadow.refusal());
        assertEquals(1L, candidates.lastRead());
        assertEquals(1L, candidates.unexecutable());
        assertEquals(0L, candidates.notWired());
    }

    @Test
    void theKeyIsAPureFunctionOfTheCapture() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        PrtsChunkDemand.demandDescriptor(WORLD, -1, 0, STATUS, true);

        List<DemandShadowCandidates.Shadow> shadows = read(5L);

        assertEquals(3, shadows.size());
        assertEquals(shadows.get(0).nodeKey(), shadows.get(1).nodeKey(),
            "the same parameters give the same key, whatever the window held before them");
        assertNotEquals(shadows.get(0).nodeKey(), shadows.get(2).nodeKey());
        assertEquals(DemandShadowCandidates.DOMAIN + "/" + WORLD + "/" + ChunkPos.asLong(3, -7) + "/"
            + STATUS, shadows.get(0).nodeKey());
    }

    @Test
    void theParametersOfOneCallAreNotSharedWithTheNext() {
        PrtsChunkDemand.demandDescriptor(WORLD, 1, 2, "features", false);
        PrtsChunkDemand.demandDescriptor(OTHER_WORLD, 0, 0, STATUS, true);

        List<DemandShadowCandidates.Shadow> shadows = read(5L);

        assertEquals(2, shadows.size());
        assertEquals(WORLD, shadows.get(0).worldId());
        assertEquals(ChunkPos.asLong(1, 2), shadows.get(0).chunk().packedPos());
        assertEquals("features", shadows.get(0).targetStatus());
        assertFalse(shadows.get(0).requireChunk());
        assertEquals(OTHER_WORLD, shadows.get(1).worldId());
        assertEquals(0L, shadows.get(1).chunk().packedPos());
    }

    @Test
    void theOriginIsACandidateAndItsPositionIsNotAStatementAboutPresence() {
        PrtsChunkDemand.demandDescriptor(WORLD, 0, 0, STATUS, true);

        List<DemandShadowCandidates.Shadow> shadows = read(5L);

        DemandShadowCandidates.Shadow origin = shadows.get(0);
        assertNotNull(origin.chunk(), "the origin is a chunk the caller named");
        assertEquals(CommitLog.NO_CHUNK_POSITION, origin.chunk().packedPos(),
            "and it packs to the value a missing chunk would pack to");
        assertTrue(origin.declared(), "presence is the object, so the origin is declared");
        assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, origin.state());
    }

    @Test
    void aNegativeChunkPositionIsStillACandidate() {
        assertEquals(-4294967296L, ChunkPos.asLong(0, -1),
            "a packed position is signed: a negative z packs to a negative number");
        assertEquals(4294967295L, ChunkPos.asLong(-1, 0),
            "and a negative x packs to a positive one, so the sign says nothing about presence");
        PrtsChunkDemand.demandDescriptor(WORLD, 0, -1, STATUS, true);
        PrtsChunkDemand.demandDescriptor(WORLD, -1, 0, STATUS, true);

        List<DemandShadowCandidates.Shadow> shadows = read(5L);

        assertEquals(-4294967296L, shadows.get(0).chunk().packedPos());
        assertEquals(4294967295L, shadows.get(1).chunk().packedPos());
        assertTrue(shadows.get(0).declared(), "a negative position is a legal chunk, not a missing one");
        assertTrue(shadows.get(1).declared());
        assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, shadows.get(0).state());
        assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, shadows.get(1).state());
    }

    @Test
    void aCallThatNamedNoCoordinatesIsNoCandidate() {
        PrtsChunkDemand.futureOpened(WORLD, STATUS);

        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertNull(shadow.chunk(), "an ask that named no coordinates carries no chunk");
        assertFalse(shadow.declared());
        assertEquals(DemandShadowCandidates.NOT_WIRED, shadow.state());
        assertEquals(1L, candidates.unshaped());
    }

    @Test
    void aCallThatNamedNoTargetStateIsNoCandidate() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, "", true);

        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertNotNull(shadow.chunk(), "the chunk is there and the state is not");
        assertEquals("", shadow.targetStatus());
        assertFalse(shadow.declared());
        assertEquals(DemandShadowCandidates.NOT_WIRED, shadow.state());
        assertEquals(1L, candidates.unshaped());
    }

    @Test
    void aCallThatNamedNoWorldIsNoCandidate() {
        PrtsChunkDemand.demandDescriptor(null, 3, -7, STATUS, true);

        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertEquals("", shadow.worldId());
        assertFalse(shadow.declared());
        assertEquals(DemandShadowCandidates.NOT_WIRED, shadow.state());
        assertEquals(1L, candidates.unshaped());
    }

    @Test
    void aWorldWithoutALiveGenerationIsNoCandidateAndTheTwoWaysAreNamedApart() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        PrtsChunkDemand.demandDescriptor(OTHER_WORLD, 3, -7, STATUS, true);

        List<DemandShadowCandidates.Shadow> shadows = candidates.readFor(freeze(5L), worldId ->
            WORLD.equals(worldId) ? 0L : -1L);

        DemandShadowCandidates.Shadow untracked = shadows.get(0);
        DemandShadowCandidates.Shadow lost = shadows.get(1);
        assertEquals(CommitLog.NO_WORLD_EPOCH, untracked.worldEpoch());
        assertEquals("world-untracked", untracked.refusal());
        assertEquals(-1L, lost.worldEpoch());
        assertEquals("world-not-live", lost.refusal());
        assertNotEquals(untracked.refusal(), lost.refusal(), "the two refusals are not one name");
        assertFalse(untracked.declared());
        assertFalse(lost.declared());
        assertEquals(DemandShadowCandidates.NOT_WIRED, untracked.state());
        assertEquals(DemandShadowCandidates.NOT_WIRED, lost.state());
        assertEquals(2L, candidates.unlive());
    }

    @Test
    void aGenerationThatMovedAfterTheFreezeIsNotCurrentAndTheColumnIsNotRewritten() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertTrue(shadow.currentFor(EPOCH), "the freeze read the generation of its own world");
        assertFalse(shadow.currentFor(EPOCH + 1L), "a world rebuilt since the freeze is not current");
        assertEquals(EPOCH, shadow.worldEpoch(),
            "the frozen column still carries the freeze's read, not the later one");
        assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, shadow.state());
    }

    @Test
    void aCompletionSeenBeforeTheFreezeRefusesTheCapture() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        PrtsChunkDemand.futureCompleted(WORLD, STATUS, 1L, true);

        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertTrue(shadow.late(), "an operation that already ran was not outstanding at the freeze");
        assertEquals(DemandShadowCandidates.LATE_BEFORE_FREEZE, shadow.refusal());
        assertEquals(DemandShadowCandidates.NOT_WIRED, shadow.state());
        assertEquals(1L, candidates.lateRefusals());
        assertEquals(0L, candidates.unexecutable());
    }

    @Test
    void aCompletionOfAnotherWorldOrStateRefusesNothing() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        PrtsChunkDemand.futureCompleted(WORLD, "features", 1L, true);
        PrtsChunkDemand.futureCompleted(OTHER_WORLD, STATUS, 1L, true);

        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertFalse(shadow.late(), "a completion answers the capture that waited on it and no other");
        assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, shadow.state());
    }

    @Test
    void aCompletionAfterTheFreezeBelongsToTheNextWindow() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        assertFalse(read(5L).get(0).late());
        PrtsChunkDemand.futureCompleted(WORLD, STATUS, 1L, true);
        PrtsChunkDemand.demandDescriptor(WORLD, 4, -7, STATUS, true);

        assertFalse(read(6L).get(0).late(),
            "a capture taken after a completion did not wait on it and is not read as done");
    }

    @Test
    void aCaptureNotSeenDoneIsStillNotExecutable() {
        assertTrue(DemandShadowCandidates.lateBeforeFreeze(true), "done before the freeze refuses");
        assertFalse(DemandShadowCandidates.lateBeforeFreeze(false),
            "and the other answer settles nothing: not done is not not started");
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertTrue(List.of(DemandShadowCandidates.NOT_WIRED, DemandShadowCandidates.NOT_EXECUTABLE)
            .contains(shadow.state()), "the capture reads: " + shadow.state());
        assertNotEquals(FrozenDeclarations.SEALED, shadow.state(),
            "and never a state a table is sealed with");
        for (RecordComponent component : DemandShadowCandidates.Shadow.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase().contains("instance"),
                "a capture carries no instance number at all: " + component.getName());
        }
        assertThrows(NoSuchMethodException.class,
            () -> DemandShadowCandidates.class.getDeclaredMethod("mint", List.class),
            "there is no entry that mints a number here");
    }

    @Test
    void anAskThatIsNotAFutureIsNoCapture() {
        PrtsChunkDemand.demandStarted(WORLD, 3, -7, STATUS, true);
        PrtsChunkDemand.demandFinished(WORLD, STATUS, true, true);
        PrtsChunkDemand.futureTaken(WORLD, STATUS);

        assertTrue(read(5L).isEmpty(), "only the head of a future-returning ask is a capture");
        assertEquals(0L, candidates.captured());
    }

    @Test
    void aWindowIsReadOnceAndBelongsToTheFreezeThatReadIt() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        assertEquals(1, read(5L).size());
        assertTrue(read(6L).isEmpty(), "a window a freeze read is not handed to the next one");
        assertEquals(0, candidates.held());
        assertEquals(2L, candidates.windows());
    }

    @Test
    void aFullRingCostsTheOldestCaptureAndNeverTheCaller() {
        for (int index = 0; index < DemandShadowCandidates.CAP + 3; index++) {
            candidates.demandDescriptor(WORLD, index, 0, STATUS, true);
        }
        PrtsChunkDemand.demandDescriptor(OTHER_WORLD, 0, 0, STATUS, true);

        assertEquals(DemandShadowCandidates.CAP, candidates.held(), "the ring never grows past its cap");
        assertEquals(DemandShadowCandidates.CAP + 4L, candidates.captured());
        assertEquals(4L, candidates.dropped(), "what a full ring cost is counted, not hidden");
        assertTrue(PrtsChunkDemand.installed(), "the reader is still the installed one");
        List<DemandShadowCandidates.Shadow> shadows = read(5L);
        assertEquals(DemandShadowCandidates.CAP, shadows.size());
        assertEquals(ChunkPos.asLong(4, 0), shadows.get(0).chunk().packedPos(),
            "the captures a full ring cost are the oldest ones");
        assertEquals(ChunkPos.asLong(0, 0), shadows.get(DemandShadowCandidates.CAP - 1)
            .chunk().packedPos(), "and the last capture of the window is kept");
    }

    private static List<Class<?>> owners() throws ClassNotFoundException {
        return List.of(DemandShadowCandidates.class, DemandShadowCandidates.DemandDescriptor.class,
            DemandShadowCandidates.Shadow.class, DemandShadowCandidates.Freeze.class,
            Class.forName(DemandShadowCandidates.class.getName() + "$Slot"));
    }

    @Test
    void everyColumnOfACandidateIsAPlainValue() throws Exception {
        TreeSet<String> types = new TreeSet<>();
        for (Class<?> owner : owners()) {
            for (Field field : owner.getDeclaredFields()) {
                types.add(field.getType().getName());
            }
        }

        assertEquals(new TreeSet<>(List.of("[L" + DemandShadowCandidates.class.getName() + "$Slot;",
            "boolean", "int", "long", "java.lang.String", "java.util.List",
            FrozenDeclarations.DeclaredChunk.class.getName(),
            FrozenDeclarations.FreezeKey.class.getName(),
            DemandShadowCandidates.DemandDescriptor.class.getName(),
            "[L" + DemandShadowCandidates.class.getName() + "$Slot;")), types,
            "every column of a candidate and of the window it waits in, and nothing else: "
                + types);
        for (String name : types) {
            assertFalse(name.startsWith("net.minecraft."), name + " is a platform type");
            assertFalse(name.startsWith("org.bukkit."), name + " is a platform type");
            assertFalse(name.startsWith("java.util.concurrent."), name + " is a task type");
        }
    }

    @Test
    void noColumnIsAssignableToAWorldAFutureOrAChunk() throws Exception {
        List<Class<?>> forbidden = List.of(Level.class, ChunkAccess.class, Future.class,
            CompletableFuture.class, Thread.class, Class.class);
        for (Class<?> owner : owners()) {
            for (Field field : owner.getDeclaredFields()) {
                Class<?> type = field.getType().isArray() ? field.getType().getComponentType()
                    : field.getType();
                if (type.isPrimitive()) {
                    continue;
                }
                for (Class<?> held : forbidden) {
                    assertFalse(held.isAssignableFrom(type), owner.getSimpleName() + "."
                        + field.getName() + " is a " + held.getName());
                }
            }
        }
    }


    // ---- the freeze of a driven tick: the wiring this batch adds ----

    private static final Path CONFIG_DIR = PrtsConfigManager.directory();
    private static final String TICK_WORLD = "shadow-freeze";

    private static String reading(String key) {
        for (String row : KernelReadings.export(KernelModule.instance())) {
            if (row.startsWith(key + "=")) {
                return row.substring(key.length() + 1);
            }
        }
        return "<missing>";
    }

    /** A tick plan is off in the declared defaults, so the fixture switches the one feature on for
     * the life of the case and puts the directory back the way it found it. */
    private static void switchTheTickPlanOn() throws IOException {
        assumeTrue(!Files.exists(CONFIG_DIR),
            "a prts-config directory already exists in the test working directory");
        Files.createDirectories(CONFIG_DIR);
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        Files.writeString(CONFIG_DIR.resolve("kernel.yml"),
            PrtsConfigManager.defaults(entry).replace("  tick-plan: false", "  tick-plan: true"),
            StandardCharsets.UTF_8);
        PrtsConfigManager.ensureAndLoad();
        System.setProperty("arclight.prts.kernel", "true");
    }

    private static void restoreTheDeclaredDefaults() throws IOException {
        PrtsChunkDemand.install(null);
        KernelModule.instance().removeLoadProbe();
        System.clearProperty("arclight.prts.kernel");
        deleteTree(CONFIG_DIR);
        PrtsConfigManager.ensureAndLoad();
        deleteTree(CONFIG_DIR);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void aDrivenFreezeReadsItsOwnWindowAndSealsNoneOfIt() throws IOException {
        switchTheTickPlanOn();
        try {
            KernelModule module = KernelModule.instance();
            DemandShadowCandidates reader = module.demandShadowCandidates();
            module.serverTick(List.of(TICK_WORLD));
            // The first tick only takes the control frame the plan needs. The window of the freeze is
            // filled after it and read by the next tick, which is the order the host walks.
            reader.reset();
            PrtsChunkDemand.install(reader);
            PrtsChunkDemand.demandDescriptor(TICK_WORLD, 3, -7, STATUS, true);
            long sealed = module.declarationsSealed();

            module.serverTick(List.of(TICK_WORLD));

            assertEquals(1L, reader.windows(), "the freeze read the window of its own tick");
            List<DemandShadowCandidates.Shadow> read = reader.lastCandidates();
            assertEquals(1, read.size());
            DemandShadowCandidates.Shadow shadow = read.get(0);
            assertEquals(TICK_WORLD, shadow.worldId());
            assertEquals(ChunkPos.asLong(3, -7), shadow.chunk().packedPos());
            assertEquals(STATUS, shadow.targetStatus());
            assertTrue(shadow.worldEpoch() > 0L, "the world was tracked and its generation was read");
            assertTrue(shadow.planSequence() > 0L);
            assertTrue(shadow.freeze().generation() > 0L);
            assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, shadow.state(),
                "the capture is complete and the freeze still has no witness for it");
            assertEquals(sealed + 1L, module.declarationsSealed(),
                "the freeze sealed a table of the plan's own declarations");
            assertEquals(FrozenDeclarations.NOT_WIRED, module.declarations().state(),
                "and none of the shadow window became an entry of it");
            assertEquals(0, module.declarations().size());
            assertEquals("", module.declarationRefusal(), "nothing was refused, nothing was taken");
            assertEquals(0L, module.pipelineJobCandidates(),
                "the shadow window is not the pipeline producer's candidate list");
            assertEquals("NOT_WIRED", reading("product.commit.declaration_state"));
            assertEquals("0", reading("product.commit.declaration_entries"));
        } finally {
            restoreTheDeclaredDefaults();
        }
    }
}
