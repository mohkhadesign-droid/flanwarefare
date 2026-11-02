package net.grandexpedition.kingdomspawn.logic;

import java.util.Arrays;

/**
 * The order in which biome samples are taken: a regular grid over the search area, visited from
 * the farthest point to the nearest (horizontal Euclidean distance from 0,0), so the outer
 * regions, corners included, are searched first and the search moves inward in bands.
 * Pure; tested by LogicSelfTest.
 */
public final class SearchPlan {
    private final int[] xs;
    private final int[] zs;
    private final int[] distances;

    private SearchPlan(int[] xs, int[] zs, int[] distances) {
        this.xs = xs;
        this.zs = zs;
        this.distances = distances;
    }

    /**
     * @param minX    inclusive search bound (safety margin already applied)
     * @param maxX    inclusive search bound
     * @param minZ    inclusive search bound
     * @param maxZ    inclusive search bound
     * @param spacing distance between samples, in blocks (at least 16)
     */
    public static SearchPlan create(int minX, int maxX, int minZ, int maxZ, int spacing) {
        if (maxX < minX || maxZ < minZ) {
            throw new IllegalArgumentException("empty search area");
        }
        int step = Math.max(16, spacing);
        // Evenly spread from edge to edge (both bounds included), about `step` apart.
        int nx = Math.max(1, (int) Math.round((maxX - minX) / (double) step)) + 1;
        int nz = Math.max(1, (int) Math.round((maxZ - minZ) / (double) step)) + 1;
        long total = (long) nx * nz;
        if (total > 20_000_000L) {
            throw new IllegalArgumentException("too many samples (" + total + "): raise sample_spacing");
        }
        int n = (int) total;
        long[] keys = new long[n];
        int i = 0;
        for (int a = 0; a < nx; a++) {
            for (int b = 0; b < nz; b++) {
                int x = coord(minX, maxX, a, nx);
                int z = coord(minZ, maxZ, b, nz);
                int d = (int) Math.round(Math.sqrt((double) x * x + (double) z * z));
                // Sort key: farthest first (negated distance), then by index for a stable order.
                keys[i] = ((long) (Integer.MAX_VALUE - d) << 32) | (a * (long) nz + b);
                i++;
            }
        }
        Arrays.sort(keys);
        int[] xs = new int[n];
        int[] zs = new int[n];
        int[] ds = new int[n];
        for (int k = 0; k < n; k++) {
            long idx = keys[k] & 0xFFFFFFFFL;
            int a = (int) (idx / nz);
            int b = (int) (idx % nz);
            xs[k] = coord(minX, maxX, a, nx);
            zs[k] = coord(minZ, maxZ, b, nz);
            ds[k] = Integer.MAX_VALUE - (int) (keys[k] >>> 32);
        }
        return new SearchPlan(xs, zs, ds);
    }

    private static int coord(int min, int max, int i, int n) {
        return n <= 1 ? min : (int) Math.round(min + (max - min) * (double) i / (n - 1));
    }

    public int size() {
        return xs.length;
    }

    public int x(int i) {
        return xs[i];
    }

    public int z(int i) {
        return zs[i];
    }

    /** Distance of sample i from 0,0 (rounded). Non-increasing with i. */
    public int distance(int i) {
        return distances[i];
    }

    /** True if sample i is the last one of its distance band (or the last sample). */
    public boolean endsBand(int i, int bandWidth) {
        if (i >= xs.length - 1) {
            return true;
        }
        int w = Math.max(1, bandWidth);
        return distances[i] / w != distances[i + 1] / w;
    }
}
