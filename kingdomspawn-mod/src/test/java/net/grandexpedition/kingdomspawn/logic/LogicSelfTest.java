package net.grandexpedition.kingdomspawn.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Dependency-free checks of the pure logic. Run by `gradlew build` (task logicTest). */
public final class LogicSelfTest {
    private static int passed;

    public static void main(String[] args) {
        bounds();
        searchPlan();
        placement();
        slots();
        System.out.println("LogicSelfTest: all " + passed + " checks passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("FAILED: " + what);
        }
        passed++;
    }

    private static void bounds() {
        Bounds b = Bounds.withMargin(-50000, 50000, -50000, 50000, 500);
        check(b.minX == -49500 && b.maxX == 49500 && b.minZ == -49500 && b.maxZ == 49500, "margin applied");
        check(Bounds.withMargin(-100, 100, -100, 100, 200) == null, "margin larger than area");
        check(Bounds.withMargin(100, -100, 50, -50, 0).minX == -100, "swapped bounds normalised");
        check(b.contains(49500, -49500) && !b.contains(49501, 0), "contains");
    }

    private static void searchPlan() {
        SearchPlan p = SearchPlan.create(-49500, 49500, -49500, 49500, 256);
        check(p.size() > 140000 && p.size() < 160000, "about 150k samples at 256 spacing: " + p.size());
        int first = p.distance(0);
        check(first >= 69000, "starts in a corner (~70k from centre): " + first);
        boolean monotone = true;
        for (int i = 1; i < p.size(); i++) {
            if (p.distance(i) > p.distance(i - 1)) {
                monotone = false;
                break;
            }
        }
        check(monotone, "farthest first, moving inward");
        check(p.distance(p.size() - 1) < 400, "ends near the centre");
        // The four corners are the first four samples (equal distance), in some order.
        java.util.Set<String> corners = new java.util.HashSet<>();
        for (int i = 0; i < 4; i++) {
            corners.add(Integer.signum(p.x(i)) + "," + Integer.signum(p.z(i)));
        }
        check(corners.size() == 4, "all four corners first");
        int bands = 0;
        for (int i = 0; i < p.size(); i++) {
            if (p.endsBand(i, 2000)) {
                bands++;
            }
        }
        check(bands >= 30 && bands <= 40, "about 35 bands of 2000 blocks: " + bands);
        boolean threw = false;
        try {
            SearchPlan.create(-50000, 50000, -50000, 50000, 16);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "refuses absurd sample counts");
    }

    private static Placement.Candidate c(int x, int z) {
        return new Placement.Candidate(x, z);
    }

    private static void placement() {
        check(Placement.sector(c(1000, 0), 8) != Placement.sector(c(-1000, 0), 8), "opposite sectors differ");
        List<Placement.Candidate> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            many.add(c(40000 + i, 100));
        }
        many.add(c(-30000, 0));
        List<Placement.Candidate> div = Placement.diversify(many, 16, 4);
        check(div.size() == 5, "4 per sector + the lone western one: " + div.size());
        check(div.get(0).x == 40099, "farthest kept first");

        // Three kingdoms, candidates in different directions; the far, spread-out set wins.
        List<List<Placement.Candidate>> k = Arrays.asList(
                Arrays.asList(c(45000, 45000), c(3000, 3000)),
                Arrays.asList(c(-45000, 45000), c(-46000, 44000)),
                Arrays.asList(c(0, -48000), c(45500, 44500)));
        Placement.Result r = Placement.choose(k, Collections.<Placement.Candidate>emptyList(), 15000);
        check(r.chosen != null, "a valid combination exists");
        check(r.chosen.get(0).x == 45000 && r.chosen.get(2).z == -48000, "picks the far, separated set");
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                check(Placement.separation(r.chosen.get(i), r.chosen.get(j)) >= 15000, "pair " + i + "," + j + " separated");
            }
        }
        // All candidates in one corner: nothing satisfies 15k; report the best possible.
        List<List<Placement.Candidate>> crowded = Arrays.asList(
                Arrays.asList(c(45000, 45000)), Arrays.asList(c(44000, 45000)), Arrays.asList(c(45000, 43000)));
        Placement.Result none = Placement.choose(crowded, Collections.<Placement.Candidate>emptyList(), 15000);
        check(none.chosen == null, "no valid combination reported");
        check(Math.abs(none.bestPossibleSeparation - 1000) < 1e-6,
                "diagnostic best separation: " + none.bestPossibleSeparation);
        // One kingdom searched, two already approved (fixed): must keep away from both.
        List<List<Placement.Candidate>> one = Collections.singletonList(
                Arrays.asList(c(44000, 44000), c(-44000, -44000)));
        Placement.Result withFixed = Placement.choose(one,
                Arrays.asList(c(45000, 45000), c(45000, -45000)), 15000);
        check(withFixed.chosen != null && withFixed.chosen.get(0).x == -44000, "stays away from approved kingdoms");
        // A kingdom without candidates: no result.
        List<List<Placement.Candidate>> empty = Arrays.asList(
                Collections.singletonList(c(1, 1)), Collections.<Placement.Candidate>emptyList());
        check(Placement.choose(empty, Collections.<Placement.Candidate>emptyList(), 0).chosen == null, "empty kingdom");
    }

    private static void slots() {
        List<int[]> s = Slots.offsets(5, 10);
        check(s.size() > 100, "many slots in the 5..10 ring: " + s.size());
        for (int[] o : s) {
            double r = Math.sqrt(o[0] * o[0] + o[1] * o[1]);
            if (r < 5 || r > 10) {
                check(false, "slot outside 5..10: " + o[0] + "," + o[1]);
            }
        }
        check(true, "all slots within 5..10 blocks");
        // The first five are spread out: at least 4 blocks apart from each other.
        for (int i = 0; i < 5; i++) {
            for (int j = i + 1; j < 5; j++) {
                double d = Math.hypot(s.get(i)[0] - s.get(j)[0], s.get(i)[1] - s.get(j)[1]);
                check(d >= 4, "first slots spread out " + i + "," + j + ": " + d);
            }
        }
        java.util.Set<String> unique = new java.util.HashSet<>();
        for (int[] o : s) {
            unique.add(o[0] + "," + o[1]);
        }
        check(unique.size() == s.size(), "no duplicate slots");
    }
}
