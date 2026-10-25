package net.flanwarfare.capture;

import net.flanwarfare.logic.CaptureModel;
import net.flanwarfare.logic.CapturerSelector;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.BossInfo;
import net.minecraft.world.server.ServerBossInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** In-memory capture state for one flag. Never persisted: a restart cancels it (by design). */
final class CaptureSession {
    final UUID claimId;
    final String dimension;
    final long windowId;
    final CaptureModel model;
    final CapturerSelector selector = new CapturerSelector();
    final ServerBossInfo bar;
    /** Owner was told this capture started (start alert is sent once real progress begins). */
    boolean startAlertSent;

    // Defense statistics (do not affect the capture itself).
    double peakFraction;
    final java.util.Set<UUID> contestingDefenders = new java.util.LinkedHashSet<>();
    final java.util.Set<UUID> attackersSeen = new java.util.LinkedHashSet<>();
    /** An attacker was still in the zone after progress stopped being allowed (raid over). */
    boolean attackersStayedAfterRaid;
    /** Design of the latest designated capturer, shown after the animation midpoint. */
    String attackerDesign;
    private String lastTitle = "";
    private int lastPercent = -1;
    private CaptureModel.Status lastStatus;

    CaptureSession(UUID claimId, String dimension, long windowId, long captureTicks, long decayTicks) {
        this.claimId = claimId;
        this.dimension = dimension;
        this.windowId = windowId;
        this.model = new CaptureModel(captureTicks, decayTicks);
        this.bar = new ServerBossInfo(new StringTextComponent("Capture"), BossInfo.Color.RED, BossInfo.Overlay.NOTCHED_10);
    }

    /** Updates the boss bar only when what it shows has changed, to avoid packet spam. */
    void updateBar(String title, Set<ServerPlayerEntity> viewers) {
        int percent = (int) Math.floor(model.fraction() * 100.0);
        if (!title.equals(lastTitle)) {
            bar.setName(new StringTextComponent(title));
            lastTitle = title;
        }
        if (percent != lastPercent) {
            bar.setPercent((float) model.fraction());
            lastPercent = percent;
        }
        if (model.status() != lastStatus) {
            switch (model.status()) {
                case CONTESTED:
                    bar.setColor(BossInfo.Color.YELLOW);
                    break;
                case DECAYING:
                case IDLE:
                    bar.setColor(BossInfo.Color.WHITE);
                    break;
                default:
                    bar.setColor(BossInfo.Color.RED);
                    break;
            }
            lastStatus = model.status();
        }
        Set<ServerPlayerEntity> current = new HashSet<>(bar.getPlayers());
        for (ServerPlayerEntity player : current) {
            if (!viewers.contains(player)) {
                bar.removePlayer(player);
            }
        }
        for (ServerPlayerEntity player : viewers) {
            if (!current.contains(player)) {
                bar.addPlayer(player);
            }
        }
    }

    void close() {
        bar.removeAllPlayers();
        bar.setVisible(false);
    }
}
