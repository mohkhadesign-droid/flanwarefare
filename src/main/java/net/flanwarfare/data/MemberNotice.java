package net.flanwarfare.data;

import java.util.UUID;

/** A membership notice waiting for an offline player (one per claim, net effect only). */
public final class MemberNotice {
    /** ADDED, REMOVED, CHANGED or CONQUERED. */
    public String type;
    public UUID claimId;
    public String label;
    public String ownerName;
    public String dimension;
    public int x;
    public int z;
    public long at;
    /** Readable permission differences, e.g. "You may now enter this claim." */
    public java.util.List<String> details = new java.util.ArrayList<>();
}
