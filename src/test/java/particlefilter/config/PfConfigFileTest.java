package particlefilter.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loading config/particlefilter.json from disk. Each test gets its own empty folder. */
class PfConfigFileTest {

    @TempDir
    Path dir;

    private List<Path> files() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
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
}
