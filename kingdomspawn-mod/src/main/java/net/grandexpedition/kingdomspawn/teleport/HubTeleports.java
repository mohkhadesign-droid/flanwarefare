package net.grandexpedition.kingdomspawn.teleport;

import net.grandexpedition.kingdomspawn.KingdomSpawn;
import net.grandexpedition.kingdomspawn.Msg;
import net.grandexpedition.kingdomspawn.WorldUtil;
import net.grandexpedition.kingdomspawn.config.KsConfig;
import net.grandexpedition.kingdomspawn.data.KsData;
import net.grandexpedition.kingdomspawn.deploy.Places;
import net.grandexpedition.kingdomspawn.logic.Bounds;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * /spawn and /return.
 *
 * <ul>
 *   <li>/spawn saves where the player is (dimension, position, rotation) and, after the delay,
 *   takes them to the hub. Using it again at the hub does not overwrite the saved spot.</li>
 *   <li>/return takes them back to the saved spot and clears it.</li>
 *   <li>Against combat escapes: a delay (cancelled by moving or taking damage), a block while
 *   in PvP combat, a cooldown, /return only from the hub, the saved spot cleared on death or
 *   after an optional expiry, and destinations outside the permitted area refused.</li>
 * </ul>
 */
public final class HubTeleports {
    private enum Kind { SPAWN, RETURN }

    private static final class Pending {
        final Kind kind;
        final Vector3d start;
        final long due;

        Pending(Kind kind, Vector3d start, long due) {
            this.kind = kind;
            this.start = start;
            this.due = due;
        }
    }

    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, Long> lastTeleport = new HashMap<>();
    private long ticks;

    private static boolean bypass(ServerPlayerEntity p) {
        KsConfig c = KingdomSpawn.config();
        return c.teleport.admins_bypass && p.hasPermissionLevel(c.admin_permission_level);
    }

    public boolean atHub(ServerPlayerEntity p) {
        KsConfig.Hub hub = KingdomSpawn.config().hub;
        ServerWorld world = Places.world(p, hub);
        if (world == null || p.world != world) {
            return false;
        }
        Vector3d h = Places.position(world, hub);
        return p.getDistanceSq(h.x, h.y, h.z) <= hub.radius * hub.radius;
    }

    // ------------------------------------------------------------------ commands

    public int requestSpawn(ServerPlayerEntity p) {
        return request(p, Kind.SPAWN);
    }

    public int requestReturn(ServerPlayerEntity p) {
        KsData.ReturnPoint r = validReturn(p, true);
        if (r == null) {
            return 0;
        }
        if (KingdomSpawn.config().teleport.return_only_from_hub && !atHub(p) && !bypass(p)) {
            Msg.send(p, Msg.error("You can only use /" + KingdomSpawn.config().teleport.return_command
                    + " from the spawn hub."));
            return 0;
        }
        return request(p, Kind.RETURN);
    }

    private int request(ServerPlayerEntity p, Kind kind) {
        KsConfig.Teleport cfg = KingdomSpawn.config().teleport;
        UUID id = p.getUniqueID();
        boolean bypass = bypass(p);
        long now = System.currentTimeMillis();
        if (!bypass) {
            Long combat = combatUntil.get(id);
            if (combat != null && now < combat) {
                Msg.send(p, Msg.error("You are in combat. Try again in " + ((combat - now) / 1000 + 1) + " s."));
                return 0;
            }
            Long last = lastTeleport.get(id);
            if (last != null && now - last < cfg.cooldown_seconds * 1000L) {
                Msg.send(p, Msg.error("Teleport cooldown: " + ((cfg.cooldown_seconds * 1000L - (now - last)) / 1000 + 1)
                        + " s left."));
                return 0;
            }
        }
        int delay = bypass ? 0 : Math.max(0, cfg.delay_seconds);
        if (delay == 0) {
            execute(p, kind);
            return 1;
        }
        pending.put(id, new Pending(kind, p.getPositionVec(), ticks + delay * 20L));
        Msg.send(p, Msg.info("Teleporting in " + delay + " s. Don't move or take damage."));
        return 1;
    }

    // ------------------------------------------------------------------ events

    public void tick(MinecraftServer server) {
        ticks++;
        if (pending.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> e = it.next();
            ServerPlayerEntity p = server.getPlayerList().getPlayerByUUID(e.getKey());
            if (p == null || !p.isAlive()) {
                it.remove();
                continue;
            }
            if (p.getPositionVec().squareDistanceTo(e.getValue().start) > 0.25D) {
                it.remove();
                Msg.send(p, Msg.error("Teleport cancelled: you moved."));
                continue;
            }
            if (ticks >= e.getValue().due) {
                it.remove();
                execute(p, e.getValue().kind);
            }
        }
    }

    /** Any damage cancels a pending teleport; player-vs-player damage puts both in combat. */
    public void onHurt(ServerPlayerEntity victim, DamageSource source) {
        if (pending.remove(victim.getUniqueID()) != null) {
            Msg.send(victim, Msg.error("Teleport cancelled: you took damage."));
        }
        Entity attacker = source.getTrueSource();
        if (attacker instanceof ServerPlayerEntity && attacker != victim) {
            long until = System.currentTimeMillis() + KingdomSpawn.config().teleport.combat_seconds * 1000L;
            combatUntil.put(victim.getUniqueID(), until);
            combatUntil.put(attacker.getUniqueID(), until);
            if (pending.remove(attacker.getUniqueID()) != null) {
                Msg.send((ServerPlayerEntity) attacker, Msg.error("Teleport cancelled: you are in combat."));
            }
        }
    }

    /** Death invalidates the saved return point (no death recovery through /return). */
    public void onDeath(ServerPlayerEntity player) {
        pending.remove(player.getUniqueID());
        KsData data = KingdomSpawn.data();
        if (data != null && data.returns.remove(player.getUniqueID().toString()) != null) {
            data.markDirty();
        }
    }

    public void onLogout(ServerPlayerEntity player) {
        pending.remove(player.getUniqueID());
    }

    // ------------------------------------------------------------------ teleporting

    private void execute(ServerPlayerEntity p, Kind kind) {
        if (kind == Kind.SPAWN) {
            toHub(p);
        } else {
            back(p);
        }
    }

    private void toHub(ServerPlayerEntity p) {
        KsConfig config = KingdomSpawn.config();
        KsData data = KingdomSpawn.data();
        String id = p.getUniqueID().toString();
        if (!atHub(p)) {
            KsData.ReturnPoint r = new KsData.ReturnPoint();
            r.dimension = WorldUtil.dimensionId(p.world);
            r.x = p.getPosX();
            r.y = p.getPosY();
            r.z = p.getPosZ();
            r.yaw = p.rotationYaw;
            r.pitch = p.rotationPitch;
            r.saved = System.currentTimeMillis();
            data.returns.put(id, r);
            data.markDirty();
        }
        if (!Places.teleport(p, config.hub)) {
            Msg.send(p, Msg.error("The hub location is not set up correctly. Tell an administrator."));
            return;
        }
        lastTeleport.put(p.getUniqueID(), System.currentTimeMillis());
        Msg.send(p, Msg.ok("Welcome to the hub. Use /" + config.teleport.return_command + " to go back."));
    }

    /** The saved return point if it is still valid; otherwise null (with a message if {@code tell}). */
    private KsData.ReturnPoint validReturn(ServerPlayerEntity p, boolean tell) {
        KsConfig config = KingdomSpawn.config();
        KsData data = KingdomSpawn.data();
        KsData.ReturnPoint r = data.returns.get(p.getUniqueID().toString());
        if (r == null) {
            if (tell) {
                Msg.send(p, Msg.error("You have no saved location to return to. Use /" + config.teleport.spawn_command
                        + " first (dying clears the saved location)."));
            }
            return null;
        }
        int expiry = config.teleport.return_expiry_minutes;
        if (expiry > 0 && System.currentTimeMillis() - r.saved > expiry * 60_000L) {
            data.returns.remove(p.getUniqueID().toString());
            data.markDirty();
            if (tell) {
                Msg.send(p, Msg.error("Your saved location expired."));
            }
            return null;
        }
        return r;
    }

    private void back(ServerPlayerEntity p) {
        KsConfig config = KingdomSpawn.config();
        KsData data = KingdomSpawn.data();
        KsData.ReturnPoint r = validReturn(p, true);
        if (r == null) {
            return;
        }
        ServerWorld world = WorldUtil.world(p.getServer(), r.dimension);
        if (world == null) {
            Msg.send(p, Msg.error("Your saved location's dimension no longer exists."));
            return;
        }
        BlockPos feet = new BlockPos(r.x, r.y, r.z);
        Bounds allowed = Bounds.withMargin(config.search.min_x, config.search.max_x, config.search.min_z,
                config.search.max_z, 0);
        boolean inOverworldBounds = !world.getDimensionKey().equals(net.minecraft.world.World.OVERWORLD)
                || (allowed != null && allowed.contains(r.x, r.z));
        if (!world.getWorldBorder().contains(feet) || !inOverworldBounds) {
            Msg.send(p, Msg.error("Your saved location is outside the permitted world area."));
            return;
        }
        world.getChunk(feet); // load (and if needed generate) the destination chunk first
        // The player must fit exactly where they stood (slabs, carpets, water are fine), no lava there.
        net.minecraft.util.math.AxisAlignedBB box = new net.minecraft.util.math.AxisAlignedBB(
                r.x - 0.3D, r.y, r.z - 0.3D, r.x + 0.3D, r.y + 1.8D, r.z + 0.3D);
        boolean lava = world.getFluidState(feet).isTagged(net.minecraft.tags.FluidTags.LAVA)
                || world.getFluidState(feet.up()).isTagged(net.minecraft.tags.FluidTags.LAVA);
        if (r.y < 1 || lava || !world.hasNoCollisions(p, box)) {
            Msg.send(p, Msg.error("Your saved location is no longer safe (blocked or lava). "
                    + "It is kept; ask an administrator if you are stuck."));
            return;
        }
        double y = r.y;
        if (p.isPassenger()) {
            p.stopRiding();
        }
        p.fallDistance = 0.0F;
        p.teleport(world, r.x, y, r.z, r.yaw, r.pitch);
        data.returns.remove(p.getUniqueID().toString());
        data.markDirty();
        lastTeleport.put(p.getUniqueID(), System.currentTimeMillis());
        Msg.send(p, Msg.ok("Returned to your previous location."));
    }
}
