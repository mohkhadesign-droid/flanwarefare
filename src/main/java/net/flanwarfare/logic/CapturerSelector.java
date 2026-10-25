package net.flanwarfare.logic;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Chooses the single player who will own a claim if the capturing side completes it.
 *
 * <p>Policy (deterministic, never random):
 * <ol>
 *   <li>The first player of the capturing side to stand at the flag is the initiator and stays
 *       the designated capturer for as long as they remain continuously present.</li>
 *   <li>If the initiator leaves (or becomes ineligible) the role passes to the present player
 *       with the longest continuous participation. A returning player starts a new streak and
 *       does not regain the initiator role.</li>
 *   <li>Ties are broken by the lowest UUID ({@link UUID#compareTo}).</li>
 * </ol>
 * The selector resets whenever the side owning the progress changes.
 */
public final class CapturerSelector {
    private final Map<UUID, Long> streakStart = new HashMap<>();
    private UUID initiator;
    private boolean initiatorChosen;
    private String side;

    /** Updates participation with the players of {@code currentSide} present at tick {@code now}. */
    public void update(long now, String currentSide, Set<UUID> present) {
        if (currentSide == null ? side != null : !currentSide.equals(side)) {
            reset();
            side = currentSide;
        }
        if (side == null) {
            return;
        }
        Iterator<Map.Entry<UUID, Long>> it = streakStart.entrySet().iterator();
        while (it.hasNext()) {
            if (!present.contains(it.next().getKey())) {
                it.remove();
            }
        }
        for (UUID id : present) {
            if (!streakStart.containsKey(id)) {
                streakStart.put(id, now);
            }
        }
        if (initiator != null && !streakStart.containsKey(initiator)) {
            initiator = null;
        }
        if (!initiatorChosen && !streakStart.isEmpty()) {
            initiator = longest();
            initiatorChosen = true;
        }
    }

    /** Player who would receive the claim right now, or null when nobody is present. */
    public UUID designated() {
        if (initiator != null && streakStart.containsKey(initiator)) {
            return initiator;
        }
        return longest();
    }

    private UUID longest() {
        UUID best = null;
        long bestStart = Long.MAX_VALUE;
        for (Map.Entry<UUID, Long> entry : streakStart.entrySet()) {
            long start = entry.getValue();
            if (best == null || start < bestStart || (start == bestStart && entry.getKey().compareTo(best) < 0)) {
                best = entry.getKey();
                bestStart = start;
            }
        }
        return best;
    }

    public void reset() {
        streakStart.clear();
        initiator = null;
        initiatorChosen = false;
        side = null;
    }
}
