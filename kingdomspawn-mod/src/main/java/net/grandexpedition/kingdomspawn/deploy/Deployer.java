package net.grandexpedition.kingdomspawn.deploy;

import net.grandexpedition.kingdomspawn.KingdomSpawn;
import net.grandexpedition.kingdomspawn.Msg;
import net.grandexpedition.kingdomspawn.WorldUtil;
import net.grandexpedition.kingdomspawn.config.KsConfig;
import net.grandexpedition.kingdomspawn.data.KsData;
import net.grandexpedition.kingdomspawn.logic.Slots;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.Stats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * First-login deployment.
 *
 * <p>A player is deployed at most once: when they join and are eligible (brand new, or forced
 * by /kingdomspawn resetplayer, or waiting in the holding area, or any not-yet-deployed player
 * if deploy_existing_players is on). They arrive 5-10 blocks from their kingdom's approved
 * point, on a spot of their own. Then the "deployed" mark is stored; later logins, deaths and
 * respawns are untouched (beds, anchors and the world spawn work as usual).
 *
 * <p>Players without a kingdom, or whose kingdom has no approved location yet, wait in the
 * holding area; they are deployed as soon as an admin assigns them / approves the location.
 */
public final class Deployer {
    private final List<UUID> pending = new ArrayList<>();

    /** Called from the login event; processed on the next tick (the player is fully in the world). */
    public void onLogin(ServerPlayerEntity player) {
        pending.add(player.getUniqueID());
    }

    public void tick(MinecraftServer server) {
        if (pending.isEmpty()) {
            return;
        }
        List<UUID> now = new ArrayList<>(pending);
        pending.clear();
        for (UUID id : now) {
            ServerPlayerEntity p = server.getPlayerList().getPlayerByUUID(id);
            if (p != null) {
                process(p, true);
            }
        }
    }

    private static boolean isNewPlayer(ServerPlayerEntity player) {
        // Play time in ticks; a first-time player has (almost) none when the login is processed.
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_ONE_MINUTE)) < 20 * 10;
    }

    /** Deploys or holds the player if eligible. {@code login}: true when triggered by a login. */
    public void process(ServerPlayerEntity player, boolean login) {
        KsConfig config = KingdomSpawn.config();
        KsData data = KingdomSpawn.data();
        if (data == null) {
            return;
        }
        String id = player.getUniqueID().toString();
        boolean forced = data.forced.contains(id);
        if (data.deployed.containsKey(id) && !forced) {
            return;
        }
        boolean held = data.held.contains(id);
        boolean eligible = forced || held || isNewPlayer(player) || config.deployment.deploy_existing_players;
        if (!eligible) {
            return;
        }
        String kingdom = config.kingdomOf(player.getUniqueID());
        if (kingdom == null) {
            hold(player, login, "You have not been assigned to a kingdom yet. Please wait here; you will be sent "
                    + "to your kingdom as soon as an administrator assigns you.");
            return;
        }
        KsData.Location loc = data.approved.get(kingdom);
        if (loc == null || loc.center == null) {
            hold(player, login, "Welcome, member of " + config.displayName(kingdom) + "! Your kingdom's land has not "
                    + "been chosen yet. Please wait here; you will be sent there once it is ready.");
            return;
        }
        deploy(player, kingdom, loc);
    }

    private void hold(ServerPlayerEntity player, boolean login, String message) {
        KsConfig config = KingdomSpawn.config();
        KsData data = KingdomSpawn.data();
        String id = player.getUniqueID().toString();
        boolean first = !data.held.contains(id);
        if (data.held.add(id)) {
            data.markDirty();
        }
        if (config.holding.enabled && login && (first || config.deployment.hold_on_every_login)) {
            KsConfig.Place place = config.holding.location != null ? config.holding.location : config.hub;
            if (!Places.teleport(player, place)) {
                KingdomSpawn.LOG.warn("Holding area {} is not usable", place.dimension);
            }
        }
        Msg.send(player, Msg.info(message));
    }

    /** Sends the player to their kingdom now. Returns false (and holds them) if no safe spot exists. */
    public boolean deploy(ServerPlayerEntity player, String kingdom, KsData.Location loc) {
        KsConfig config = KingdomSpawn.config();
        KsData data = KingdomSpawn.data();
        MinecraftServer server = player.getServer();
        ServerWorld world = WorldUtil.world(server, loc.center.dimension);
        if (world == null) {
            KingdomSpawn.LOG.error("Kingdom {} location is in unknown dimension {}", kingdom, loc.center.dimension);
            return false;
        }
        BlockPos center = new BlockPos(loc.center.x, loc.center.y, loc.center.z);
        BlockPos spot = arrival(world, kingdom, center, config.deployment);
        if (spot == null) {
            String warn = "No safe arrival spot near " + kingdom + "'s point " + loc.center + " for "
                    + player.getGameProfile().getName() + "; they wait in the holding area. Check the location "
                    + "(/kingdomspawn tp " + kingdom + ") or set a new one (/kingdomspawn sethere " + kingdom + ").";
            KingdomSpawn.LOG.warn(warn);
            notifyAdmins(server, Msg.error(warn));
            hold(player, true, "Your kingdom's arrival area is blocked right now; an administrator has been told.");
            return false;
        }
        double x = spot.getX() + 0.5D;
        double z = spot.getZ() + 0.5D;
        float yaw = (float) (MathHelper.atan2(center.getZ() + 0.5D - z, center.getX() + 0.5D - x) * (180.0D / Math.PI)) - 90.0F;
        if (player.isPassenger()) {
            player.stopRiding();
        }
        player.fallDistance = 0.0F;
        player.teleport(world, x, spot.getY(), z, yaw, 0.0F);
        String id = player.getUniqueID().toString();
        KsData.Deployed d = new KsData.Deployed();
        d.kingdom = kingdom;
        d.name = player.getGameProfile().getName();
        d.time = System.currentTimeMillis();
        data.deployed.put(id, d);
        data.forced.remove(id);
        data.held.remove(id);
        data.markDirty();
        data.save();
        Msg.send(player, Msg.ok("Welcome to " + config.displayName(kingdom) + "! These are your kingdom's lands. "
                + "Your bed and respawn anchor work as usual; /spawn takes you to the central hub."));
        KingdomSpawn.LOG.info("Deployed {} ({}) to {} at {}", d.name, id, kingdom, spot);
        return true;
    }

    /** A free, safe spot 5-10 blocks from the centre; each member gets their own. */
    private BlockPos arrival(ServerWorld world, String kingdom, BlockPos center, KsConfig.Deployment cfg) {
        List<int[]> slots = Slots.offsets(cfg.radius_min, cfg.radius_max);
        int start = 0;
        for (KsData.Deployed d : KingdomSpawn.data().deployed.values()) {
            if (kingdom.equals(d.kingdom)) {
                start++;
            }
        }
        for (int i = 0; i < slots.size(); i++) {
            int[] o = slots.get((start + i) % slots.size());
            BlockPos p = WorldUtil.surface(world, center.getX() + o[0], center.getZ() + o[1]);
            if (Math.abs(p.getY() - center.getY()) <= 6 && WorldUtil.safeStand(world, p) && !occupied(world, p)) {
                return p;
            }
        }
        BlockPos c = WorldUtil.surface(world, center.getX(), center.getZ());
        return WorldUtil.safeStand(world, c) ? c : null;
    }

    private static boolean occupied(ServerWorld world, BlockPos p) {
        for (ServerPlayerEntity other : world.getPlayers()) {
            if (other.getDistanceSq(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D) < 2.25D) {
                return true;
            }
        }
        return false;
    }

    /** After an assignment or approval: deploy online players of that kingdom who are waiting. */
    public int deployWaiting(MinecraftServer server, String kingdom) {
        KsData data = KingdomSpawn.data();
        KsConfig config = KingdomSpawn.config();
        int n = 0;
        for (ServerPlayerEntity p : new ArrayList<>(server.getPlayerList().getPlayers())) {
            String id = p.getUniqueID().toString();
            if ((data.held.contains(id) || data.forced.contains(id)) && kingdom.equals(config.kingdomOf(p.getUniqueID()))) {
                KsData.Location loc = data.approved.get(kingdom);
                if (loc != null && deploy(p, kingdom, loc)) {
                    n++;
                }
            }
        }
        return n;
    }

    public static void notifyAdmins(MinecraftServer server, net.minecraft.util.text.ITextComponent text) {
        int level = KingdomSpawn.config().admin_permission_level;
        for (ServerPlayerEntity p : server.getPlayerList().getPlayers()) {
            if (p.hasPermissionLevel(level)) {
                Msg.send(p, text);
            }
        }
    }
}
