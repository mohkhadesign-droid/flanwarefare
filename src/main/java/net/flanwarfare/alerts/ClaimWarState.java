package net.flanwarfare.alerts;

import net.flanwarfare.logic.CaptureModel;

/**
 * Live warfare state of one claim, derived on demand (not persisted) from three independent
 * sources, so a raid window alone never marks a claim as attacked and breaking blocks never
 * counts as capturing:
 * <ol>
 *   <li>the capture session of its flag (CAPTURE_*), which takes precedence;</li>
 *   <li>recent hostile raid actions within the alert cooldown (RAID_ACTIVITY);</li>
 *   <li>the persistent conquered flag (CONQUERED);</li>
 * </ol>
 * otherwise PEACEFUL.
 */
public enum ClaimWarState {
    PEACEFUL, RAID_ACTIVITY, CAPTURE_ACTIVE, CAPTURE_CONTESTED, CAPTURE_DECAYING, CONQUERED;

    public static ClaimWarState of(CaptureModel.Status capture, boolean recentlyAttacked, boolean conquered) {
        if (capture == CaptureModel.Status.CONTESTED) {
            return CAPTURE_CONTESTED;
        }
        if (capture == CaptureModel.Status.CAPTURING || capture == CaptureModel.Status.COMPLETE) {
            return CAPTURE_ACTIVE;
        }
        if (capture == CaptureModel.Status.DECAYING) {
            return CAPTURE_DECAYING;
        }
        if (recentlyAttacked) {
            return RAID_ACTIVITY;
        }
        return conquered ? CONQUERED : PEACEFUL;
    }
}
