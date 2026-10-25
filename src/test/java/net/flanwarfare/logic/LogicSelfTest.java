package net.flanwarfare.logic;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Dependency-free tests for the Minecraft-independent logic. Run with
 * {@code ./gradlew logicTest} (or plain javac/java). Exits non-zero on the first failure.
 */
public final class LogicSelfTest {
    private static int passed;

    public static void main(String[] args) {
        clockConversion();
        windowsCrossingSunrise();
        windowsSameDay();
        transitions();
        sleepClamp();
        soloCapture();
        defenderPauses();
        rivalsContest();
        rivalCannotSteal();
        decayWhenAttackersLeave();
        decayAfterRaidEvenWithAttackers();
        capturerPolicy();
        defenderAt99Percent();
        ownerExitsAndReenters();
        alternatingDefenders();
        simultaneousEntry();
        raidEndsWhileContested();
        defenderAloneDecays();
        zoneGeometry();
        alertThrottle();
        warStates();
        System.out.println("LogicSelfTest: all " + passed + " checks passed");
    }

    private static void clockConversion() {
        eq(0, ClockMath.parseClock("06:00"), "06:00");
        eq(6000, ClockMath.parseClock("12:00"), "12:00");
        eq(9000, ClockMath.parseClock("15:00"), "15:00");
        eq(12000, ClockMath.parseClock("18:00"), "18:00");
        eq(18000, ClockMath.parseClock("00:00"), "00:00");
        eq(23000, ClockMath.parseClock("05:00"), "05:00");
        eq(500, ClockMath.parseClock("06:30"), "06:30");
        eq("15:00", ClockMath.formatClock(9000), "format 9000");
        eq("05:00", ClockMath.formatClock(23000 + 24000L * 7), "format later day");
        eq("06:00", ClockMath.formatClock(-24000), "format negative");
        boolean threw = false;
        try {
            ClockMath.parseClock("25:00");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "rejects 25:00");
    }

    private static void windowsCrossingSunrise() {
        int s = ClockMath.parseClock("15:00");
        int e = ClockMath.parseClock("06:00");
        check(ClockMath.inWindow(9000, s, e), "15:00 inside 15-06");
        check(ClockMath.inWindow(18000, s, e), "midnight inside 15-06");
        check(ClockMath.inWindow(23999, s, e), "05:59 inside 15-06");
        check(!ClockMath.inWindow(0, s, e), "06:00 outside 15-06 (end exclusive)");
        check(!ClockMath.inWindow(8999, s, e), "14:59 outside 15-06");

        int s2 = ClockMath.parseClock("18:00");
        int e2 = ClockMath.parseClock("05:00");
        check(ClockMath.inWindow(18000, s2, e2), "midnight inside 18-05");
        check(!ClockMath.inWindow(23000, s2, e2), "05:00 outside 18-05");
        check(!ClockMath.inWindow(6000, s2, e2), "noon outside 18-05");
    }

    private static void windowsSameDay() {
        int s = ClockMath.parseClock("08:00");
        int e = ClockMath.parseClock("20:00");
        check(ClockMath.inWindow(6000, s, e), "noon inside 08-20");
        check(!ClockMath.inWindow(15000, s, e), "21:00 outside 08-20");
        check(!ClockMath.inWindow(5, 100, 100), "empty window");
    }

    private static void transitions() {
        int s = 9000;
        int e = 0;
        long day5 = 24000L * 5;
        eq(9000L, ClockMath.ticksUntilChange(day5, s, e), "opens in 9000 from 06:00");
        eq(15000L, ClockMath.ticksUntilChange(day5 + 9000, s, e), "closes 15000 after opening");
        eq(1L, ClockMath.ticksUntilChange(day5 + 23999, s, e), "closes next tick");
        long id1 = ClockMath.windowStart(day5 + 9000, s, e);
        long id2 = ClockMath.windowStart(day5 + 23999, s, e);
        eq(id1, id2, "same window id across sunrise wrap");
        long id3 = ClockMath.windowStart(day5 + 24000 + 9000, s, e);
        check(id3 != id1, "next window has a new id");
        // /time set backwards into the same window keeps the same id only if in that window
        eq(day5 + 9000, id1, "window id equals absolute start tick");
    }

    private static void sleepClamp() {
        int s = ClockMath.parseClock("18:00");
        int e = ClockMath.parseClock("05:00");
        long now = 12541; // 18:32ish, raid active
        eq(now, ClockMath.clampSleepTarget(now, 24000, s, e), "no skip during raid");
        int s2 = ClockMath.parseClock("20:00");
        long now2 = 12541; // before 20:00 (14000)
        eq(14000L, ClockMath.clampSleepTarget(now2, 24000, s2, e), "skip stops at raid start");
        int s3 = ClockMath.parseClock("08:00");
        int e3 = ClockMath.parseClock("12:00");
        eq(24000L, ClockMath.clampSleepTarget(13000, 24000, s3, e3), "skip allowed when no raid in span");
    }

    private static Set<String> sides(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    private static void soloCapture() {
        CaptureModel m = new CaptureModel(3600, 1200);
        for (int i = 0; i < 359; i++) {
            m.step(10, true, sides("A"), false, true, true);
        }
        eq(CaptureModel.Status.CAPTURING, m.status(), "still capturing at 3590");
        m.step(10, true, sides("A"), false, true, true);
        eq(CaptureModel.Status.COMPLETE, m.status(), "complete after 180s");
        eq("A", m.side(), "side A owns completion");
    }

    private static void defenderPauses() {
        CaptureModel m = new CaptureModel(3600, 1200);
        run(m, 180, sides("A"), false); // 1800 ticks = 50%
        double before = m.progressTicks();
        run(m, 50, sides("A"), true);
        eq(CaptureModel.Status.CONTESTED, m.status(), "defender contests");
        eqd(before, m.progressTicks(), "progress paused, not reset");
        run(m, 10, sides("A"), false);
        check(m.progressTicks() > before, "resumes after defender leaves");
    }

    private static void rivalsContest() {
        CaptureModel m = new CaptureModel(3600, 1200);
        run(m, 100, sides("A"), false);
        double before = m.progressTicks();
        run(m, 100, sides("A", "B"), false);
        eq(CaptureModel.Status.CONTESTED, m.status(), "two sides contest");
        eqd(before, m.progressTicks(), "no side gains while contested");
    }

    private static void rivalCannotSteal() {
        CaptureModel m = new CaptureModel(3600, 1200);
        run(m, 120, sides("A"), false); // 1200 ticks of progress
        run(m, 1, sides("B"), false);
        eq("A", m.side(), "progress still belongs to A");
        check(m.progressTicks() < 1200, "B erodes A's progress");
        // Erosion removes 30 progress per 10-tick step: A's 1200 reaches zero after 40 steps in
        // total. The remaining 21 steps are B's own fresh progress: 21 * 10 = 210.
        run(m, 60, sides("B"), false);
        eq("B", m.side(), "B starts fresh only after reaching zero");
        eqd(210.0, m.progressTicks(), "B did not inherit A's progress");
    }

    private static void decayWhenAttackersLeave() {
        CaptureModel m = new CaptureModel(3600, 1200);
        run(m, 360, sides("A"), false); // full minus... 3600 -> complete
        CaptureModel n = new CaptureModel(3600, 1200);
        run(n, 270, sides("A"), false); // 75%
        eqd(0.75, n.fraction(), "75%");
        run(n, 30, Collections.<String>emptySet(), false); // 300 ticks decay = 900 progress
        eq(CaptureModel.Status.DECAYING, n.status(), "decaying");
        eqd(0.5, n.fraction(), "decays at full-bar-per-60s rate");
        run(n, 100, Collections.<String>emptySet(), false);
        check(n.isFinished(), "session ends at zero");
        check(n.side() == null, "side cleared at zero");
        check(m.status() == CaptureModel.Status.COMPLETE, "control: full capture completes");
    }

    private static void decayAfterRaidEvenWithAttackers() {
        CaptureModel m = new CaptureModel(3600, 1200);
        run(m, 270, sides("A"), false);
        for (int i = 0; i < 30; i++) {
            m.step(10, false, sides("A"), false, true, true);
        }
        eqd(0.5, m.fraction(), "decays after raid even with attacker present");
        for (int i = 0; i < 100; i++) {
            m.step(10, false, sides("A"), false, true, true);
        }
        check(m.isFinished(), "decays to zero, never completes after raid");
    }

    private static void capturerPolicy() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        CapturerSelector sel = new CapturerSelector();
        sel.update(0, "T", set(b));
        sel.update(10, "T", set(b, a));
        eq(b, sel.designated(), "initiator b keeps role while present");
        sel.update(20, "T", set(a, c));
        eq(a, sel.designated(), "longest continuous (a) after initiator leaves");
        sel.update(30, "T", set(a, c, b));
        eq(a, sel.designated(), "returning initiator does not regain role");
        CapturerSelector tie = new CapturerSelector();
        tie.update(0, "T", set(c, b));
        eq(b, tie.designated(), "tie broken by lowest UUID");
        tie.update(5, "U", set(c));
        eq(c, tie.designated(), "side change resets participation");
    }

    private static void defenderAt99Percent() {
        CaptureModel m = new CaptureModel(3600, 1200);
        run(m, 356, sides("A"), false); // 3560 ticks = 98.9%
        check(m.fraction() > 0.98 && m.status() == CaptureModel.Status.CAPTURING, "reached 99%");
        run(m, 500, sides("A"), true);
        eq(CaptureModel.Status.CONTESTED, m.status(), "defender at 99% contests");
        check(m.status() != CaptureModel.Status.COMPLETE, "defender at 99% prevents completion");
        eqd(3560, m.progressTicks(), "no progress while defender stays");
    }

    private static void ownerExitsAndReenters() {
        CaptureModel m = new CaptureModel(3600, 1200);
        run(m, 100, sides("A"), false); // 1000
        run(m, 10, sides("A"), true);
        run(m, 1, sides("A"), false); // owner steps out for one evaluation: +10
        run(m, 10, sides("A"), true);
        eqd(1010, m.progressTicks(), "exit for one evaluation gains exactly one step, re-entry pauses again");
    }

    private static void alternatingDefenders() {
        CaptureModel m = new CaptureModel(3600, 1200);
        run(m, 100, sides("A"), false);
        double before = m.progressTicks();
        // Defender 1 leaves on the same evaluation defender 2 arrives: at least one present every step.
        for (int i = 0; i < 40; i++) {
            m.step(10, true, sides("A"), true, true, true);
        }
        eqd(before, m.progressTicks(), "continuous defender presence keeps it paused");
    }

    private static void simultaneousEntry() {
        CaptureModel m = new CaptureModel(3600, 1200);
        m.step(10, true, sides("A"), true, true, true);
        eq(CaptureModel.Status.CONTESTED, m.status(), "attacker and defender arriving together: contested");
        eqd(0, m.progressTicks(), "no progress on simultaneous entry");
    }

    private static void raidEndsWhileContested() {
        CaptureModel m = new CaptureModel(3600, 1200);
        run(m, 180, sides("A"), false); // 50%
        run(m, 10, sides("A"), true);
        for (int i = 0; i < 30; i++) {
            m.step(10, false, sides("A"), true, true, true); // raid over, both still there
        }
        eqd(0.25, m.fraction(), "post-raid decay applies even while contested");
    }

    private static void defenderAloneDecays() {
        CaptureModel m = new CaptureModel(3600, 1200);
        run(m, 180, sides("A"), false); // 50%
        run(m, 30, Collections.<String>emptySet(), true); // attackers gone, defender stays
        eqd(0.25, m.fraction(), "attackers gone: abandonment decay even with defender present");
    }

    private static void zoneGeometry() {
        check(CaptureZone.inside(10.5, 64, 12.5, 10, 64, 10, 2.0), "2.0 blocks: inside");
        check(!CaptureZone.inside(10.5, 64, 12.6, 10, 64, 10, 2.0), "2.1 blocks: outside");
        check(!CaptureZone.inside(10.5, 62.9, 10.5, 10, 64, 10, 2.0), "too far below");
        check(!CaptureZone.inside(10.5, 67.1, 10.5, 10, 64, 10, 2.0), "too far above");
        boolean allOnRadius = true;
        for (int i = 0; i < 24; i++) {
            double[] p = CaptureZone.ringPoint(10, 10, 2.0, i, 24);
            allOnRadius &= Math.abs(CaptureZone.horizontalDistance(p[0], p[1], 10, 10) - 2.0) < 1e-9;
        }
        check(allOnRadius, "every aura particle lies exactly on the capture radius");
        eqd(1.5, CaptureZone.horizontalDistance(12.0, 10.5, 10, 10), "action-bar distance");
    }

    private static void alertThrottle() {
        AlertThrottle t = new AlertThrottle();
        int titles = 0;
        int hints = 0;
        for (int i = 0; i < 50; i++) { // 50 blocks broken over 25 seconds
            AlertThrottle.Decision d = t.hostile(i * 500L, 45000L, 5000L);
            if (d == AlertThrottle.Decision.TITLE) {
                titles++;
            } else if (d == AlertThrottle.Decision.HINT) {
                hints++;
            }
        }
        eq(1, titles, "50 breaks: one title");
        eq(4, hints, "50 breaks: action-bar hint at most every 5 s");
        check(t.underAttack(30000L, 45000L), "under attack within cooldown");
        check(!t.underAttack(24500L + 45000L, 45000L), "peaceful after cooldown without activity");
        eq(AlertThrottle.Decision.TITLE, t.hostile(24500L + 45000L, 45000L, 5000L), "new title after cooldown");
        check(t.contestUpdate(0, 15000L) && !t.contestUpdate(5000L, 15000L) && t.contestUpdate(15000L, 15000L),
                "contest updates throttled to one per 15 s");
    }

    private static void warStates() {
        eq(net.flanwarfare.alerts.ClaimWarState.CAPTURE_CONTESTED,
                net.flanwarfare.alerts.ClaimWarState.of(CaptureModel.Status.CONTESTED, true, true), "capture state wins");
        eq(net.flanwarfare.alerts.ClaimWarState.RAID_ACTIVITY,
                net.flanwarfare.alerts.ClaimWarState.of(null, true, false), "raid activity");
        eq(net.flanwarfare.alerts.ClaimWarState.CAPTURE_DECAYING,
                net.flanwarfare.alerts.ClaimWarState.of(CaptureModel.Status.DECAYING, false, false), "decaying");
        eq(net.flanwarfare.alerts.ClaimWarState.PEACEFUL,
                net.flanwarfare.alerts.ClaimWarState.of(null, false, false), "raid window alone is peaceful");
        eq(net.flanwarfare.alerts.ClaimWarState.CONQUERED,
                net.flanwarfare.alerts.ClaimWarState.of(null, false, true), "conquered when quiet");
    }

    private static Set<UUID> set(UUID... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    private static void run(CaptureModel m, int steps, Set<String> attackers, boolean defender) {
        for (int i = 0; i < steps; i++) {
            m.step(10, true, attackers, defender, true, true);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("FAILED: " + what);
        }
        passed++;
    }

    private static void eq(Object expected, Object actual, String what) {
        check(expected.equals(actual), what + " (expected " + expected + ", got " + actual + ")");
    }

    private static void eqd(double expected, double actual, String what) {
        check(Math.abs(expected - actual) < 1e-6, what + " (expected " + expected + ", got " + actual + ")");
    }
}
