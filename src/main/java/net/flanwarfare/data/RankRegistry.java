package net.flanwarfare.data;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The ranks of one kingdom, attached to its main Flan claim. Whoever owns that claim in Flan
 * manages the registry; if the claim is conquered, management passes to the new owner with
 * the claim, and assignments are revalidated against current membership.
 */
public final class RankRegistry {
    public UUID claimId;
    public String dimension;
    public long createdAt;
    /** Rank id -> rank. */
    public Map<String, RankDef> ranks = new LinkedHashMap<>();
    /** Player -> rank id. A player holds at most one rank on the whole server. */
    public Map<UUID, String> assignments = new HashMap<>();
    /** Player -> RankRules.Position name (overrides the rank default). */
    public Map<UUID, String> positionOverrides = new HashMap<>();

    void repair() {
        if (ranks == null) {
            ranks = new LinkedHashMap<>();
        }
        if (assignments == null) {
            assignments = new HashMap<>();
        }
        if (positionOverrides == null) {
            positionOverrides = new HashMap<>();
        }
        assignments.values().removeIf(id -> !ranks.containsKey(id));
        for (RankDef rank : ranks.values()) {
            if (rank.colors == null) {
                rank.colors = new java.util.ArrayList<>();
            }
            if (rank.prefix == null) {
                rank.prefix = "";
            }
            if (rank.suffix == null) {
                rank.suffix = "";
            }
            if (rank.effect == null) {
                rank.effect = "NONE";
            }
        }
    }
}
