package net.grandexpedition.kingdomspawn.search;

import net.grandexpedition.kingdomspawn.KingdomSpawn;
import net.grandexpedition.kingdomspawn.Msg;
import net.grandexpedition.kingdomspawn.WorldUtil;
import net.grandexpedition.kingdomspawn.config.KsConfig;
import net.grandexpedition.kingdomspawn.data.KsData;
import net.grandexpedition.kingdomspawn.deploy.Terrain;
import net.grandexpedition.kingdomspawn.logic.Bounds;
import net.grandexpedition.kingdomspawn.logic.Placement;
import net.grandexpedition.kingdomspawn.logic.SearchPlan;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Finds distant, well-separated, safe deployment points for one or more kingdoms, a little each
 * server tick (all on the server thread, within a time budget, so the server never freezes).
 *
 * <ol>
 *   <li><b>Sampling</b>: biome samples on a grid over the search area, farthest from 0,0 first
 *   (corners and edges), moving inward in bands. Biomes come from the world generator's biome
 *   source, so no chunk is loaded or generated. A sample counts as a candidate for a kingdom if
 *   it is that kingdom's exact biome and most of a ring around it is too (inside the biome, not
 *   on a sliver). After each band, if every kingdom has enough candidates and a layout meeting
 *   the minimum separation exists, sampling stops early.</li>
 *   <li><b>Selection</b>: {@link Placement} picks one candidate per kingdom together: all pairs
 *   (and already-approved kingdoms) at least the minimum separation apart, as far out and as
 *   spread as possible.</li>
 *   <li><b>Terrain</b>: for each chosen candidate, a generator height pre-check (no chunks), then
 *   the few chunks around it are generated one per tick and checked for a safe centre with
 *   enough safe arrival spots. A failed candidate is excluded and the selection is redone.</li>
 * </ol>
 * The result is stored as proposals; nothing changes for players until an admin approves.
 */
public final class BiomeSearch {
    public enum Phase { SAMPLING, TERRAIN, DONE, FAILED, CANCELLED }

    private static final int SAMPLE_Y = 64;
    private static final int[][] NOISE_OFFSETS = {
            {0, 0}, {16, 0}, {-16, 0}, {0, 16}, {0, -16}, {16, 16}, {-16, 16}, {16, -16}, {-16, -16},
            {32, 0}, {-32, 0}, {0, 32}, {0, -32}};

    private final MinecraftServer server;
    private final ServerWorld world;
    private final UUID initiator;
    private final List<String> kingdoms;
    private final Map<String, ResourceLocation> biomes = new LinkedHashMap<>();
    private final List<Placement.Candidate> fixed = new ArrayList<>();
    private final KsConfig.Search cfg;
    private final KsConfig.Deployment deploy;
    private final SearchPlan plan;
    private final IdentityHashMap<Biome, ResourceLocation> ids = new IdentityHashMap<>();
    private final Map<String, List<Placement.Candidate>> hits = new LinkedHashMap<>();
    private final Map<String, Set<Long>> rejected = new HashMap<>();
    private final Map<String, Long> validatedKey = new HashMap<>();
    private final Map<String, BlockPos> validatedCenter = new HashMap<>();
    private Phase phase = Phase.SAMPLING;
    private int index;
    private long lastProgress;
    private int terrainAttempts;
    private TerrainJob job;
    private final long started = System.currentTimeMillis();

    /** Throws IllegalArgumentException with a readable reason if the search cannot start. */
    public BiomeSearch(MinecraftServer server, UUID initiator, List<String> kingdomIds, KsConfig config, KsData data) {
        this.server = server;
        this.world = server.getWorld(World.OVERWORLD);
        this.initiator = initiator;
        this.cfg = config.search;
        this.deploy = config.deployment;
        this.kingdoms = new ArrayList<>(kingdomIds);
        for (String k : kingdoms) {
            KsConfig.Kingdom def = config.kingdoms.get(k);
            String biome = def == null ? null : def.biome;
            ResourceLocation id = biome == null ? null : ResourceLocation.tryCreate(biome);
            if (id == null || biome.isEmpty()) {
                throw new IllegalArgumentException(k + " has no biome: /kingdomspawn setbiome " + k + " <biome_id>");
            }
            if (!WorldUtil.biomeExists(server, id)) {
                throw new IllegalArgumentException(k + ": biome '" + id + "' is not registered on this server");
            }
            biomes.put(k, id);
            hits.put(k, new ArrayList<Placement.Candidate>());
            rejected.put(k, new HashSet<Long>());
        }
        // Kingdoms not being searched keep their approved location; the new ones must stay away from them.
        for (Map.Entry<String, KsData.Location> e : data.approved.entrySet()) {
            if (!kingdoms.contains(e.getKey()) && e.getValue().center != null) {
                fixed.add(new Placement.Candidate(e.getValue().center.x, e.getValue().center.z));
            }
        }
        Bounds b = Bounds.withMargin(cfg.min_x, cfg.max_x, cfg.min_z, cfg.max_z, cfg.safety_margin);
        if (b == null) {
            throw new IllegalArgumentException("the search bounds minus the safety margin leave no area");
        }
        this.plan = SearchPlan.create(b.minX, b.maxX, b.minZ, b.maxZ, cfg.sample_spacing);
        report(Msg.info("Search started for " + String.join(", ", kingdoms) + ": " + plan.size()
                + " biome samples, from " + plan.distance(0) + " blocks out (corners) inward. Minimum separation "
                + (int) cfg.min_separation + " blocks. /kingdomspawn status, /kingdomspawn cancel."));
    }

    public Phase phase() {
        return phase;
    }

    public boolean running() {
        return phase == Phase.SAMPLING || phase == Phase.TERRAIN;
    }

    public void cancel(String reason) {
        if (running()) {
            phase = Phase.CANCELLED;
            report(Msg.info("Search cancelled (" + reason + "). Nothing was changed."));
        }
    }

    // ------------------------------------------------------------------ ticking

    public void tick() {
        if (!running()) {
            return;
        }
        try {
            if (phase == Phase.SAMPLING) {
                sample();
            } else {
                terrain();
            }
        } catch (RuntimeException e) {
            KingdomSpawn.LOG.error("Kingdom search failed", e);
            fail("Search stopped by an error: " + e);
        }
        long now = System.currentTimeMillis();
        if (running() && now - lastProgress >= Math.max(1, cfg.progress_seconds) * 1000L) {
            lastProgress = now;
            report(Msg.info(status()));
        }
    }

    public String status() {
        StringBuilder sb = new StringBuilder();
        if (phase == Phase.SAMPLING) {
            int pct = (int) (100L * index / Math.max(1, plan.size()));
            sb.append("Sampling ").append(pct).append("% (").append(index).append('/').append(plan.size())
                    .append("), now ").append(index < plan.size() ? plan.distance(index) : 0).append(" blocks from centre.");
        } else if (phase == Phase.TERRAIN) {
            sb.append("Checking terrain").append(job != null ? " for " + job.kingdom + " at " + job.candidate : "")
                    .append(" (attempt ").append(terrainAttempts + 1).append(").");
        } else {
            sb.append("Search ").append(phase.name().toLowerCase()).append('.');
        }
        sb.append(" Candidates:");
        for (String k : kingdoms) {
            sb.append(' ').append(k).append('=').append(hits.get(k).size());
        }
        sb.append(". Running ").append((System.currentTimeMillis() - started) / 1000).append(" s.");
        return sb.toString();
    }

    private ResourceLocation idOf(Biome b) {
        ResourceLocation id = ids.get(b);
        if (id == null && !ids.containsKey(b)) {
            id = WorldUtil.biomeId(world, b);
            ids.put(b, id);
        }
        return id;
    }

    private void sample() {
        long deadline = System.nanoTime() + Math.max(1, cfg.budget_ms_per_tick) * 1_000_000L;
        while (index < plan.size() && System.nanoTime() < deadline) {
            int x = plan.x(index);
            int z = plan.z(index);
            ResourceLocation here = idOf(WorldUtil.noiseBiome(world, x, SAMPLE_Y, z));
            for (String k : kingdoms) {
                if (biomes.get(k).equals(here) && isCore(x, z, here)) {
                    hits.get(k).add(new Placement.Candidate(x, z));
                }
            }
            boolean bandEnd = plan.endsBand(index, cfg.band_width);
            index++;
            if (bandEnd && enoughToStop()) {
                report(Msg.info("Enough distant candidates found by " + plan.distance(index - 1)
                        + " blocks from centre; stopping the biome scan there."));
                phase = Phase.TERRAIN;
                return;
            }
        }
        if (index >= plan.size()) {
            List<String> missing = new ArrayList<>();
            for (String k : kingdoms) {
                if (hits.get(k).isEmpty()) {
                    missing.add(k + " (" + biomes.get(k) + ")");
                }
            }
            if (!missing.isEmpty()) {
                fail("No usable area of the assigned biome was found in the search area for: " + String.join(", ", missing)
                        + ". No substitute biome is chosen. Try a smaller sample_spacing or core_min_matches.");
                return;
            }
            phase = Phase.TERRAIN;
        }
    }

    private boolean isCore(int x, int z, ResourceLocation biome) {
        int r = Math.max(8, cfg.core_radius);
        int d = (int) Math.round(r * 0.7071);
        int[][] ring = {{r, 0}, {-r, 0}, {0, r}, {0, -r}, {d, d}, {-d, d}, {d, -d}, {-d, -d}};
        int matches = 0;
        for (int[] o : ring) {
            if (biome.equals(idOf(WorldUtil.noiseBiome(world, x + o[0], SAMPLE_Y, z + o[1])))) {
                matches++;
            }
        }
        return matches >= Math.min(8, Math.max(0, cfg.core_min_matches));
    }

    private boolean enoughToStop() {
        for (String k : kingdoms) {
            if (hits.get(k).size() < Math.max(1, cfg.stop_after_candidates)) {
                return false;
            }
        }
        return select().chosen != null;
    }

    private static long key(Placement.Candidate c) {
        return ((long) c.x << 32) | (c.z & 0xFFFFFFFFL);
    }

    private Placement.Result select() {
        List<List<Placement.Candidate>> lists = new ArrayList<>();
        for (String k : kingdoms) {
            List<Placement.Candidate> usable = new ArrayList<>();
            for (Placement.Candidate c : hits.get(k)) {
                if (!rejected.get(k).contains(key(c))) {
                    usable.add(c);
                }
            }
            lists.add(Placement.diversify(usable, 16, 4));
        }
        return Placement.choose(lists, fixed, cfg.min_separation);
    }

    // ------------------------------------------------------------------ terrain

    private final class TerrainJob {
        final String kingdom;
        final Placement.Candidate candidate;
        int offset;
        int x;
        int z;
        List<int[]> chunks;
        int loaded;

        TerrainJob(String kingdom, Placement.Candidate candidate) {
            this.kingdom = kingdom;
            this.candidate = candidate;
        }
    }

    private void terrain() {
        if (job == null) {
            Placement.Result r = select();
            if (r.chosen == null) {
                StringBuilder sb = new StringBuilder("No layout satisfies the minimum separation of "
                        + (int) cfg.min_separation + " blocks");
                if (r.bestPossibleSeparation > 0) {
                    sb.append(" (best possible with the candidates found: ").append((int) r.bestPossibleSeparation)
                            .append(" blocks)");
                }
                sb.append(". Usable candidates:");
                for (String k : kingdoms) {
                    sb.append(' ').append(k).append('=').append(hits.get(k).size() - rejected.get(k).size());
                }
                sb.append(". Lower search.min_separation, widen the bounds, or choose locations with /kingdomspawn sethere.");
                fail(sb.toString());
                return;
            }
            for (int i = 0; i < kingdoms.size(); i++) {
                String k = kingdoms.get(i);
                Placement.Candidate c = r.chosen.get(i);
                Long done = validatedKey.get(k);
                if (done == null || done != key(c)) {
                    validatedKey.remove(k);
                    validatedCenter.remove(k);
                    job = new TerrainJob(k, c);
                    return;
                }
            }
            finish(r.chosen);
            return;
        }
        TerrainJob j = job;
        ResourceLocation biome = biomes.get(j.kingdom);
        if (j.chunks == null) {
            // Cheap pre-check from the generator (no chunks): one offset per tick.
            if (j.offset >= NOISE_OFFSETS.length) {
                reject(j, "no flat land above sea level near it");
                return;
            }
            int x = j.candidate.x + NOISE_OFFSETS[j.offset][0];
            int z = j.candidate.z + NOISE_OFFSETS[j.offset][1];
            j.offset++;
            if (biome.equals(idOf(WorldUtil.noiseBiome(world, x, SAMPLE_Y, z)))
                    && Terrain.noiseFlat(world, x, z, cfg.max_slope)) {
                j.x = x;
                j.z = z;
                j.chunks = new ArrayList<>();
                int rr = Terrain.readRadius(deploy.radius_max);
                for (int cx = (x - rr) >> 4; cx <= (x + rr) >> 4; cx++) {
                    for (int cz = (z - rr) >> 4; cz <= (z + rr) >> 4; cz++) {
                        j.chunks.add(new int[]{cx, cz});
                    }
                }
            }
            return;
        }
        if (j.loaded < j.chunks.size()) {
            int n = Math.max(1, cfg.chunk_loads_per_tick);
            for (int i = 0; i < n && j.loaded < j.chunks.size(); i++, j.loaded++) {
                int[] c = j.chunks.get(j.loaded);
                world.getChunkProvider().getChunk(c[0], c[1], ChunkStatus.FULL, true);
            }
            return;
        }
        BlockPos center = Terrain.findCenter(world, j.x, j.z, biome, deploy.radius_min, deploy.radius_max,
                cfg.max_slope, cfg.min_safe_slots);
        if (center == null) {
            reject(j, "not enough safe, level ground for " + cfg.min_safe_slots + " players");
            return;
        }
        validatedKey.put(j.kingdom, key(j.candidate));
        validatedCenter.put(j.kingdom, center);
        report(Msg.info(j.kingdom + ": terrain OK at " + center.getX() + ", " + center.getY() + ", " + center.getZ()));
        job = null;
    }

    private void reject(TerrainJob j, String why) {
        rejected.get(j.kingdom).add(key(j.candidate));
        terrainAttempts++;
        report(Msg.info(j.kingdom + ": candidate " + j.candidate + " rejected (" + why + "); trying the next best."));
        job = null;
        if (terrainAttempts >= Math.max(1, cfg.max_terrain_attempts)) {
            fail("Gave up after " + terrainAttempts + " rejected candidates (search.max_terrain_attempts).");
        }
    }

    private void finish(List<Placement.Candidate> chosen) {
        KsData data = KingdomSpawn.data();
        String dim = WorldUtil.dimensionId(world);
        IFormattableTextComponent summary = new StringTextComponent("");
        summary.appendSibling(Msg.ok("Search complete. Proposed deployment points (not active until approved):"));
        for (int i = 0; i < kingdoms.size(); i++) {
            String k = kingdoms.get(i);
            BlockPos c = validatedCenter.get(k);
            KsData.Location loc = new KsData.Location();
            loc.center = new KsData.Point(dim, c.getX(), c.getY(), c.getZ());
            loc.biome = biomes.get(k).toString();
            loc.source = "search";
            loc.time = System.currentTimeMillis();
            data.proposals.put(k, loc);
            summary.appendSibling(new StringTextComponent("\n  " + k + ": " + c.getX() + ", " + c.getY() + ", " + c.getZ()
                    + "  (" + (int) loc.center.distanceFromCenter() + " blocks from centre, " + loc.biome + ")  "))
                    .appendSibling(Msg.button("preview", "/kingdomspawn preview " + k))
                    .appendSibling(new StringTextComponent(" "))
                    .appendSibling(Msg.button("approve", "/kingdomspawn approve " + k));
        }
        StringBuilder seps = new StringBuilder();
        for (int i = 0; i < chosen.size(); i++) {
            for (int j = i + 1; j < chosen.size(); j++) {
                seps.append(seps.length() == 0 ? "" : ", ").append(kingdoms.get(i)).append('-').append(kingdoms.get(j))
                        .append(' ').append((int) Placement.separation(chosen.get(i), chosen.get(j)));
            }
        }
        if (seps.length() > 0) {
            summary.appendSibling(new StringTextComponent("\n  Separation: " + seps + " blocks."));
        }
        data.markDirty();
        data.save();
        phase = Phase.DONE;
        report(summary);
    }

    private void fail(String why) {
        phase = Phase.FAILED;
        report(Msg.error(why));
    }

    private void report(ITextComponent text) {
        KingdomSpawn.LOG.info(text.getString());
        if (initiator != null) {
            ServerPlayerEntity p = server.getPlayerList().getPlayerByUUID(initiator);
            Msg.send(p, text);
        }
    }
}
