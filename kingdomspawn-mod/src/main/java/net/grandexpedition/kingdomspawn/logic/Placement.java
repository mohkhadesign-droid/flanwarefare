package net.grandexpedition.kingdomspawn.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Chooses one deployment candidate per kingdom, all kingdoms together (pure; tested by
 * LogicSelfTest).
 *
 * <p>A combination is valid when every pair of kingdoms, including kingdoms that already have
 * an approved location ({@code fixed}), is at least {@code minSeparation} blocks apart. Among
 * valid combinations the one with the highest score wins: the sum of the chosen points'
 * distances from 0,0 (outer regions first) plus the smallest pairwise separation (spread out,
 * which in practice means different directions).
 *
 * <p>To keep the search small, each kingdom's candidates are first reduced to the farthest few
 * per compass sector, so directions stay represented.
 */
public final class Placement {
    private Placement() {
    }

    /** A candidate deployment centre. */
    public static final class Candidate {
        public final int x;
        public final int z;
        public final double distance;

        public Candidate(int x, int z) {
            this.x = x;
            this.z = z;
            this.distance = Math.sqrt((double) x * x + (double) z * z);
        }

        @Override
        public String toString() {
            return "(" + x + ", " + z + ")";
        }
    }

    /** The outcome: one candidate per kingdom, or null with diagnostics. */
    public static final class Result {
        /** Chosen candidate per kingdom (same order as the input), or null if none is valid. */
        public final List<Candidate> chosen;
        /** Best achievable smallest pairwise separation ignoring the minimum (for reports). */
        public final double bestPossibleSeparation;

        Result(List<Candidate> chosen, double bestPossibleSeparation) {
            this.chosen = chosen;
            this.bestPossibleSeparation = bestPossibleSeparation;
        }
    }

    public static double separation(Candidate a, Candidate b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Compass sector (0..sectors-1) of a point around 0,0. */
    public static int sector(Candidate c, int sectors) {
        double angle = Math.atan2(c.z, c.x); // -pi..pi
        int s = (int) Math.floor((angle + Math.PI) / (2 * Math.PI) * sectors);
        return Math.max(0, Math.min(sectors - 1, s));
    }

    /** The farthest {@code perSector} candidates in each of {@code sectors} directions. */
    public static List<Candidate> diversify(List<Candidate> candidates, int sectors, int perSector) {
        List<List<Candidate>> buckets = new ArrayList<>();
        for (int i = 0; i < sectors; i++) {
            buckets.add(new ArrayList<Candidate>());
        }
        for (Candidate c : candidates) {
            buckets.get(sector(c, sectors)).add(c);
        }
        List<Candidate> out = new ArrayList<>();
        Comparator<Candidate> farthest = Comparator.comparingDouble((Candidate c) -> -c.distance);
        for (List<Candidate> bucket : buckets) {
            bucket.sort(farthest);
            out.addAll(bucket.subList(0, Math.min(perSector, bucket.size())));
        }
        out.sort(farthest);
        return out;
    }

    /**
     * @param perKingdom    candidates per searched kingdom (already diversified)
     * @param fixed         approved locations of kingdoms not being searched
     * @param minSeparation minimum distance between any two kingdoms
     */
    public static Result choose(List<List<Candidate>> perKingdom, List<Candidate> fixed, double minSeparation) {
        int k = perKingdom.size();
        for (List<Candidate> list : perKingdom) {
            if (list.isEmpty()) {
                return new Result(null, 0);
            }
        }
        Candidate[] pick = new Candidate[k];
        Search s = new Search(perKingdom, fixed, minSeparation, k);
        s.run(0, pick, Double.MAX_VALUE, 0);
        return new Result(s.best == null ? null : s.best, s.bestSep);
    }

    private static final class Search {
        final List<List<Candidate>> lists;
        final List<Candidate> fixed;
        final double min;
        final int k;
        List<Candidate> best;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestSep;

        Search(List<List<Candidate>> lists, List<Candidate> fixed, double min, int k) {
            this.lists = lists;
            this.fixed = fixed;
            this.min = min;
            this.k = k;
            // Separation among the fixed kingdoms alone bounds everything.
        }

        void run(int depth, Candidate[] pick, double minPair, double distSum) {
            if (depth == k) {
                bestSep = Math.max(bestSep, minPair == Double.MAX_VALUE ? 0 : minPair);
                if (minPair >= min) {
                    double score = distSum + (minPair == Double.MAX_VALUE ? 0 : minPair);
                    if (score > bestScore) {
                        bestScore = score;
                        List<Candidate> chosen = new ArrayList<>();
                        Collections.addAll(chosen, pick);
                        best = chosen;
                    }
                }
                return;
            }
            for (Candidate c : lists.get(depth)) {
                double m = minPair;
                for (Candidate f : fixed) {
                    m = Math.min(m, separation(c, f));
                }
                for (int i = 0; i < depth; i++) {
                    m = Math.min(m, separation(c, pick[i]));
                }
                if (m < min && m <= bestSep) {
                    continue; // cannot be valid, and cannot improve the diagnostics either
                }
                pick[depth] = c;
                run(depth + 1, pick, m, distSum + c.distance);
            }
            pick[depth] = null;
        }
    }
}
