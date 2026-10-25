package net.flanwarfare.data;

import java.util.UUID;

/**
 * One coalesced raid event kept for an offline owner. Repeated events of the same type on the
 * same claim within a short span only increase {@link #count}, so the history stays small.
 */
public final class AlertEntry {
    public String type;
    public UUID claimId;
    public String label;
    public String dimension;
    public int x;
    public int z;
    public long firstAt;
    public long lastAt;
    public int count;
}
