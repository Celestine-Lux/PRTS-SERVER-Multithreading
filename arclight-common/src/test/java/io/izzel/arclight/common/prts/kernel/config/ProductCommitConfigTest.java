/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.config;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The declared versioned face: off by default, and a declaration that is switched on without the
 * room or the schema it needs is not executable and names the piece it is missing. */
class ProductCommitConfigTest {

    private static final Path CONFIG_DIR = PrtsConfigManager.directory();

    @AfterEach
    void restoreTheDeclaredDefaults() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        deleteTree(CONFIG_DIR);
        PrtsConfigManager.ensureAndLoad();
        deleteTree(CONFIG_DIR);
    }

    private static void declare(String... replacements) throws IOException {
        assumeTrue(!Files.exists(CONFIG_DIR),
            "a prts-config directory already exists in the test working directory");
        Files.createDirectories(CONFIG_DIR);
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        String content = PrtsConfigManager.defaults(entry);
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            content = content.replace(replacements[index], replacements[index + 1]);
        }
        Files.writeString(CONFIG_DIR.resolve("kernel.yml"), content, StandardCharsets.UTF_8);
        PrtsConfigManager.ensureAndLoad();
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
    void theFaceIsOffAndTheStoreRoomIsZeroByDefault() {
        KernelSettings.ProductCommit declared = KernelSettings.productCommit();

        assertFalse(declared.enabled());
        assertFalse(declared.executable());
        assertEquals(KernelSettings.SCHEMA_UNDECLARED, declared.schemaVersion());
        assertEquals(0, declared.effectCapacityBytes());
        assertEquals(0, declared.effectCapacityRecords());
        assertEquals("", declared.refusal());
    }

    @Test
    void theTypedSchemaOfTheLogAndTheSupportedOneAreTheSameNumber() {
        assertEquals(CommitLog.SCHEMA_TYPED, KernelSettings.SUPPORTED_SCHEMA_VERSION);
        assertEquals(-1, KernelSettings.SCHEMA_UNDECLARED);
        assertTrue(CommitLog.supportedSchema(CommitLog.SCHEMA_LEGACY));
        assertTrue(CommitLog.supportedSchema(CommitLog.SCHEMA_TYPED));
        assertFalse(CommitLog.supportedSchema(CommitLog.SCHEMA_UNDECLARED));
        assertFalse(CommitLog.supportedSchema(7));
    }

    @Test
    void aFaceSwitchedOnWithNoDeclaredSchemaIsNotExecutable() throws IOException {
        declare("  product-commit: false", "  product-commit: true");

        KernelSettings.ProductCommit declared = KernelSettings.productCommit();

        assertTrue(declared.enabled());
        assertFalse(declared.executable());
        assertEquals(KernelSettings.REFUSAL_SCHEMA, declared.refusal());
    }

    @Test
    void aFaceSwitchedOnWithNoByteRoomNamesTheBytesItIsMissing() throws IOException {
        declare("  product-commit: false", "  product-commit: true",
            "  product-schema-version: -1", "  product-schema-version: 1");

        KernelSettings.ProductCommit declared = KernelSettings.productCommit();

        assertTrue(declared.enabled());
        assertFalse(declared.executable());
        assertEquals(KernelSettings.REFUSAL_EFFECT_BYTES, declared.refusal());
        assertEquals(0, declared.effectCapacityBytes());
    }

    @Test
    void aFaceSwitchedOnWithNoRecordRoomNamesTheRecordsItIsMissing() throws IOException {
        declare("  product-commit: false", "  product-commit: true",
            "  product-schema-version: -1", "  product-schema-version: 1",
            "  effect-capacity-bytes: 0", "  effect-capacity-bytes: 4096");

        KernelSettings.ProductCommit declared = KernelSettings.productCommit();

        assertTrue(declared.enabled());
        assertFalse(declared.executable(), "the store would answer every reservation with a refusal");
        assertEquals(KernelSettings.REFUSAL_EFFECT_RECORDS, declared.refusal());
        assertEquals(4096, declared.effectCapacityBytes());
        assertEquals(0, declared.effectCapacityRecords());
    }

    @Test
    void aSchemaThisBuildDoesNotKnowIsRefusedAndNotObeyed() throws IOException {
        declare("  product-commit: false", "  product-commit: true",
            "  product-schema-version: -1", "  product-schema-version: 7",
            "  effect-capacity-bytes: 0", "  effect-capacity-bytes: 4096",
            "  effect-capacity-records: 0", "  effect-capacity-records: 16");

        KernelSettings.ProductCommit declared = KernelSettings.productCommit();

        assertEquals(7, declared.schemaVersion(), "the declaration is read as it stands");
        assertFalse(declared.executable(), "and it is refused instead of being obeyed");
        assertEquals(KernelSettings.REFUSAL_SCHEMA, declared.refusal());
    }

    @Test
    void aFaceWithItsSchemaAndItsRoomIsExecutable() throws IOException {
        declare("  product-commit: false", "  product-commit: true",
            "  product-schema-version: -1", "  product-schema-version: 1",
            "  effect-capacity-bytes: 0", "  effect-capacity-bytes: 4096",
            "  effect-capacity-records: 0", "  effect-capacity-records: 16");

        KernelSettings.ProductCommit declared = KernelSettings.productCommit();

        assertTrue(declared.enabled());
        assertTrue(declared.executable());
        assertEquals(1, declared.schemaVersion());
        assertEquals(4096, declared.effectCapacityBytes());
        assertEquals(16, declared.effectCapacityRecords());
        assertEquals("", declared.refusal());
    }

    @Test
    void aRangeThatWouldHideADeclarationIsNotDeclared() {
        assertThrows(IllegalArgumentException.class, () -> new PrtsConfigManager.IntSetting(7, 0, 1));
    }
}
