package net.grandexpedition.kingdomspawn.logic;

/** Search area after the safety margin (pure; tested by LogicSelfTest). */
public final class Bounds {
    public final int minX;
    public final int maxX;
    public final int minZ;
    public final int maxZ;

    public Bounds(int minX, int maxX, int minZ, int maxZ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    /** The configured bounds shrunk by {@code margin} on every side; null if nothing is left. */
    public static Bounds withMargin(int minX, int maxX, int minZ, int maxZ, int margin) {
        int m = Math.max(0, margin);
        Bounds b = new Bounds(Math.min(minX, maxX) + m, Math.max(minX, maxX) - m,
                Math.min(minZ, maxZ) + m, Math.max(minZ, maxZ) - m);
        return b.minX > b.maxX || b.minZ > b.maxZ ? null : b;
    }

    public boolean contains(double x, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
