package net.flanwarfare.capture;

import net.flanwarfare.logic.CaptureModel;
import net.flanwarfare.logic.CapturerSelector;

import java.util.UUID;

/** In-memory capture state for one flag. Never persisted: a restart cancels it (by design). */
final class CaptureSession {
    final UUID claimId;
    final String dimension;
    final long windowId;
    final CaptureModel model;
    final CapturerSelector selector = new CapturerSelector();
    /** Viewer-specific HUD (client panel or per-perspective boss bars). */
    CaptureHud hud;
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

    CaptureSession(UUID claimId, String dimension, long windowId, long captureTicks, long decayTicks) {
        this.claimId = claimId;
        this.dimension = dimension;
        this.windowId = windowId;
        this.model = new CaptureModel(captureTicks, decayTicks);
    }

    void close() {
        if (hud != null) {
            hud.close();
        }
    }
}
