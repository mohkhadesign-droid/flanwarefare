package net.flanwarfare.logic;

/**
 * Whether a finished capture session counts as a successful defense (statistics only; it never
 * changes the capture itself). All conditions are required:
 * <ol>
 *   <li>progress reached at least the minimum percentage (default 10%);</li>
 *   <li>at least one defender stood in the zone while an attacker was there during the raid;</li>
 *   <li>the session ended by decaying to zero with nobody left at the flag (attackers gone),
 *   not by conquest, cancellation or a new raid window (the caller only asks in that case);</li>
 *   <li>no attacker was still in the zone after the raid window closed (so a raid ending while
 *   attackers were still contesting never awards a defense);</li>
 *   <li>no defense was credited for the same claim within the cooldown (anti-farming).</li>
 * </ol>
 */
public final class DefenseRules {
    private DefenseRules() {
    }

    public static boolean qualifies(double peakFraction, int minimumPercent, int contestingDefenders,
                                    boolean attackersStayedAfterRaid, long lastDefenseAt, long now, long cooldownMillis) {
        if (peakFraction * 100.0 + 1e-9 < minimumPercent) {
            return false;
        }
        if (contestingDefenders <= 0 || attackersStayedAfterRaid) {
            return false;
        }
        return lastDefenseAt <= 0 || now - lastDefenseAt >= cooldownMillis;
    }
}
