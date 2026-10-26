package net.flanwarfare.logic;

/**
 * Viewer-specific presentation of one capture (pure; tested by LogicSelfTest).
 *
 * <p>The bar always measures the attacker's progress towards capturing the claim; every viewer
 * sees the same percentage and fill. Only the wording and the colour depend on who looks: green
 * when the situation is good for the viewer, red when it is bad, gold while contested. Arrows
 * show the direction of the shared progress and never flip with the viewer.
 */
public final class HudView {
    private HudView() {
    }

    public enum Perspective { ATTACKER, DEFENDER, NEUTRAL }

    public enum Trend { INCREASING, CONTESTED, DECREASING }

    public enum Tone { GOOD, BAD, PAUSED, NEUTRAL }

    public enum Arrow { RIGHT, PAUSE, LEFT }

    /** From the authoritative capture state (never from a percentage that happens not to move). */
    public static Trend trendOf(CaptureModel.Status status) {
        if (status == CaptureModel.Status.CAPTURING) {
            return Trend.INCREASING;
        }
        if (status == CaptureModel.Status.CONTESTED) {
            return Trend.CONTESTED;
        }
        return Trend.DECREASING;
    }

    public static Tone tone(Perspective p, Trend t) {
        if (t == Trend.CONTESTED) {
            return Tone.PAUSED;
        }
        if (p == Perspective.NEUTRAL) {
            return Tone.NEUTRAL;
        }
        boolean attackerGains = t == Trend.INCREASING;
        return (p == Perspective.ATTACKER) == attackerGains ? Tone.GOOD : Tone.BAD;
    }

    public static Arrow arrow(Trend t) {
        return t == Trend.INCREASING ? Arrow.RIGHT : t == Trend.CONTESTED ? Arrow.PAUSE : Arrow.LEFT;
    }

    public static String status(Perspective p, Trend t) {
        if (t == Trend.CONTESTED) {
            return "CONTESTED";
        }
        switch (p) {
            case ATTACKER:
                return t == Trend.INCREASING ? "CAPTURING" : "LOSING PROGRESS";
            case DEFENDER:
                return t == Trend.INCREASING ? "LOSING CLAIM" : "DEFENDING";
            default:
                return t == Trend.INCREASING ? "CAPTURE IN PROGRESS" : "CAPTURE FADING";
        }
    }

    public static String subtitle(Perspective p, Trend t) {
        if (t == Trend.CONTESTED) {
            return "Capture progress paused";
        }
        String dir = t == Trend.INCREASING ? "increasing" : "decreasing";
        switch (p) {
            case ATTACKER:
                return "Your capture progress is " + dir;
            case DEFENDER:
                return "Enemy capture progress is " + dir;
            default:
                return "Capture progress is " + dir;
        }
    }

    /** Filled width in pixels for a bar of {@code width}: exact, left-anchored, clamped. */
    public static int fill(double fraction, int width) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        return (int) Math.round(f * width);
    }
}
