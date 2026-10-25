package net.flanwarfare.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One significant, permanent warfare event of a season (never individual block actions or
 * capture ticks). Statistics are always computed from these events, so each event counts
 * exactly once: the id is unique, and for conquests it is the conquest transaction id, which is
 * written in the same atomic file write that commits the transaction.
 *
 * <p>Names and kingdoms are recorded as they were at the time of the event; later renames or
 * team changes never rewrite history.
 */
public final class WarEvent {
    public static final String CONQUEST = "CONQUEST";
    public static final String DEFENSE = "DEFENSE";
    public static final String CONQUEST_REVERSED = "CONQUEST_REVERSED";

    public String id;
    public String type;
    public String seasonId;
    public long at;

    public UUID claimId;
    public String claimName;
    public String dimension;
    public int x;
    public int z;

    /** Claim owner before the event (conquest: the loser; defense: the defended owner). */
    public UUID previousOwner;
    public String previousOwnerName;
    /** Conquest: the new owner (designated capturer). */
    public UUID newOwner;
    public String newOwnerName;

    /** Kingdom of {@link #previousOwner} at the time, null if unknown/none. */
    public String previousKingdomId;
    public String previousKingdomName;
    /** Conquest: capturing kingdom. Defense: defending kingdom (the owner's). Null if unknown/none. */
    public String actingKingdomId;
    public String actingKingdomName;
    /** Defense: the attacking kingdom, for the log. */
    public String opposingKingdomId;
    public String opposingKingdomName;

    /** Conquest: capturing side's players. Defense: credited defenders. */
    public List<UUID> participants = new ArrayList<>();
    public List<String> participantNames = new ArrayList<>();
    /** Defense: attackers that were repelled (log only). */
    public List<String> opponentNames = new ArrayList<>();

    public UUID txnId;
    public int peakPercent;

    /** Administrative correction: a reversed conquest no longer counts in statistics. */
    public boolean reversed;
    public long reversedAt;
    public String reversedNote;
    /** CONQUEST_REVERSED: id of the conquest event it corrects. */
    public String correctsEventId;
}
