package net.flanwarfare.capture;

import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.CaptureModel;
import net.flanwarfare.logic.CaptureZone;
import net.flanwarfare.util.Msg;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.particles.RedstoneParticleData;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Visible capture zone: a ring of vanilla dust particles on the zone boundary, at the flagpole
 * base's height. Every player within the visibility distance receives it, whatever their
 * relation to the claim (owner, defender, raider, neutral). Particles are sent per player, so
 * nothing is broadcast server-wide and no chunk is ever loaded (a player
 * being nearby means the chunk is loaded already). The ring uses exactly the configured
 * {@code capture.radius_blocks} and {@link CaptureZone}, the same as capture detection.
 *
 * <p>Colours: idle (grey), being captured or decaying (red), contested (yellow), recapture
 * immunity (blue), and a totem
 * burst when a claim is conquered. Flags of admin claims, excluded claims, disabled dimensions
 * and claims without a placed flag never get a ring.
 */
public final class CaptureAura {
    private final WarCore core;

    public CaptureAura(WarCore core) {
        this.core = core;
    }

    public void tick(long ticks) {
        if (!WarConfig.AURA_ENABLED.get() || ticks % WarConfig.AURA_INTERVAL_TICKS.get() != 0) {
            return;
        }
        boolean raid = core.raid().isActive();
        if (!raid && !WarConfig.AURA_OUTSIDE_RAIDS.get()) {
            return;
        }
        double radius = WarConfig.CAPTURE_RADIUS.get();
        double viewSq = Math.pow(WarConfig.AURA_DISTANCE.get(), 2);
        for (Map.Entry<String, List<ClaimRecord>> entry : core.capture().capturableFlagsByDimension().entrySet()) {
            ServerWorld world = FlanBridge.world(core.server(), entry.getKey());
            if (world == null) {
                continue;
            }
            List<ServerPlayerEntity> players = world.getPlayers();
            if (players.isEmpty()) {
                continue;
            }
            for (ClaimRecord record : entry.getValue()) {
                List<ServerPlayerEntity> viewers = new ArrayList<>();
                for (ServerPlayerEntity player : players) {
                    if (player.getDistanceSq(record.flagX + 0.5, record.flagY, record.flagZ + 0.5) <= viewSq) {
                        viewers.add(player);
                    }
                }
                if (viewers.isEmpty()) {
                    continue;
                }
                Claim claim = FlanBridge.find(core.server(), record);
                if (claim == null || claim.isAdminClaim() || core.isExcluded(claim)) {
                    continue;
                }
                CaptureModel.Status status = core.capture().statusOf(record.claimId);
                long immunity = record.immunityRemaining(System.currentTimeMillis());
                String color = immunity > 0
                        ? (WarConfig.AURA_SHOW_IMMUNE.get() ? WarConfig.AURA_IMMUNE_COLOR.get() : null)
                        : colorFor(status);
                if (color != null) {
                    drawRing(world, viewers, record, radius, color);
                }
                if (raid && WarConfig.AURA_ACTIONBAR.get()) {
                    distanceHints(viewers, record, radius, status, immunity);
                }
            }
        }
    }

    /** Ring colour for a state, or null if that state's ring is switched off. */
    private static String colorFor(CaptureModel.Status status) {
        if (status == CaptureModel.Status.CONTESTED) {
            return WarConfig.AURA_SHOW_CONTESTED.get() ? WarConfig.AURA_CONTESTED_COLOR.get() : null;
        }
        if (status == CaptureModel.Status.CAPTURING || status == CaptureModel.Status.DECAYING) {
            return WarConfig.AURA_SHOW_ACTIVE.get() ? WarConfig.AURA_ACTIVE_COLOR.get() : null;
        }
        return WarConfig.AURA_SHOW_IDLE.get() ? WarConfig.AURA_IDLE_COLOR.get() : null;
    }

    private static void drawRing(ServerWorld world, List<ServerPlayerEntity> viewers, ClaimRecord record,
                                 double radius, String color) {
        float[] rgb = WarConfig.rgb(color);
        RedstoneParticleData dust = new RedstoneParticleData(rgb[0], rgb[1], rgb[2],
                WarConfig.AURA_PARTICLE_SIZE.get().floatValue());
        int points = WarConfig.AURA_POINTS.get();
        double y = record.flagY + 0.15;
        for (int i = 0; i < points; i++) {
            double[] p = CaptureZone.ringPoint(record.flagX, record.flagZ, radius, i, points);
            for (ServerPlayerEntity viewer : viewers) {
                // longDistance=true: clients normally cull particles beyond 32 blocks.
                world.spawnParticle(viewer, dust, true, p[0], y, p[1], 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /** Action-bar distance readout for players at or near the zone edge. */
    private static void distanceHints(List<ServerPlayerEntity> viewers, ClaimRecord record, double radius,
                                      CaptureModel.Status status, long immunity) {
        for (ServerPlayerEntity player : viewers) {
            if (!CaptureManager.canTakePart(player)
                    || !CaptureZone.withinHeight(player.getPosY(), record.flagY, WarConfig.VERTICAL_TOLERANCE.get())) {
                continue;
            }
            double distance = CaptureZone.horizontalDistance(player.getPosX(), player.getPosZ(), record.flagX, record.flagZ);
            if (distance > radius + 2.0) {
                continue;
            }
            boolean inside = distance <= radius;
            if (immunity > 0) {
                Msg.actionBar(player, Msg.text(String.format(Locale.ROOT,
                        "Capture Zone - IMMUNE to conquest - recapture in %s", Msg.duration(immunity)), TextFormatting.AQUA));
                continue;
            }
            String text = String.format(Locale.ROOT, "Capture Zone - %.1f / %.1f blocks from flag%s", distance, radius,
                    inside ? (status == CaptureModel.Status.CONTESTED ? " - INSIDE (contested)" : " - INSIDE") : "");
            Msg.actionBar(player, Msg.text(text, inside ? TextFormatting.RED : TextFormatting.GRAY));
        }
    }

    /** Short burst on conquest, for players near the flag. */
    public void conquestEffect(ClaimRecord record) {
        if (!WarConfig.AURA_ENABLED.get() || !WarConfig.AURA_SHOW_COMPLETION.get() || !record.hasFlag) {
            return;
        }
        ServerWorld world = FlanBridge.world(core.server(), record.dimension);
        if (world == null) {
            return;
        }
        double viewSq = Math.pow(WarConfig.AURA_DISTANCE.get(), 2);
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.getDistanceSq(record.flagX + 0.5, record.flagY, record.flagZ + 0.5) <= viewSq) {
                world.spawnParticle(player, ParticleTypes.TOTEM_OF_UNDYING, true,
                        record.flagX + 0.5, record.flagY + 1.0 + record.poleHeight, record.flagZ + 0.5, 80, 0.6, 0.8, 0.6, 0.4);
            }
        }
    }
}
