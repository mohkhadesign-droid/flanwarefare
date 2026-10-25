package net.flanwarfare.data;

import java.util.UUID;

/**
 * Journal entry for one conquest. Written (and flushed) before Flan is touched, so a crash at
 * any point can be reconciled on the next start without guessing.
 */
public final class ConquestTxn {
    public enum Phase {
        /** Recorded; Flan may or may not have been changed and saved. */
        PENDING,
        /** Flan and add-on state are both final. */
        COMMITTED,
        /** Startup found Flan unchanged; nothing happened. */
        ABORTED,
        /** Startup found an inconsistent state; an administrator must decide. */
        NEEDS_ADMIN,
        /** Ownership was handed back by an administrator. */
        ROLLED_BACK
    }

    public UUID txnId;
    public UUID claimId;
    public String dimension;
    public UUID oldOwner;
    public UUID newOwner;
    public boolean wasConqueredBefore;
    public String previousDesign;
    public String newDesign;
    /** Flan's own JSON of the claim before conquest (bounds, members, groups, subclaims). */
    public String snapshotJson;
    public Phase phase = Phase.PENDING;
    public long startedAt;
    public long finishedAt;
    public String note;

    // Recorded when the transaction is journaled, so history reflects the moment of conquest.
    public long previousImmuneUntil;
    public String claimLabel;
    public int x;
    public int z;
    public String oldOwnerName;
    public String newOwnerName;
    public String oldKingdomId;
    public String oldKingdomName;
    public String newKingdomId;
    public String newKingdomName;
    public java.util.List<UUID> participants = new java.util.ArrayList<>();
    public java.util.List<String> participantNames = new java.util.ArrayList<>();
}
