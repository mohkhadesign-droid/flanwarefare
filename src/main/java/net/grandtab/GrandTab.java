package net.grandtab;

import net.grandtab.config.ConfigStore;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Grand Tab List: a server-side custom Tab screen for Forge 1.16.5.
 *
 * <p>No blocks, items, entities or network channels are registered, and everything shown uses
 * vanilla packets, so players join with an unmodified client. The display test tells Forge the
 * mod is server-only.
 */
@Mod(GrandTab.MOD_ID)
public final class GrandTab {
    public static final String MOD_ID = "grandtab";
    public static final Logger LOG = LogManager.getLogger("GrandTab");

    private static volatile TabService service;
    private static final ConfigStore STORE = new ConfigStore(FMLPaths.CONFIGDIR.get().resolve(MOD_ID));

    public GrandTab() {
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
                () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
        MinecraftForge.EVENT_BUS.register(new TabEvents());
    }

    public static ConfigStore store() {
        return STORE;
    }

    /** The running service, or null while no server is running. */
    public static TabService service() {
        return service;
    }

    static void setService(TabService s) {
        service = s;
    }

    public static String version() {
        return ModList.get().getModContainerById(MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
    }
}
