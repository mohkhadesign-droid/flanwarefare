package net.flanwarfare.capture;

import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.CaptureModel;
import net.flanwarfare.logic.CaptureZone;
import net.flanwarfare.team.SideResolver;
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
 * Evaluates every capture zone on a fixed interval (default every 10 ticks) using only
 * server-side positions and {@link CaptureZone}. Cost per evaluation is (online players x flags
 * in their dimension); no blocks are scanned and no chunks are loaded.
 *
 * <p>Presence is recomputed from scratch at every evaluation, so a defender who dies, logs out,
 * changes dimension, teleports away or turns spectator stops contesting at the next evaluation,
 * and an attacker and a defender entering on the same evaluation always yield CONTESTED.
 */
public final class CaptureManager {

    /** Why a session ended; decides which alert (if any) the owner gets. */
    private enum End { FAILED, CONQUERED, CANCELLED, SILENT }

    private static final class Presence {
        final Set<String> attackerSides = new HashSet<>();
        final Map<String, Set<UUID>> attackersBySide = new HashMap<>();
        final Set<UUID> everyone = new HashSet<>();
        int defenders;
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

        Map<String, List<ClaimRecord>> flagsByDimension = capturableFlagsByDimension();
        if (flagsByDimension.isEmpty() && sessions.isEmpty()) {
            return;
        }

        Map<UUID, Claim> claims = new HashMap<>();
        Map<UUID, Presence> presence = new HashMap<>();
        double radius = WarConfig.CAPTURE_RADIUS.get();
        for (ServerPlayerEntity player : core.server().getPlayerList().getPlayers()) {
            if (!canTakePart(player)) {
                continue;
            }
            List<ClaimRecord> flags = flagsByDimension.get(FlanBridge.dimensionId(player.world));
            if (flags == null) {
                continue;
            }
            for (ClaimRecord record : flags) {
                if (!CaptureZone.inside(player.getPosX(), player.getPosY(), player.getPosZ(),
                        record.flagX, record.flagY, record.flagZ, radius)) {
                    continue;
                }
                Claim claim = claims.computeIfAbsent(record.claimId, id -> FlanBridge.find(core.server(), record));
                if (claim == null || claim.isAdminClaim()) {
                    continue;
                }
                Presence p = presence.computeIfAbsent(record.claimId, id -> new Presence());
                SideResolver.Role role = core.sides().roleOf(player, claim);
                if (role == SideResolver.Role.NEUTRAL) {
                    continue;
                }
                p.everyone.add(player.getUniqueID());
                if (role == SideResolver.Role.DEFENDER) {
                    p.defenders++;
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

    /** Flags whose zone is live: placed, ACTIVE/RECOVERY claim, enabled dimension. */
    Map<String, List<ClaimRecord>> capturableFlagsByDimension() {
        Map<String, List<ClaimRecord>> byDimension = new HashMap<>();
        for (ClaimRecord record : core.data().claims().values()) {
            if (record.hasFlag && record.isWarEnabled() && !core.isDimensionDisabled(record.dimension)) {
                byDimension.computeIfAbsent(record.dimension, d -> new ArrayList<>()).add(record);
            }
        }
        return byDimension;
    }

    /** Online, alive, not spectating, and not creative unless configured. */
    public static boolean canTakePart(ServerPlayerEntity player) {
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
            close(claimId, End.SILENT, null, null);
            return;
        }
        CaptureSession session = sessions.get(claimId);
        boolean attackers = !presence.attackerSides.isEmpty();
        if (session == null) {
            if (!raidOpen || !attackers) {
                return;
            }
            session = open(record, windowId);
        } else if (raidOpen && session.windowId != windowId && attackers) {
            // Progress from an earlier raid window is never reused: start fresh.
            close(claimId, End.SILENT, null, null);
            session = open(record, windowId);
        }

        boolean progressAllowed = raidOpen && session.windowId == windowId;
        CaptureModel.Status before = session.model.status();
        CaptureModel.Status status = session.model.step(dt, progressAllowed, presence.attackerSides,
                presence.defenders > 0, WarConfig.CONTEST_BY_DEFENDERS.get(), WarConfig.CONTEST_BY_RIVALS.get());
        String side = session.model.side();
        Set<UUID> sidePlayers = side == null ? Collections.<UUID>emptySet()
                : presence.attackersBySide.getOrDefault(side, Collections.<UUID>emptySet());
        session.selector.update(core.ticks(), side, sidePlayers);

        int percent = (int) Math.floor(session.model.fraction() * 100.0);
        if (!session.startAlertSent && progressAllowed && attackers
                && (status == CaptureModel.Status.CAPTURING || status == CaptureModel.Status.CONTESTED)) {
            // An enemy is at the flag: alert even if a defender is already there.
            session.startAlertSent = true;
            core.alerts().captureStarted(claim, record, percent);
        } else if (status == CaptureModel.Status.CONTESTED && before != CaptureModel.Status.CONTESTED
                && session.startAlertSent) {
            core.alerts().captureContested(claim, record, percent, presence.defenders > 0);
        }

        if (status == CaptureModel.Status.COMPLETE) {
            UUID winner = session.selector.designated();
            boolean conquered = winner != null && progressAllowed
                    && core.conquest().complete(claim, record, winner, presence.everyone);
            close(claimId, conquered ? End.CONQUERED : End.FAILED, claim, record);
            return;
        }
        if (session.model.isFinished() && presence.everyone.isEmpty()) {
            close(claimId, End.FAILED, claim, record);
            return;
        }
        session.updateBar(title(claim, session, status, progressAllowed), viewers(record));
    }

    private CaptureSession open(ClaimRecord record, long windowId) {
        CaptureSession session = new CaptureSession(record.claimId, record.dimension, windowId,
                WarConfig.CAPTURE_SECONDS.get() * 20L, WarConfig.DECAY_SECONDS.get() * 20L);
        sessions.put(record.claimId, session);
        return session;
    }

    private void close(UUID claimId, End reason, Claim claim, ClaimRecord record) {
        CaptureSession session = sessions.remove(claimId);
        if (session == null) {
            return;
        }
        session.close();
        if (session.startAlertSent && claim != null && record != null) {
            if (reason == End.FAILED) {
                core.alerts().captureFailed(claim, record);
            } else if (reason == End.CANCELLED) {
                core.alerts().captureCancelled(claim, record);
            }
        }
    }

    private String title(Claim claim, CaptureSession session, CaptureModel.Status status, boolean progressAllowed) {
        int percent = (int) Math.floor(session.model.fraction() * 100.0);
        String state;
        switch (status) {
            case CONTESTED:
                state = "CONTESTED";
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
            if (player.getDistanceSq(record.flagX + 0.5, record.flagY, record.flagZ + 0.5) <= rangeSq) {
                viewers.add(player);
            }
        }
        return viewers;
    }

    /** Current capture status of a claim's flag, or null if no capture session exists. */
    public CaptureModel.Status statusOf(UUID claimId) {
        CaptureSession session = sessions.get(claimId);
        return session == null ? null : session.model.status();
    }

    public int percentOf(UUID claimId) {
        CaptureSession session = sessions.get(claimId);
        return session == null ? 0 : (int) Math.floor(session.model.fraction() * 100.0);
    }

    /** Administrator cancellation (tells the owner the attempt ended). */
    public boolean cancelByAdmin(UUID claimId) {
        if (!sessions.containsKey(claimId)) {
            return false;
        }
        ClaimRecord record = core.data().claim(claimId);
        Claim claim = record == null ? null : FlanBridge.find(core.server(), record);
        close(claimId, End.CANCELLED, claim, record);
        return true;
    }

    /** Silent cancellation (claim deleted, record removed). */
    public boolean cancel(UUID claimId) {
        boolean had = sessions.containsKey(claimId);
        close(claimId, End.SILENT, null, null);
        return had;
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

    private String describe(CaptureSession session, ClaimRecord record) {
        int percent = (int) Math.floor(session.model.fraction() * 100.0);
        UUID capturer = session.selector.designated();
        return record.claimId + " @ " + record.flagX + "," + record.flagY + "," + record.flagZ + " ("
                + record.dimension + "): " + session.model.status() + " " + percent + "%"
                + (capturer == null ? "" : " by " + FlanBridge.playerName(core.server(), capturer));
    }
}
