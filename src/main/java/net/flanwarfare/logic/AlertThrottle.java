package net.flanwarfare.logic;

/**
 * Per-claim alert throttling for ordinary raid activity.
 *
 * <ul>
 *   <li>The first hostile action, and the first one after {@code cooldown} has passed since the
 *   last title, produces a TITLE (centre-screen title, sound, chat).</li>
 *   <li>Further actions within the cooldown produce at most one HINT (action-bar update) per
 *   {@code hintInterval}; everything else is NONE.</li>
 *   <li>The claim counts as under attack while the last hostile action is less than
 *   {@code cooldown} old.</li>
 * </ul>
 * Capture start, failure and conquest are separate high-priority alerts and do not pass through
 * this throttle. Contest updates have their own interval.
 */
public final class AlertThrottle {

    public enum Decision { TITLE, HINT, NONE }

    private boolean titled;
    private long lastTitleAt;
    private long lastHintAt;
    private long lastActivityAt;
    private boolean contestNoticed;
    private long lastContestAt;

    public Decision hostile(long now, long cooldown, long hintInterval) {
        lastActivityAt = now;
        if (!titled || now - lastTitleAt >= cooldown) {
            titled = true;
            lastTitleAt = now;
            lastHintAt = now;
            return Decision.TITLE;
        }
        if (now - lastHintAt >= hintInterval) {
            lastHintAt = now;
            return Decision.HINT;
        }
        return Decision.NONE;
    }

    public boolean underAttack(long now, long cooldown) {
        return titled && now - lastActivityAt < cooldown;
    }

    public boolean contestUpdate(long now, long interval) {
        if (!contestNoticed || now - lastContestAt >= interval) {
            contestNoticed = true;
            lastContestAt = now;
            return true;
        }
        return false;
    }
}
