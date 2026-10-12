/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** The file side of the close seal: which directory a record may land in, whether an atomic replace
 * can be promised there, and what a failure is called. No branch of it names an instance directory,
 * so no configuration of the diagnostic property can make it write inside one. */
final class CloseSealFiles {

    static final String FILE_NAME = "close-seal.tsv";
    static final String RECEIPT_NAME = "close-seal.md5";
    static final String FALLBACK_ROOT = "/tmp/prts-seal";

    private CloseSealFiles() {
    }

    /** The normalised directory the property names, or the reason it names none. */
    record Resolved(Path dir, String reason) {
    }

    static Resolved diagDir(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new Resolved(null, "diag-dir-property-missing");
        }
        Path path;
        try {
            path = Paths.get(value.trim());
        } catch (InvalidPathException error) {
            return new Resolved(null, "diag-dir-not-absolute");
        }
        if (!path.isAbsolute()) {
            return new Resolved(null, "diag-dir-not-absolute");
        }
        return new Resolved(path.normalize(), "");
    }

    /** Creates the run directory and refuses one whose real path leaves the diagnostic root, which
     * is what a symbolic link planted at the run directory would do. The directory is claimed with
     * the one call that fails when it is already there, and a directory that already holds a record
     * is refused rather than adopted, so a run id that comes round a second time cannot replace the
     * seal of the run that used it first. */
    static String prepare(Path diagDir, Path runDir) {
        try {
            Files.createDirectories(diagDir);
        } catch (IOException error) {
            return reason(error);
        }
        boolean claimed;
        try {
            Files.createDirectory(runDir);
            claimed = true;
        } catch (FileAlreadyExistsException exists) {
            claimed = false;
        } catch (IOException error) {
            return reason(error);
        }
        if (!Files.isDirectory(runDir)) {
            return "path-not-a-directory";
        }
        try {
            Path root = diagDir.toRealPath();
            Path real = runDir.toRealPath();
            if (!real.startsWith(root)) {
                return "symlink-escape";
            }
        } catch (IOException error) {
            return reason(error);
        }
        if (claimed || (!Files.isRegularFile(runDir.resolve(FILE_NAME))
            && !Files.isRegularFile(runDir.resolve(RECEIPT_NAME)))) {
            return "";
        }
        return "run-dir-exists";
    }

    /** Whether a temporary file in one directory can be renamed onto the other in one step; a false
     * answer refuses the atomic promise instead of half keeping it. */
    static boolean sameFileStore(Path left, Path right) {
        try {
            FileStore a = Files.getFileStore(left);
            FileStore b = Files.getFileStore(right);
            if (a.equals(b)) {
                return true;
            }
            return a.name().equals(b.name()) && a.type().equals(b.type());
        } catch (IOException error) {
            return false;
        }
    }

    /** The closed set of failure names this writer publishes, read from the throwable. */
    static String reason(IOException error) {
        if (error instanceof AtomicMoveNotSupportedException) {
            return "atomic-move-unsupported";
        }
        if (error instanceof FileAlreadyExistsException) {
            return "path-not-a-directory";
        }
        if (error instanceof AccessDeniedException) {
            return "path-not-writable";
        }
        String text = error.getClass().getName() + ' ' + String.valueOf(error.getMessage());
        if (text.contains("No space left on device") || text.contains("ENOSPC")) {
            return "disk-full";
        }
        if (text.contains("Not a directory")) {
            return "path-not-a-directory";
        }
        if (text.contains("Permission denied") || text.contains("Read-only file system")) {
            return "path-not-writable";
        }
        return "io-error";
    }

    /** Writes the whole file through a temporary neighbour and one rename, and only where the two
     * directories sit on the same file system. */
    static String create(Path tmpDir, Path target, String text) {
        Path staged = null;
        try {
            Files.createDirectories(tmpDir);
            Files.createDirectories(target.getParent());
            staged = Files.createTempFile(tmpDir, ".close-seal-", ".new");
            write(staged, text, false);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            staged = null;
            return "";
        } catch (IOException error) {
            return reason(error);
        } finally {
            deleteQuietly(staged);
        }
    }

    /** Adds one record to a file that is already in place, and starts it when it is not: every later
     * stage appends, so nothing an earlier stage wrote can be replaced by a later one, and starting a
     * missing file never truncates a file that is already there. */
    static String append(Path target, String text) {
        try {
            write(target, text, true);
            return "";
        } catch (IOException error) {
            return reason(error);
        }
    }

    private static void write(Path file, String text, boolean append) throws IOException {
        StandardOpenOption[] options = append
            ? new StandardOpenOption[] {StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                StandardOpenOption.CREATE}
            : new StandardOpenOption[] {StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING};
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, options)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
    }

    /** The whole file as one read. A digest and a length taken from this read describe the same
     * bytes, which is what lets one record say which object its two numbers are about; a read that
     * fails is returned as its own name instead of a number that would look like a reading. */
    record Whole(byte[] bytes, String problem) {

        static Whole of(Path file) {
            try {
                return new Whole(Files.readAllBytes(file), "");
            } catch (IOException error) {
                return new Whole(new byte[0], reason(error));
            }
        }

        boolean read() {
            return problem.isEmpty();
        }
    }

    static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            return;
        }
    }
}
