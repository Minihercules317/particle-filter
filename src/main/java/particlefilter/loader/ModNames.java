package particlefilter.loader;

//? if neoforge {
import net.neoforged.fml.ModList;
//?} else {
/*import net.minecraftforge.fml.ModList;
*///?}

/** Display names of mods by namespace, for describing rules and grouping scan results. */
public final class ModNames {

    private ModNames() {}

    /** The name the mod gives itself ("GeOre" for "geore"), or the namespace if no loaded mod has it. */
    public static String of(String namespace) {
        if (namespace.equals("minecraft")) return "Minecraft";
        return ModList.get().getModContainerById(namespace).map(mod -> mod.getModInfo().getDisplayName()).orElse(namespace);
    }
}
