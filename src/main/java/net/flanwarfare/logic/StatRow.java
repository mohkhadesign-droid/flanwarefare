package net.flanwarfare.logic;

import java.util.Comparator;

/** One leaderboard row for a player or a kingdom. */
public final class StatRow {
    public final String id;
    public String name;
    public int conquests;
    public int defenses;
    public int conqueredHeld;
    public int totalHeld;
    public int lost;

    public StatRow(String id, String name) {
        this.id = id;
        this.name = name;
    }

    /** Conquests, then defenses, then conquered territory held, then id (stable). */
    public static final Comparator<StatRow> BY_CONQUESTS = (a, b) -> {
        int c = Integer.compare(b.conquests, a.conquests);
        if (c == 0) {
            c = Integer.compare(b.defenses, a.defenses);
        }
        if (c == 0) {
            c = Integer.compare(b.conqueredHeld, a.conqueredHeld);
        }
        return c != 0 ? c : a.id.compareTo(b.id);
    };

    /** Defenses, then conquests, then conquered held, then id. */
    public static final Comparator<StatRow> BY_DEFENSES = (a, b) -> {
        int c = Integer.compare(b.defenses, a.defenses);
        if (c == 0) {
            c = Integer.compare(b.conquests, a.conquests);
        }
        if (c == 0) {
            c = Integer.compare(b.conqueredHeld, a.conqueredHeld);
        }
        return c != 0 ? c : a.id.compareTo(b.id);
    };

    /** Total territory held, then conquered territory held, then conquests, then id. */
    public static final Comparator<StatRow> BY_TERRITORY = (a, b) -> {
        int c = Integer.compare(b.totalHeld, a.totalHeld);
        if (c == 0) {
            c = Integer.compare(b.conqueredHeld, a.conqueredHeld);
        }
        if (c == 0) {
            c = Integer.compare(b.conquests, a.conquests);
        }
        return c != 0 ? c : a.id.compareTo(b.id);
    };
}
