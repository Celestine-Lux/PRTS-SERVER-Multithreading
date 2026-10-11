/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.config;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Declares the versioned face executable for the length of one test and puts the declared defaults
 * back afterwards. The declaration goes into the file an operator owns and is removed again, so no
 * built-in default of this build is touched: an install outside a test still reads the declared
 * default, which declares no face at all. */
public final class ExecutableProductFace {

    private ExecutableProductFace() {
    }

    /** Declares the face, its schema and its room, and loads them. */
    public static void declare() throws IOException {
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        String content = PrtsConfigManager.defaults(entry)
            .replace("  product-commit: false", "  product-commit: true")
            .replace("  product-schema-version: -1", "  product-schema-version: 1")
            .replace("  effect-capacity-bytes: 0", "  effect-capacity-bytes: 4096")
            .replace("  effect-capacity-records: 0", "  effect-capacity-records: 16");
        Path directory = PrtsConfigManager.directory();
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("kernel.yml"), content, StandardCharsets.UTF_8);
        PrtsConfigManager.ensureAndLoad();
    }

    /** Declares the face switched on with no room to hand out: it is declared and not executable,
     * which is a different refusal from a face nobody declared. */
    public static void declareWithoutRoom() throws IOException {
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        String content = PrtsConfigManager.defaults(entry)
            .replace("  product-commit: false", "  product-commit: true")
            .replace("  product-schema-version: -1", "  product-schema-version: 1");
        Path directory = PrtsConfigManager.directory();
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("kernel.yml"), content, StandardCharsets.UTF_8);
        PrtsConfigManager.ensureAndLoad();
    }

    /** Removes the declaration and reloads the built-in defaults. */
    public static void restore() throws IOException {
        deleteTree(PrtsConfigManager.directory());
        PrtsConfigManager.ensureAndLoad();
        deleteTree(PrtsConfigManager.directory());
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
}
