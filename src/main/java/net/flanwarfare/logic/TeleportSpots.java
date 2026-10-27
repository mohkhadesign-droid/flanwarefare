package net.flanwarfare.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Order in which the spots around a flag's base are tried as a claim-teleport destination
 * (pure; tested by LogicSelfTest).
 *
 * <p>Ring 1 (the 8 blocks around the base) first, then ring 2. Within a ring: the side away
 * from the banner first, then the two perpendicular sides, then the diagonals, the banner side
 * last; ties by distance. Each spot is tried at the base's own level first, then one block
 * lower, then one higher (the floor near the base, never a roof).
 */
public final class TeleportSpots {
    private TeleportSpots() {
    }

    /** Vertical offsets tried for each spot, in order. */
    public static final int[] LEVELS = {0, -1, 1};

    /**
     * Horizontal offsets {dx, dz} in the order to try.
     *
     * @param bannerDx x step from the base towards the banner side (-1, 0 or 1)
     * @param bannerDz z step from the base towards the banner side (-1, 0 or 1)
     */
    public static List<int[]> offsets(int bannerDx, int bannerDz) {
        List<int[]> out = new ArrayList<>();
        for (int ring = 1; ring <= 2; ring++) {
            List<int[]> spots = new ArrayList<>();
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        spots.add(new int[]{dx, dz});
                    }
                }
            }
            spots.sort((a, b) -> {
                int c = Double.compare(score(a, bannerDx, bannerDz), score(b, bannerDx, bannerDz));
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(a[0], b[0]);
                return c != 0 ? c : Integer.compare(a[1], b[1]);
            });
            out.addAll(spots);
        }
        return out;
    }

    /** Lower is better. */
    private static double score(int[] o, int bannerDx, int bannerDz) {
        int towardsBanner = Integer.signum(o[0] * bannerDx + o[1] * bannerDz); // >0: banner side
        boolean diagonal = o[0] != 0 && o[1] != 0;
        if (Math.max(Math.abs(o[0]), Math.abs(o[1])) == 1) {
            // away, perpendicular sides, away diagonals, banner diagonals, banner side
            if (!diagonal) {
                return towardsBanner < 0 ? 0 : towardsBanner == 0 ? 1 : 4;
            }
            return towardsBanner < 0 ? 2 : 3;
        }
        return Math.sqrt(o[0] * o[0] + o[1] * o[1]) + towardsBanner * 0.75;
    }
}
