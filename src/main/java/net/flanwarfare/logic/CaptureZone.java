package net.flanwarfare.logic;

/**
 * The one authoritative capture-zone geometry, shared by capture detection, defender
 * contesting, the particle aura and the action-bar distance. The zone is a vertical cylinder:
 * horizontal distance from the centre of the flag block at most {@code radius}, and feet
 * between 1 block below and {@code radius + 1} blocks above the flag's base.
 */
public final class CaptureZone {
    private CaptureZone() {
    }

    /** Horizontal distance from the centre of the flag block at (flagX, flagZ). */
    public static double horizontalDistance(double x, double z, int flagX, int flagZ) {
        double dx = x - (flagX + 0.5);
        double dz = z - (flagZ + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static boolean withinHeight(double y, int flagY, double radius) {
        double dy = y - flagY;
        return dy >= -1.0 && dy <= radius + 1.0;
    }

    public static boolean inside(double x, double y, double z, int flagX, int flagY, int flagZ, double radius) {
        return withinHeight(y, flagY, radius) && horizontalDistance(x, z, flagX, flagZ) <= radius;
    }

    /** Point {@code index} of {@code points} evenly spaced on the zone boundary: {x, z}. */
    public static double[] ringPoint(int flagX, int flagZ, double radius, int index, int points) {
        double angle = 2.0 * Math.PI * index / points;
        return new double[]{flagX + 0.5 + radius * Math.cos(angle), flagZ + 0.5 + radius * Math.sin(angle)};
    }
}
