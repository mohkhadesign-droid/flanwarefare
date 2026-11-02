package net.grandexpedition.kingdomspawn.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Individual arrival spots around a kingdom's deployment centre (pure; tested by LogicSelfTest):
 * integer offsets whose horizontal distance lies between {@code rMin} and {@code rMax}. The
 * first eight are spread evenly around the centre at the middle radius, so the first members
 * of a kingdom do not land on top of each other; the rest follow ring by ring.
 */
public final class Slots {
    private Slots() {
    }

    public static List<int[]> offsets(int rMin, int rMax) {
        int lo = Math.max(0, Math.min(rMin, rMax));
        int hi = Math.max(lo, Math.max(rMin, rMax));
        List<int[]> out = new ArrayList<>();
        double mid = (lo + hi) / 2.0;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            int dx = (int) Math.round(Math.cos(a) * mid);
            int dz = (int) Math.round(Math.sin(a) * mid);
            double r = Math.sqrt(dx * dx + dz * dz);
            if (r >= lo && r <= hi && !contains(out, dx, dz)) {
                out.add(new int[]{dx, dz});
            }
        }
        List<int[]> rest = new ArrayList<>();
        for (int dx = -hi; dx <= hi; dx++) {
            for (int dz = -hi; dz <= hi; dz++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r >= lo && r <= hi && !contains(out, dx, dz)) {
                    rest.add(new int[]{dx, dz});
                }
            }
        }
        rest.sort((a, b) -> {
            int c = Double.compare(Math.abs(Math.sqrt(a[0] * a[0] + a[1] * a[1]) - mid),
                    Math.abs(Math.sqrt(b[0] * b[0] + b[1] * b[1]) - mid));
            if (c != 0) {
                return c;
            }
            return Double.compare(Math.atan2(a[1], a[0]), Math.atan2(b[1], b[0]));
        });
        out.addAll(rest);
        return out;
    }

    private static boolean contains(List<int[]> list, int dx, int dz) {
        for (int[] o : list) {
            if (o[0] == dx && o[1] == dz) {
                return true;
            }
        }
        return false;
    }
}
