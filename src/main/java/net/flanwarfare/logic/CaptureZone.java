package net.flanwarfare.logic;

/**
 * The one authoritative capture-zone geometry, shared by capture detection, defender
 * contesting, the particle aura and the action-bar distance. The zone is a vertical cylinder
 * centred on the flagpole's ground-level base: horizontal distance from the centre of the base
 * block at most {@code radius}, and feet within {@code verticalTolerance} blocks above or below
 * the base (so nobody captures from a tunnel underneath or a roof far above).
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

    public static boolean withinHeight(double y, int baseY, double verticalTolerance) {
        return Math.abs(y - baseY) <= verticalTolerance;
    }

    public static boolean inside(double x, double y, double z, int baseX, int baseY, int baseZ, double radius,
                                 double verticalTolerance) {
        return withinHeight(y, baseY, verticalTolerance) && horizontalDistance(x, z, baseX, baseZ) <= radius;
    }

    /** Point {@code index} of {@code points} evenly spaced on the zone boundary: {x, z}. */
    public static double[] ringPoint(int flagX, int flagZ, double radius, int index, int points) {
        double angle = 2.0 * Math.PI * index / points;
        return new double[]{flagX + 0.5 + radius * Math.cos(angle), flagZ + 0.5 + radius * Math.sin(angle)};
    }
}
