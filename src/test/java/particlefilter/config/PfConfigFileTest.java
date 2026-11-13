package particlefilter.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Loading config/particlefilter.json from disk. Each test gets its own empty folder. */
class PfConfigFileTest {

    @TempDir
    Path dir;

    private List<Path> files() throws IOException {
        return files(dir);
    }

    /** The entries of [folder], sorted; closes the directory stream. */
    private static List<Path> files(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.sorted().toList();
        }
    }

    @Test
    void missingFileIsCreatedWithDefaults() throws IOException {
        Path file = dir.resolve("particlefilter.json");

        PfConfig.load(file);

        assertEquals(List.of(file), files());
        assertEquals(List.of(), PfConfig.hiddenParticles());
    }

    @Test
    void goodFileIsLoadedWithoutABackup() throws IOException {
        Path file = dir.resolve("particlefilter.json");
        Files.writeString(file, "{\"hiddenParticles\": [\"minecraft:smoke\"]}");

        PfConfig.load(file);

        assertEquals(List.of("minecraft:smoke"), PfConfig.hiddenParticles());
        assertEquals(List.of(file), files());
    }

    @Test
    void saveLeavesAnUnrelatedTmpFileAloneAndCleansUpItsOwn() throws IOException {
        Path file = dir.resolve("particlefilter.json");
        Files.writeString(file, "{\"hiddenParticles\": [\"minecraft:smoke\"]}");
        Path unrelated = dir.resolve("particlefilter.json.tmp");
        Files.writeString(unrelated, "not ours");

        PfConfig.load(file); // saves the normalized file

        assertEquals("not ours", Files.readString(unrelated));
        assertEquals(List.of(file, unrelated), files(), "the generated temporary file should be gone");
        assertTrue(Files.readString(file).contains("minecraft:smoke"));
    }

    @Test
    void brokenFileIsKeptBeforeDefaultsAreSaved() throws IOException {
        // A typo from editing the file by hand: the missing ] makes it unreadable.
        Path file = dir.resolve("particlefilter.json");
        String broken = "{\"hiddenParticles\": [\"minecraft:smoke\", \"minecraft:flame\"}";
        Files.writeString(file, broken);

        PfConfig.load(file);

        assertEquals(List.of(), PfConfig.hiddenParticles());
        List<Path> backups = files().stream().filter(p -> !p.equals(file)).toList();
        assertEquals(1, backups.size(), "expected one copy of the broken file next to it, found " + files());
        assertEquals(broken, Files.readString(backups.get(0)));
    }

    // ---- symlinks ----

    private static void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links not available: " + e);
        }
    }

    @Test
    void saveWritesThroughASymlinkChainAndLeavesTheLinksAlone() throws IOException {
        Path realDir = Files.createDirectory(dir.resolve("real"));
        Path linkDir = Files.createDirectory(dir.resolve("links"));
        Path real = realDir.resolve("settings.json");
        Files.writeString(real, "{\"hiddenParticles\": [\"minecraft:smoke\"]}");
        Path inner = linkDir.resolve("inner.json");
        Path outer = linkDir.resolve("particlefilter.json");
        link(inner, real);
        link(outer, inner.getFileName()); // relative link to another link

        PfConfig.load(outer);
        PfConfig.setScanSeconds(11);

        assertTrue(Files.isSymbolicLink(outer));
        assertTrue(Files.isSymbolicLink(inner));
        assertEquals(inner.getFileName(), Files.readSymbolicLink(outer));
        assertEquals(real, Files.readSymbolicLink(inner));
        String saved = Files.readString(real);
        assertTrue(saved.contains("minecraft:smoke") && saved.contains("11"), saved);
        assertEquals(List.of(real), files(realDir), "no temporary file should be left beside the target");
        assertEquals(List.of(inner, outer), files(linkDir));
    }

    @Test
    void danglingSymlinkIsNotFollowedOrReplaced() throws IOException {
        Path missing = dir.resolve("missing.json");
        Path file = dir.resolve("particlefilter.json");
        link(file, missing);

        PfConfig.load(file);

        assertTrue(Files.isSymbolicLink(file));
        assertEquals(missing, Files.readSymbolicLink(file));
        assertTrue(Files.notExists(missing), "the link's target should not be created");
        assertEquals(List.of(file), files());
    }

    // ---- files that can't be written or recovered (POSIX permissions; skipped when running as root) ----

    private static void setPermissions(Path path, String perms) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "POSIX permissions not available");
        }
    }

    @Test
    void readOnlyFileIsNotReplacedEvenInAWritableFolder() throws IOException {
        Path file = dir.resolve("particlefilter.json");
        String original = "{\"hiddenParticles\": [\"minecraft:smoke\"]}";
        Files.writeString(file, original);
        setPermissions(file, "r--r--r--");
        assumeTrue(!Files.isWritable(file), "files are writable regardless of permissions (root?)");

        PfConfig.load(file);

        assertEquals(List.of("minecraft:smoke"), PfConfig.hiddenParticles());
        assertEquals(original, Files.readString(file));
        assertEquals(List.of(file), files());
    }

    @Test
    void saveKeepsTheExistingFilesPermissions() throws IOException {
        Path file = dir.resolve("particlefilter.json");
        Files.writeString(file, "{\"hiddenParticles\": [\"minecraft:smoke\"]}");
        setPermissions(file, "rw-rw-r--");

        PfConfig.load(file);
        PfConfig.setScanSeconds(14);

        assertEquals(PosixFilePermissions.fromString("rw-rw-r--"), Files.getPosixFilePermissions(file));
        assertTrue(Files.readString(file).contains("14"));
        assertEquals(List.of(file), files());
    }

    @Test
    void emptyAclIsRefusedRatherThanAppliedOrReplacedWithDefaults() {
        Path file = dir.resolve("particlefilter.json");
        IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> PfConfig.applicableAcl(file, List.of()));
        assertTrue(e.getMessage().contains("empty"));
    }

    @Test
    void saveOnAVolumeWithoutAclSupportStillKeepsPermissions() throws IOException {
        // Regression guard for the FileStore check: on a POSIX volume the ACL path must be skipped, not fail the save.
        Path file = dir.resolve("particlefilter.json");
        Files.writeString(file, "{}");
        assumeTrue(!Files.getFileStore(file).supportsFileAttributeView(java.nio.file.attribute.AclFileAttributeView.class),
                "volume has ACL support");
        setPermissions(file, "rw-r-----");

        PfConfig.load(file);
        PfConfig.setScanSeconds(15);

        assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(file));
        assertTrue(Files.readString(file).contains("15"));
    }

    @Test
    void newFileIsStillCreatedPrivately() throws IOException {
        Path file = dir.resolve("particlefilter.json");
        assumeTrue(Files.getFileAttributeView(dir, java.nio.file.attribute.PosixFileAttributeView.class) != null,
                "POSIX permissions not available");

        PfConfig.load(file);

        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }

    @Test
    void unreadableAndUnbackedUpFileIsNeverOverwrittenUntilTheNextLoad() throws IOException {
        Path file = dir.resolve("particlefilter.json");
        String broken = "{\"hiddenParticles\": [\"minecraft:smoke\"";
        Files.writeString(file, broken);
        // The file can be read but nothing can be created next to it, so the backup fails.
        setPermissions(dir, "r-xr-xr-x");
        try {
            assumeTrue(!Files.isWritable(dir), "folders are writable regardless of permissions (root?)");

            PfConfig.load(file);
            assertEquals(List.of(), PfConfig.hiddenParticles(), "defaults are used in memory");

            setPermissions(dir, "rwxr-xr-x"); // writing would work now, but the loaded config must not be saved
            PfConfig.setScanSeconds(12);
            PfConfig.setHiddenEverywhere("minecraft:flame", true);
            assertEquals(12, PfConfig.scanSeconds());
            assertEquals(broken, Files.readString(file));
            assertEquals(List.of(file), files());

            // Loading again once the file is fixed saves normally.
            Files.writeString(file, "{}");
            PfConfig.load(file);
            PfConfig.setScanSeconds(13);
            assertTrue(Files.readString(file).contains("13"));
        } finally {
            setPermissions(dir, "rwxr-xr-x");
        }
    }
}
