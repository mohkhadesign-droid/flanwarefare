package net.flanwarfare.capture;

import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.CaptureModel;
import net.flanwarfare.logic.CaptureZone;
import net.flanwarfare.logic.DefenseRules;
import net.flanwarfare.logic.FlagAnimation;
import net.flanwarfare.flag.BannerDesigns;
import net.flanwarfare.team.SideResolver;
import net.flanwarfare.util.Msg;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.TextFormatting;

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
        final Set<UUID> defenders = new HashSet<>();
        final Set<UUID> allAttackers = new HashSet<>();
    }

    private static final long NOTICE_INTERVAL_MILLIS = 20_000L;
    private final Map<String, Long> notices = new HashMap<>();

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
        Map<UUID, Boolean> slots = new HashMap<>();
        long now = System.currentTimeMillis();
        double radius = WarConfig.CAPTURE_RADIUS.get();
        double tolerance = WarConfig.VERTICAL_TOLERANCE.get();
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
                        record.flagX, record.flagY, record.flagZ, radius, tolerance)) {
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
                UUID id = player.getUniqueID();
                if (role == SideResolver.Role.DEFENDER) {
                    p.everyone.add(id);
                    p.defenders.add(id);
                    continue;
                }
                // Attackers who cannot capture this flag right now are ignored (they neither
                // capture nor contest) and told why.
                long immunity = record.immunityRemaining(now);
                if (immunity > 0) {
                    if (!raidOpen) {
                        continue;
                    }
                    notice(player, record, "immune", () -> {
                        Msg.send(player, Msg.text("This territory is immune to conquest.", TextFormatting.AQUA));
                        Msg.send(player, Msg.text("Recapture available in: " + Msg.duration(immunity), TextFormatting.AQUA));
                    });
                    continue;
                }
                if (!slots.computeIfAbsent(id, core.conquest()::hasConquestSlot)) {
                    if (raidOpen) {
                        notice(player, record, "limit", () -> core.conquest().sendLimitMessage(player));
                    }
                    continue;
                }
                p.everyone.add(id);
                p.allAttackers.add(id);
                String side = core.sides().sideOf(id);
                p.attackerSides.add(side);
                p.attackersBySide.computeIfAbsent(side, s -> new HashSet<>()).add(id);
            }
        }

        Set<UUID> toVisit = new HashSet<>(sessions.keySet());
        toVisit.addAll(presence.keySet());
        for (UUID claimId : toVisit) {
            step(claimId, claims, presence.getOrDefault(claimId, NOBODY), raidOpen, windowId, interval);
        }
    }

    /** Sends a message to a player about a flag at most once per 20 seconds. */
    private void notice(ServerPlayerEntity player, ClaimRecord record, String kind, Runnable send) {
        String key = player.getUniqueID() + "|" + record.claimId + "|" + kind;
        long now = System.currentTimeMillis();
        Long last = notices.get(key);
        if (last == null || now - last >= NOTICE_INTERVAL_MILLIS) {
            notices.put(key, now);
            send.run();
        }
        if (notices.size() > 512) {
            notices.values().removeIf(t -> now - t > NOTICE_INTERVAL_MILLIS);
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
        boolean immune = record.immunityRemaining(System.currentTimeMillis()) > 0;
        if (session == null) {
            if (!raidOpen || !attackers || immune) {
                return;
            }
            session = open(record, windowId);
        } else if (raidOpen && session.windowId != windowId && attackers) {
            // Progress from an earlier raid window is never reused: start fresh.
            close(claimId, End.SILENT, null, null);
            session = open(record, windowId);
        }

        long captureTicks = WarConfig.CAPTURE_SECONDS.get() * 20L;
        long decayTicks = WarConfig.DECAY_SECONDS.get() * 20L;
        if (session.model.captureTicks() != captureTicks || session.model.decayTicks() != decayTicks) {
            // Global capture time changed: keep the percentage, rescale the remaining time.
            session.model.rescale(captureTicks, decayTicks);
        }
        boolean progressAllowed = raidOpen && session.windowId == windowId && !immune;
        CaptureModel.Status before = session.model.status();
        CaptureModel.Status status = session.model.step(dt, progressAllowed, presence.attackerSides,
                !presence.defenders.isEmpty(), WarConfig.CONTEST_BY_DEFENDERS.get(), WarConfig.CONTEST_BY_RIVALS.get());
        session.peakFraction = Math.max(session.peakFraction, session.model.fraction());
        session.attackersSeen.addAll(presence.allAttackers);
        if (progressAllowed && attackers && !presence.defenders.isEmpty()) {
            session.contestingDefenders.addAll(presence.defenders);
        }
        if (!progressAllowed && attackers) {
            session.attackersStayedAfterRaid = true;
        }
        String side = session.model.side();
        Set<UUID> sidePlayers = side == null ? Collections.<UUID>emptySet()
                : presence.attackersBySide.getOrDefault(side, Collections.<UUID>emptySet());
        session.selector.update(core.ticks(), side, sidePlayers);

        animate(session, record, status);

        int percent = (int) Math.floor(session.model.fraction() * 100.0);
        if (!session.startAlertSent && progressAllowed && attackers
                && (status == CaptureModel.Status.CAPTURING || status == CaptureModel.Status.CONTESTED)) {
            // An enemy is at the flag: alert even if a defender is already there.
            session.startAlertSent = true;
            core.alerts().captureStarted(claim, record, percent);
        } else if (status == CaptureModel.Status.CONTESTED && before != CaptureModel.Status.CONTESTED
                && session.startAlertSent) {
            core.alerts().captureContested(claim, record, percent, !presence.defenders.isEmpty());
        }

        if (status == CaptureModel.Status.COMPLETE) {
            UUID winner = session.selector.designated();
            boolean conquered = winner != null && progressAllowed
                    && core.conquest().complete(claim, record, winner, presence.everyone, sidePlayers);
            close(claimId, conquered ? End.CONQUERED : End.FAILED, claim, record);
            return;
        }
        if (session.model.isFinished() && presence.everyone.isEmpty()) {
            close(claimId, End.FAILED, claim, record);
            return;
        }
        session.hud.noteAttackers(presence.attackerSides);
        session.hud.update(claim, record, status, viewers(record), core.ticks());
    }

    /**
     * Flagpole animation, derived only from the authoritative capture fraction: the owner's
     * banner descends to the midpoint, switches to the designated capturer's design, and the
     * attacker's banner rises. Decay runs it backwards; contesting freezes it (no change).
     */
    private void animate(CaptureSession session, ClaimRecord record, CaptureModel.Status status) {
        if (record.poleHeight <= 0 || !WarConfig.POLE_ANIMATED.get()) {
            return;
        }
        if (status == CaptureModel.Status.DECAYING && !WarConfig.POLE_REVERSE_ON_DECAY.get()) {
            return;
        }
        UUID capturer = session.selector.designated();
        if (capturer != null) {
            session.attackerDesign = core.flags().designFor(capturer);
        }
        FlagAnimation.Frame frame = FlagAnimation.frame(session.model.fraction(), WarConfig.POLE_MIDPOINT.get(),
                net.flanwarfare.flag.FlagStructure.of(record).animationLevels());
        String design = record.designSnbt;
        if (frame.attacker) {
            design = session.attackerDesign != null ? session.attackerDesign : BannerDesigns.defaultSnbt();
        }
        core.flags().setVisual(record, frame.level, design);
    }

    private CaptureSession open(ClaimRecord record, long windowId) {
        CaptureSession session = new CaptureSession(record.claimId, record.dimension, windowId,
                WarConfig.CAPTURE_SECONDS.get() * 20L, WarConfig.DECAY_SECONDS.get() * 20L);
        session.hud = new CaptureHud(core, session);
        sessions.put(record.claimId, session);
        return session;
    }

    private void close(UUID claimId, End reason, Claim claim, ClaimRecord record) {
        CaptureSession session = sessions.remove(claimId);
        if (session == null) {
            return;
        }
        session.close();
        ClaimRecord visualRecord = record != null ? record : core.data().claim(claimId);
        if (visualRecord != null) {
            core.flags().clearVisual(visualRecord);
        }
        if (reason == End.FAILED && claim != null && record != null) {
            creditDefense(session, claim, record);
        }
        if (session.startAlertSent && claim != null && record != null) {
            if (reason == End.FAILED) {
                core.alerts().captureFailed(claim, record);
            } else if (reason == End.CANCELLED) {
                core.alerts().captureCancelled(claim, record);
            }
        }
    }

    /** Statistics only: a capture that decayed to zero after defenders contested it. */
    private void creditDefense(CaptureSession session, Claim claim, ClaimRecord record) {
        if (!core.stats().defensesTracked()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean qualifies = DefenseRules.qualifies(session.peakFraction, WarConfig.DEFENSE_MIN_PERCENT.get(),
                session.contestingDefenders.size(), session.attackersStayedAfterRaid,
                core.stats().lastDefenseAt(record.claimId), now, WarConfig.DEFENSE_COOLDOWN_MINUTES.get() * 60_000L);
        if (qualifies) {
            core.stats().recordDefense(claim, record, session.contestingDefenders, session.attackersSeen,
                    (int) Math.floor(session.peakFraction * 100.0));
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

    /** Server stopping: end every session and put every animated banner back on top. */
    public void clearAll() {
        Iterator<CaptureSession> it = sessions.values().iterator();
        while (it.hasNext()) {
            CaptureSession session = it.next();
            session.close();
            ClaimRecord record = core.data().claim(session.claimId);
            if (record != null) {
                core.flags().clearVisual(record);
            }
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
