package net.flanwarfare.logic;

/**
 * Maps authoritative capture progress to the flagpole's visible banner (stepped animation).
 *
 * <p>The banner can hang at levels 1..{@code levels} on the pole (level {@code levels} = fully
 * raised). Before the midpoint the owner's banner descends; at the midpoint it reaches level 1
 * and switches to the attacker's design; after the midpoint the attacker's banner rises; at
 * 100% it is at the top. Decay simply runs the same mapping backwards. The mapping is a pure
 * function of progress, so it can never drift from the capture itself.
 */
public final class FlagAnimation {

    /** One visible state: banner level (1 = lowest) and whose design is shown. */
    public static final class Frame {
        public final int level;
        public final boolean attacker;

        Frame(int level, boolean attacker) {
            this.level = level;
            this.attacker = attacker;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Frame && ((Frame) o).level == level && ((Frame) o).attacker == attacker;
        }

        @Override
        public int hashCode() {
            return level * 2 + (attacker ? 1 : 0);
        }
    }

    private FlagAnimation() {
    }

    public static Frame frame(double fraction, int midpointPercent, int levels) {
        int top = Math.max(1, levels);
        double f = Math.max(0.0, Math.min(1.0, fraction));
        double mid = Math.max(0.01, Math.min(0.99, midpointPercent / 100.0));
        if (top == 1) {
            return new Frame(1, f >= mid);
        }
        if (f < mid) {
            double t = f / mid;
            return new Frame(top - (int) Math.round(t * (top - 1)), false);
        }
        double t = (f - mid) / (1.0 - mid);
        return new Frame(1 + (int) Math.round(t * (top - 1)), true);
    }
}
