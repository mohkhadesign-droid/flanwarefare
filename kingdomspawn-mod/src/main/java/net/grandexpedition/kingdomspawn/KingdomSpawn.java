package net.grandexpedition.kingdomspawn;

import net.grandexpedition.kingdomspawn.config.KsConfig;
import net.grandexpedition.kingdomspawn.data.KsData;
import net.grandexpedition.kingdomspawn.deploy.Deployer;
import net.grandexpedition.kingdomspawn.search.BiomeSearch;
import net.grandexpedition.kingdomspawn.teleport.HubTeleports;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.FolderName;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Grand Expedition Kingdom Spawn: preassigned kingdoms, distant biome deployment on first join,
 * and /spawn + /return to the central hub. Server-side only (clients without it can join).
 */
@Mod(KingdomSpawn.MOD_ID)
public final class KingdomSpawn {
    public static final String MOD_ID = "kingdomspawn";
    public static final Logger LOG = LogManager.getLogger("KingdomSpawn");

    private static KsConfig config;
    private static KsData data;
    private static MinecraftServer server;
    private static BiomeSearch search;
    private static final Deployer DEPLOYER = new Deployer();
    private static final HubTeleports TELEPORTS = new HubTeleports();

    public KingdomSpawn() {
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
                () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
        MinecraftForge.EVENT_BUS.register(new KsEvents());
    }

    public static Path configFile() {
        return FMLPaths.CONFIGDIR.get().resolve("kingdomspawn.json");
    }

    /** The configuration, loaded on first use (commands are registered before the server starts). */
    public static synchronized KsConfig config() {
        if (config == null) {
            List<String> problems = new ArrayList<>();
            try {
                config = KsConfig.load(configFile(), problems);
            } catch (IOException e) {
                LOG.error("Configuration {} could not be read ({}); using built-in defaults until it is fixed "
                        + "and /kingdomspawn reload is run. The file was NOT overwritten.", configFile(), e.getMessage());
                config = KsConfig.defaults();
                config.index();
            }
            for (String p : problems) {
                LOG.warn(p);
            }
        }
        return config;
    }

    /** Re-reads the configuration. On error the current one stays active. Returns messages. */
    public static synchronized List<String> reloadConfig() {
        List<String> messages = new ArrayList<>();
        try {
            KsConfig fresh = KsConfig.load(configFile(), messages);
            config = fresh;
            messages.add(0, "Configuration reloaded.");
        } catch (IOException e) {
            messages.add("Configuration NOT reloaded: " + e.getMessage() + ". The previous configuration stays active.");
        }
        return messages;
    }

    /** Saves the configuration (after a command changed it). Returns an error text or null. */
    public static String saveConfig() {
        try {
            config().index();
            config().save(configFile());
            return null;
        } catch (IOException e) {
            LOG.error("Could not save {}", configFile(), e);
            return "Could not save the configuration: " + e.getMessage();
        }
    }

    public static KsData data() {
        return data;
    }

    public static MinecraftServer server() {
        return server;
    }

    public static BiomeSearch search() {
        return search;
    }

    public static void setSearch(BiomeSearch s) {
        search = s;
    }

    public static Deployer deployer() {
        return DEPLOYER;
    }

    public static HubTeleports teleports() {
        return TELEPORTS;
    }

    static void start(MinecraftServer s) {
        server = s;
        Path file = s.func_240776_a_(FolderName.DOT).resolve(MOD_ID).resolve("state.json");
        try {
            data = KsData.load(file);
        } catch (IOException e) {
            LOG.error("{} is unreadable ({}). Starting with empty state; the broken file is kept as state.json.broken.",
                    file, e.getMessage());
            try {
                java.nio.file.Files.move(file, file.resolveSibling("state.json.broken"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                data = KsData.load(file);
            } catch (IOException e2) {
                throw new RuntimeException("Cannot initialise " + file, e2);
            }
        }
        for (String line : Validation.check(s, config())) {
            LOG.warn(line);
        }
        LOG.info("Kingdom Spawn ready: {} kingdoms, {} assigned players, {} approved locations, {} players deployed",
                config().kingdoms.size(), config().kingdoms.values().stream().mapToInt(k -> k.players.size()).sum(),
                data.approved.size(), data.deployed.size());
    }

    static void stop() {
        if (search != null) {
            search.cancel("server stopping");
            search = null;
        }
        if (data != null) {
            data.save();
        }
        server = null;
    }
}
