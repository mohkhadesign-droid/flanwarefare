package net.flanwarfare.capture;

import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.CaptureModel;
import net.flanwarfare.util.Msg;
import net.minecraft.entity.player.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Evaluates every capture area on a fixed interval (default every 10 ticks) using only
 * server-side positions. Cost per evaluation is (online players x flags in their dimension),
 * which is tiny for a 15-player server; no blocks are scanned and no chunks are loaded.
 */
public final class CaptureManager {

    private static final class Presence {
        final Set<String> attackerSides = new HashSet<>();
        final Map<String, Set<UUID>> attackersBySide = new HashMap<>();
        final Set<UUID> everyone = new HashSet<>();
        boolean defender;
    }

    private static final Presence NOBODY = new Presence();

    private final WarCore core;
    private final Map<UUID, CaptureSession> sessions = new HashMap<>();

    public CaptureManager(WarCore core) {
        this.core = core;
    }

    public void tick(long ticks) {
        int interval = WarConfig.CHECK_INTERVAL_TICKS.get();
        if (ticks % interval != 0) {
            return;
        }
        boolean raidOpen = core.raid().isActive();
        long windowId = core.raid().windowId();

        Map<String, List<ClaimRecord>> flagsByDimension = new HashMap<>();
        for (ClaimRecord record : core.data().claims().values()) {
            if (record.hasFlag && record.isWarEnabled() && !core.isDimensionDisabled(record.dimension)) {
                flagsByDimension.computeIfAbsent(record.dimension, d -> new ArrayList<>()).add(record);
            }
        }
        if (flagsByDimension.isEmpty() && sessions.isEmpty()) {
            return;
        }

        Map<UUID, Claim> claims = new HashMap<>();
        Map<UUID, Presence> presence = new HashMap<>();
        double radius = WarConfig.CAPTURE_RADIUS.get();
        double radiusSq = radius * radius;
        for (ServerPlayerEntity player : core.server().getPlayerList().getPlayers()) {
            if (!canTakePart(player)) {
                continue;
            }
            List<ClaimRecord> flags = flagsByDimension.get(FlanBridge.dimensionId(player.world));
            if (flags == null) {
                continue;
            }
            for (ClaimRecord record : flags) {
                double dx = player.getPosX() - (record.flagX + 0.5);
                double dz = player.getPosZ() - (record.flagZ + 0.5);
                double dy = player.getPosY() - record.flagY;
                if (dx * dx + dz * dz > radiusSq || dy < -1.0 || dy > radius + 1.0) {
                    continue;
                }
                Claim claim = claims.computeIfAbsent(record.claimId, id -> FlanBridge.find(core.server(), record));
                if (claim == null || claim.isAdminClaim()) {
                    continue;
                }
                Presence p = presence.computeIfAbsent(record.claimId, id -> new Presence());
                p.everyone.add(player.getUniqueID());
                if (core.sides().isDefender(player, claim)) {
                    p.defender = true;
                } else {
                    String side = core.sides().sideOf(player.getUniqueID());
                    p.attackerSides.add(side);
                    p.attackersBySide.computeIfAbsent(side, s -> new HashSet<>()).add(player.getUniqueID());
                }
            }
        }

        Set<UUID> toVisit = new HashSet<>(sessions.keySet());
        toVisit.addAll(presence.keySet());
        for (UUID claimId : toVisit) {
            step(claimId, claims, presence.getOrDefault(claimId, NOBODY), raidOpen, windowId, interval);
        }
    }

    private boolean canTakePart(ServerPlayerEntity player) {
        if (!player.isAlive() || player.isSpectator()) {
            return false;
        }
        return !player.isCreative() || WarConfig.CREATIVE_CAN_CAPTURE.get();
    }

    private void step(UUID claimId, Map<UUID, Claim> claims, Presence presence, boolean raidOpen, long windowId, int dt) {
        ClaimRecord record = core.data().claim(claimId);
        Claim claim = claims.get(claimId);
        if (claim == null && record != null) {
            claim = FlanBridge.find(core.server(), record);
        }
        if (record == null || claim == null || claim.isAdminClaim() || !record.isWarEnabled() || !record.hasFlag
                || core.isExcluded(claim)) {
            cancel(claimId);
            return;
        }
        CaptureSession session = sessions.get(claimId);
        boolean attackers = !presence.attackerSides.isEmpty();
        if (session == null) {
            if (!raidOpen || !attackers) {
                return;
            }
            session = open(claim, record, windowId);
        } else if (raidOpen && session.windowId != windowId && attackers) {
            // Progress from an earlier raid window is never reused: start fresh.
            cancel(claimId);
            session = open(claim, record, windowId);
        }

        boolean progressAllowed = raidOpen && session.windowId == windowId;
        CaptureModel.Status status = session.model.step(dt, progressAllowed, presence.attackerSides, presence.defender,
                WarConfig.CONTEST_BY_DEFENDERS.get(), WarConfig.CONTEST_BY_RIVALS.get());
        String side = session.model.side();
        Set<UUID> sidePlayers = side == null ? Collections.<UUID>emptySet()
                : presence.attackersBySide.getOrDefault(side, Collections.<UUID>emptySet());
        session.selector.update(core.ticks(), side, sidePlayers);

        if (status == CaptureModel.Status.COMPLETE) {
            UUID winner = session.selector.designated();
            cancel(claimId);
            if (winner != null && progressAllowed) {
                core.conquest().complete(claim, record, winner, presence.everyone);
            }
            return;
        }
        if (session.model.isFinished() && presence.everyone.isEmpty()) {
            cancel(claimId);
            return;
        }
        session.updateBar(title(claim, session, status, progressAllowed), viewers(record));
    }

    private CaptureSession open(Claim claim, ClaimRecord record, long windowId) {
        CaptureSession session = new CaptureSession(record.claimId, record.dimension, windowId,
                WarConfig.CAPTURE_SECONDS.get() * 20L, WarConfig.DECAY_SECONDS.get() * 20L);
        sessions.put(record.claimId, session);
        ServerPlayerEntity owner = core.server().getPlayerList().getPlayerByUUID(claim.getOwner());
        Msg.send(owner, Msg.error("Your claim '" + FlanBridge.label(claim) + "' is being captured! Defend its flag at "
                + record.flagX + ", " + record.flagY + ", " + record.flagZ + "."));
        return session;
    }

    private String title(Claim claim, CaptureSession session, CaptureModel.Status status, boolean progressAllowed) {
        int percent = (int) Math.floor(session.model.fraction() * 100.0);
        String state;
        switch (status) {
            case CONTESTED:
                state = "Contested";
                break;
            case DECAYING:
                state = progressAllowed ? "Losing progress" : "Raid over - decaying";
                break;
            case CAPTURING:
                state = "Capturing";
                break;
            default:
                state = "Idle";
                break;
        }
        UUID capturer = session.selector.designated();
        String who = capturer == null ? "" : " - " + FlanBridge.playerName(core.server(), capturer);
        return FlanBridge.label(claim) + " | " + state + " " + percent + "%" + who;
    }

    private Set<ServerPlayerEntity> viewers(ClaimRecord record) {
        Set<ServerPlayerEntity> viewers = new HashSet<>();
        double range = WarConfig.BOSSBAR_DISTANCE.get();
        double rangeSq = range * range;
        for (ServerPlayerEntity player : core.server().getPlayerList().getPlayers()) {
            if (!FlanBridge.dimensionId(player.world).equals(record.dimension)) {
                continue;
            }
            double dx = player.getPosX() - (record.flagX + 0.5);
            double dy = player.getPosY() - record.flagY;
            double dz = player.getPosZ() - (record.flagZ + 0.5);
            if (dx * dx + dy * dy + dz * dz <= rangeSq) {
                viewers.add(player);
            }
        }
        return viewers;
    }

    public boolean cancel(UUID claimId) {
        CaptureSession session = sessions.remove(claimId);
        if (session != null) {
            session.close();
            return true;
        }
        return false;
    }

    public void clearAll() {
        Iterator<CaptureSession> it = sessions.values().iterator();
        while (it.hasNext()) {
            it.next().close();
            it.remove();
        }
    }

    public int activeCount() {
        return sessions.size();
    }

    /** Lines for /war capture: sessions on claims the player owns, or within 64 blocks. */
    public List<String> describeFor(ServerPlayerEntity player) {
        List<String> lines = new ArrayList<>();
        String dim = FlanBridge.dimensionId(player.world);
        for (CaptureSession session : sessions.values()) {
            ClaimRecord record = core.data().claim(session.claimId);
            if (record == null) {
                continue;
            }
            boolean own = player.getUniqueID().equals(record.owner);
            boolean near = dim.equals(record.dimension)
                    && player.getDistanceSq(record.flagX + 0.5, record.flagY, record.flagZ + 0.5) <= 64 * 64;
            if (own || near || player.hasPermissionLevel(Msg.adminLevel())) {
                lines.add(describe(session, record));
            }
        }
        return lines;
    }

    public List<String> describeAll() {
        List<String> lines = new ArrayList<>();
        for (CaptureSession session : sessions.values()) {
            ClaimRecord record = core.data().claim(session.claimId);
            if (record != null) {
                lines.add(describe(session, record));
            }
        }
        return lines;
    }

    private String describe(CaptureSession session, ClaimRecord record) {
        int percent = (int) Math.floor(session.model.fraction() * 100.0);
        UUID capturer = session.selector.designated();
        return record.claimId + " @ " + record.flagX + "," + record.flagY + "," + record.flagZ + " ("
                + record.dimension + "): " + session.model.status() + " " + percent + "%"
                + (capturer == null ? "" : " by " + FlanBridge.playerName(core.server(), capturer));
    }
}
