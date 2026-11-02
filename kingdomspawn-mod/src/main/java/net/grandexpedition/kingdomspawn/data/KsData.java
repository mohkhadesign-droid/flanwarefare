package net.grandexpedition.kingdomspawn.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.grandexpedition.kingdomspawn.KingdomSpawn;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Runtime state kept with the world (world/kingdomspawn/state.json): proposed and approved
 * deployment points, deployment status per player, players waiting in the holding area, forced
 * redeployments and saved /return locations. Readable JSON, saved atomically.
 */
public final class KsData {
    public static final class Point {
        public String dimension;
        public int x;
        public int y;
        public int z;

        public Point() {
        }

        public Point(String dimension, int x, int y, int z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public double distanceFromCenter() {
            return Math.sqrt((double) x * x + (double) z * z);
        }

        @Override
        public String toString() {
            return x + ", " + y + ", " + z + " (" + dimension + ")";
        }
    }

    public static final class Location {
        public Point center;
        public String biome;
        /** "search" or "manual". */
        public String source;
        public long time;
    }

    public static final class Deployed {
        public String kingdom;
        public String name;
        public long time;
    }

    public static final class ReturnPoint {
        public String dimension;
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
        public long saved;
    }

    public Map<String, Location> approved = new LinkedHashMap<>();
    public Map<String, Location> proposals = new LinkedHashMap<>();
    public Map<String, Deployed> deployed = new HashMap<>();
    /** Players to deploy at their next login regardless of history (resetplayer). */
    public Set<String> forced = new HashSet<>();
    /** Players waiting in the holding area (unassigned, or their kingdom has no location yet). */
    public Set<String> held = new HashSet<>();
    public Map<String, ReturnPoint> returns = new HashMap<>();

    private transient Path file;
    private transient boolean dirty;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static KsData load(Path file) throws IOException {
        KsData d;
        if (!Files.exists(file)) {
            d = new KsData();
        } else {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                d = GSON.fromJson(r, KsData.class);
            } catch (JsonParseException | IllegalStateException e) {
                throw new IOException("malformed " + file + ": " + e.getMessage(), e);
            }
            if (d == null) {
                d = new KsData();
            }
        }
        if (d.approved == null) {
            d.approved = new LinkedHashMap<>();
        }
        if (d.proposals == null) {
            d.proposals = new LinkedHashMap<>();
        }
        if (d.deployed == null) {
            d.deployed = new HashMap<>();
        }
        if (d.forced == null) {
            d.forced = new HashSet<>();
        }
        if (d.held == null) {
            d.held = new HashSet<>();
        }
        if (d.returns == null) {
            d.returns = new HashMap<>();
        }
        d.file = file;
        return d;
    }

    public void markDirty() {
        dirty = true;
    }

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            KingdomSpawn.LOG.error("Could not save {}", file, e);
        }
    }
}
