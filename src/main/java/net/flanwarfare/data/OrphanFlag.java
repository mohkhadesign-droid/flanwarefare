package net.flanwarfare.data;

/** A flag block whose claim no longer exists; removed as soon as its chunk is loaded. */
public final class OrphanFlag {
    public String dimension;
    public int x;
    public int y;
    public int z;

    public OrphanFlag() {
    }

    public OrphanFlag(String dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
