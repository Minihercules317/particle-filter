package pfbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.neoforged.fml.ModList;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Everything the shared harness code needs that differs between loaders or Minecraft versions: NeoForge 1.21.1.
 * The Forge 1.20.1 build (benchmarks/harness-forge) has its own Platform with the same methods.
 */
public final class Platform {
    /** Loader id, also the key of its version in harness.json "versions". */
    public static final String LOADER = "neoforge";

    private Platform() {
    }

    public static boolean isLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static String modVersion(String modId) {
        return ModList.get().getModContainerById(modId).map(c -> c.getModInfo().getVersion().toString()).orElse(null);
    }

    /** "id version" of every loaded mod. */
    public static JsonArray mods() {
        JsonArray mods = new JsonArray();
        ModList.get().getMods().forEach(m -> mods.add(m.getModId() + " " + m.getVersion()));
        return mods;
    }

    /** Every mod file the loader loaded (including nested jar-in-jar files and the game itself), with its mods. */
    public static JsonArray loadedModFiles() {
        JsonArray files = new JsonArray();
        ModList.get().getModFiles().forEach(info -> {
            JsonObject f = new JsonObject();
            Path file = info.getFile().getFilePath();
            f.addProperty("file", file.getFileName() == null ? file.toString() : file.getFileName().toString());
            f.addProperty("nested", file.getFileSystem() != FileSystems.getDefault());
            JsonArray ids = new JsonArray();
            info.getMods().forEach(m -> ids.add(m.getModId() + " " + m.getVersion()));
            f.add("mods", ids);
            files.add(f);
        });
        return files;
    }

    /** The integrated server's average tick time in nanoseconds. */
    public static long serverAverageTickNs(IntegratedServer server) {
        return server.getAverageTickTimeNanos();
    }

    /** Creates and opens a new world (the template), as the Create World screen would. */
    public static void createFreshLevel(Minecraft mc, String name, LevelSettings settings, WorldOptions options,
                                        Function<RegistryAccess, WorldDimensions> dimensions) {
        mc.createWorldOpenFlows().createFreshLevel(name, settings, options, dimensions, new TitleScreen());
    }
}
