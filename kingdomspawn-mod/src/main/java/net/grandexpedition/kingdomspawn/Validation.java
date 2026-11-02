package net.grandexpedition.kingdomspawn;

import net.grandexpedition.kingdomspawn.config.KsConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Checks the configuration against the running server (biome ids, hub dimension). */
public final class Validation {
    private Validation() {
    }

    public static List<String> check(MinecraftServer server, KsConfig config) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, KsConfig.Kingdom> e : config.kingdoms.entrySet()) {
            String biome = e.getValue().biome;
            if (biome == null || biome.isEmpty()) {
                out.add(e.getKey() + " has no biome yet: /kingdomspawn setbiome " + e.getKey() + " <biome_id>");
                continue;
            }
            ResourceLocation id = ResourceLocation.tryCreate(biome);
            if (id == null || !WorldUtil.biomeExists(server, id)) {
                out.add(e.getKey() + ": biome '" + biome + "' is not registered on this server (exact registry id needed). "
                        + "It will not be searched, and no substitute is chosen.");
            }
        }
        if (WorldUtil.world(server, config.hub.dimension) == null) {
            out.add("Hub dimension '" + config.hub.dimension + "' does not exist.");
        }
        return out;
    }
}
