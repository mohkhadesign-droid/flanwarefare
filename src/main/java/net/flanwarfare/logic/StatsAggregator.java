package net.flanwarfare.logic;

import net.flanwarfare.data.WarEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Computes leaderboards from stored events (history) plus current claim ownership (territory).
 *
 * <ul>
 *   <li>Events with an id already seen are ignored, so a duplicated event can never count twice.</li>
 *   <li>Reversed conquests count for nobody.</li>
 *   <li>Conquest: +1 conquest for the new owner and their kingdom at the time; +1 lost for the
 *   previous owner and their kingdom at the time.</li>
 *   <li>Defense: +1 defense for each credited defender, but only +1 for the defending kingdom.</li>
 *   <li>Territory held comes only from {@link Ownership} (current Flan owners), so trusted
 *   members never count, and each claim counts once.</li>
 * </ul>
 */
public final class StatsAggregator {

    /** One current claim, as seen in Flan right now. */
    public static final class Ownership {
        public final UUID owner;
        public final String ownerName;
        public final boolean conquered;
        public final String kingdomId;
        public final String kingdomName;

        public Ownership(UUID owner, String ownerName, boolean conquered, String kingdomId, String kingdomName) {
            this.owner = owner;
            this.ownerName = ownerName;
            this.conquered = conquered;
            this.kingdomId = kingdomId;
            this.kingdomName = kingdomName;
        }
    }

    private final Map<String, StatRow> players = new LinkedHashMap<>();
    private final Map<String, StatRow> kingdoms = new LinkedHashMap<>();
    private int duplicates;

    public static StatsAggregator compute(Collection<WarEvent> events, Collection<Ownership> ownership) {
        StatsAggregator a = new StatsAggregator();
        Set<String> seen = new HashSet<>();
        for (WarEvent e : events) {
            if (e == null || e.id == null) {
                continue;
            }
            if (!seen.add(e.id)) {
                a.duplicates++;
                continue;
            }
            if (WarEvent.CONQUEST.equals(e.type) && !e.reversed) {
                if (e.newOwner != null) {
                    a.player(e.newOwner, e.newOwnerName).conquests++;
                }
                if (e.previousOwner != null) {
                    a.player(e.previousOwner, e.previousOwnerName).lost++;
                }
                if (e.actingKingdomId != null) {
                    a.kingdom(e.actingKingdomId, e.actingKingdomName).conquests++;
                }
                if (e.previousKingdomId != null) {
                    a.kingdom(e.previousKingdomId, e.previousKingdomName).lost++;
                }
            } else if (WarEvent.DEFENSE.equals(e.type)) {
                Set<UUID> credited = new HashSet<>();
                for (int i = 0; i < e.participants.size(); i++) {
                    UUID id = e.participants.get(i);
                    if (id != null && credited.add(id)) {
                        String name = i < e.participantNames.size() ? e.participantNames.get(i) : null;
                        a.player(id, name).defenses++;
                    }
                }
                if (e.actingKingdomId != null) {
                    a.kingdom(e.actingKingdomId, e.actingKingdomName).defenses++;
                }
            }
        }
        for (Ownership o : ownership) {
            StatRow p = a.player(o.owner, o.ownerName);
            p.totalHeld++;
            if (o.conquered) {
                p.conqueredHeld++;
            }
            if (o.kingdomId != null) {
                StatRow k = a.kingdom(o.kingdomId, o.kingdomName);
                k.totalHeld++;
                if (o.conquered) {
                    k.conqueredHeld++;
                }
            }
        }
        return a;
    }

    private StatRow player(UUID id, String name) {
        StatRow row = players.computeIfAbsent(id.toString(), k -> new StatRow(k, name));
        if (row.name == null && name != null) {
            row.name = name;
        }
        return row;
    }

    private StatRow kingdom(String id, String name) {
        StatRow row = kingdoms.computeIfAbsent(id, k -> new StatRow(k, name));
        if (name != null) {
            row.name = name; // latest recorded name for display; the id stays the identity
        }
        return row;
    }

    public List<StatRow> players(java.util.Comparator<StatRow> order) {
        List<StatRow> list = new ArrayList<>(players.values());
        list.sort(order);
        return list;
    }

    public List<StatRow> kingdoms(java.util.Comparator<StatRow> order) {
        List<StatRow> list = new ArrayList<>(kingdoms.values());
        list.sort(order);
        return list;
    }

    public StatRow player(UUID id) {
        return players.get(id.toString());
    }

    public int duplicates() {
        return duplicates;
    }
}
