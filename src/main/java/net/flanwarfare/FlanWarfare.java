package net.flanwarfare;

import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarEvents;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Flan Warfare &amp; Conquest: a server-side add-on for Flan 1.16.5-1.7.2.
 *
 * <p>No blocks, items, entities or network channels are registered, so vanilla clients (and
 * clients without this mod) can join. The display test below tells Forge the mod is
 * server-only and must not be required on clients.
 */
@Mod(FlanWarfare.MOD_ID)
public final class FlanWarfare {
    public static final String MOD_ID = "flanwarfare";
    public static final Logger LOG = LogManager.getLogger("FlanWarfare");

    /** Version lines shown at startup and by /war admin version. */
    public static java.util.List<String> versionLines() {
        String full = version();
        int dash = full.lastIndexOf("-b");
        String base = dash > 0 ? full.substring(0, dash) : full;
        String build = dash > 0 ? full.substring(dash + 2) : "local (not a GitHub Actions build)";
        String flan = ModList.get().getModContainerById("flan")
                .map(c -> c.getModInfo().getVersion().toString()).orElse("not installed");
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("Flan Warfare & Conquest");
        lines.add("Version: " + base);
        lines.add("Build: " + build);
        lines.add("Minecraft: " + net.minecraftforge.versions.mcp.MCPVersion.getMCVersion()
                + "  Forge: " + net.minecraftforge.versions.forge.ForgeVersion.getVersion());
        lines.add("Flan: " + flan);
        return lines;
    }

    /** Installed version including the build number, e.g. 1.1.0-b6. */
    public static String version() {
        return ModList.get().getModContainerById(MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
    }

    public FlanWarfare() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, WarConfig.SPEC);
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
                () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
        FMLJavaModLoadingContext.get().getModEventBus().addListener(
                (ModConfig.Loading event) -> WarConfig.onConfigEvent(event));
        FMLJavaModLoadingContext.get().getModEventBus().addListener(
                (ModConfig.Reloading event) -> WarConfig.onConfigEvent(event));
        net.flanwarfare.flag.FlagDesigner.registerChannel();
        MinecraftForge.EVENT_BUS.register(new WarEvents());
    }
}
