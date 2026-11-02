package net.grandexpedition.kingdomspawn.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.grandexpedition.kingdomspawn.KingdomSpawn;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * config/kingdomspawn.json: kingdoms (name, roulette biome, preassigned players), search
 * limits, hub, holding area, deployment and teleport rules. Human-editable; commands that change
 * it write it back. A malformed file is never overwritten: the previous configuration stays in
 * use and the error is reported.
 */
public final class KsConfig {
    /** A preassigned player: UUID (required) and an optional name for readability. */
    public static final class PlayerEntry {
        public String uuid;
        public String name;

        public PlayerEntry() {
        }

        public PlayerEntry(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    public static final class Kingdom {
        public String name = "";
        /** Exact biome registry id, e.g. "minecraft:dark_forest" or "byg:cypress_swamplands". */
        public String biome = "";
        public List<PlayerEntry> players = new ArrayList<>();
    }

    public static final class Search {
        public int min_x = -50000;
        public int max_x = 50000;
        public int min_z = -50000;
        public int max_z = 50000;
        public int safety_margin = 500;
        public double min_separation = 15000;
        /** Distance between biome samples (blocks). 256 = about 150,000 samples for 100k x 100k. */
        public int sample_spacing = 256;
        /** The search moves inward in bands of this width and may stop early after a band. */
        public int band_width = 2000;
        /** A hit counts only if most of the ring at this radius is the same biome (inside, not a sliver). */
        public int core_radius = 48;
        public int core_min_matches = 6;
        /** Stop sampling once every kingdom has this many candidates and a valid layout exists. */
        public int stop_after_candidates = 24;
        /** Server time per tick the search may use (milliseconds). */
        public int budget_ms_per_tick = 8;
        /** Chunks generated/loaded per tick during terrain checks. */
        public int chunk_loads_per_tick = 1;
        public int max_terrain_attempts = 40;
        /** Largest height difference tolerated around a deployment point. */
        public int max_slope = 3;
        /** Safe arrival spots needed around a deployment point (one per member). */
        public int min_safe_slots = 5;
        public int progress_seconds = 5;
    }

    public static class Place {
        public String dimension = "minecraft:overworld";
        /** Null = the world spawn of that dimension. */
        public Double x;
        public Double y;
        public Double z;
        public float yaw;
        public float pitch;
    }

    public static final class Hub extends Place {
        /** Players within this distance of the hub count as "at the hub". */
        public double radius = 64;
    }

    public static final class Holding {
        public boolean enabled = true;
        /** Null = the hub. */
        public Place location;
    }

    public static final class Deployment {
        public int radius_min = 5;
        public int radius_max = 10;
        /**
         * Also deploy players who had already played on this server before being assigned
         * (play time above zero). Off: only brand-new players are deployed automatically
         * (an admin can still send anyone with /kingdomspawn resetplayer).
         */
        public boolean deploy_existing_players = false;
        /** Unassigned new players are sent back to the holding area at every login until assigned. */
        public boolean hold_on_every_login = true;
    }

    public static final class Teleport {
        public boolean register_spawn_commands = true;
        public String spawn_command = "spawn";
        public String return_command = "return";
        public int delay_seconds = 5;
        public int cooldown_seconds = 30;
        /** Seconds after dealing or taking player damage during which /spawn and /return are blocked. */
        public int combat_seconds = 15;
        /** A saved return location expires after this many minutes (0 = never). */
        public int return_expiry_minutes = 0;
        /** /return only works while at the hub (it cannot be used as a general teleport). */
        public boolean return_only_from_hub = true;
        /** Operators skip the delay, cooldown and combat block. */
        public boolean admins_bypass = true;
    }

    public int admin_permission_level = 2;
    public Map<String, Kingdom> kingdoms = new LinkedHashMap<>();
    public Search search = new Search();
    public Hub hub = new Hub();
    public Holding holding = new Holding();
    public Deployment deployment = new Deployment();
    public Teleport teleport = new Teleport();

    // ------------------------------------------------------------------ derived (not saved)

    private transient Map<UUID, String> playerKingdom = new HashMap<>();

    public static KsConfig defaults() {
        KsConfig c = new KsConfig();
        String[] names = {"Kingdom I", "Kingdom II", "Kingdom III"};
        for (int i = 0; i < 3; i++) {
            Kingdom k = new Kingdom();
            k.name = names[i];
            c.kingdoms.put("kingdom_" + (i + 1), k);
        }
        return c;
    }

    /** Kingdom id of a player, or null. */
    public String kingdomOf(UUID player) {
        return playerKingdom.get(player);
    }

    public String displayName(String kingdomId) {
        Kingdom k = kingdoms.get(kingdomId);
        return k == null || k.name == null || k.name.isEmpty() ? kingdomId : k.name;
    }

    /** Rebuilds the lookup and returns problems found (invalid UUIDs, duplicates). */
    public List<String> index() {
        List<String> problems = new ArrayList<>();
        playerKingdom = new HashMap<>();
        if (kingdoms == null) {
            kingdoms = new LinkedHashMap<>();
        }
        for (Map.Entry<String, Kingdom> e : kingdoms.entrySet()) {
            Kingdom k = e.getValue();
            if (k == null) {
                e.setValue(k = new Kingdom());
            }
            if (k.players == null) {
                k.players = new ArrayList<>();
            }
            if (k.biome == null) {
                k.biome = "";
            }
            for (PlayerEntry p : k.players) {
                UUID id = parseUuid(p == null ? null : p.uuid);
                if (id == null) {
                    problems.add("Invalid UUID in " + e.getKey() + ": " + (p == null ? "null" : p.uuid) + " (ignored)");
                    continue;
                }
                String previous = playerKingdom.putIfAbsent(id, e.getKey());
                if (previous != null && !previous.equals(e.getKey())) {
                    problems.add("Player " + id + " is listed in both " + previous + " and " + e.getKey()
                            + " (" + previous + " is used)");
                }
            }
        }
        if (search == null) {
            search = new Search();
        }
        if (hub == null) {
            hub = new Hub();
        }
        if (holding == null) {
            holding = new Holding();
        }
        if (deployment == null) {
            deployment = new Deployment();
        }
        if (teleport == null) {
            teleport = new Teleport();
        }
        return problems;
    }

    public static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            String t = s.trim();
            if (t.length() == 32) {
                t = t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16) + "-"
                        + t.substring(16, 20) + "-" + t.substring(20);
            }
            return UUID.fromString(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ file

    private static Gson gson() {
        // Player entries may be written as plain UUID strings or as {"uuid": ..., "name": ...}.
        JsonDeserializer<PlayerEntry> players = (json, type, ctx) -> {
            if (json.isJsonPrimitive()) {
                return new PlayerEntry(json.getAsString(), null);
            }
            JsonObject o = json.getAsJsonObject();
            JsonElement uuid = o.get("uuid");
            JsonElement name = o.get("name");
            return new PlayerEntry(uuid == null || uuid.isJsonNull() ? null : uuid.getAsString(),
                    name == null || name.isJsonNull() ? null : name.getAsString());
        };
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .registerTypeAdapter(PlayerEntry.class, players).create();
    }

    /** Loads the file, creating it with defaults if missing. Throws on malformed content. */
    public static KsConfig load(Path file, List<String> problems) throws IOException {
        if (!Files.exists(file)) {
            KsConfig c = defaults();
            c.index();
            c.save(file);
            KingdomSpawn.LOG.info("Created default configuration {}", file);
            return c;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            KsConfig c = gson().fromJson(r, KsConfig.class);
            if (c == null) {
                throw new IOException("empty file");
            }
            problems.addAll(c.index());
            return c;
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("malformed JSON: " + e.getMessage(), e);
        }
    }

    /** Writes atomically (temporary file, then replace). */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            gson().toJson(this, w);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
