package net.flanwarfare.data;

import java.util.ArrayList;
import java.util.List;

/** A competitive season: explicit boundaries set by administrators, never by Minecraft days. */
public final class Season {
    public String id;
    public String name;
    public long startedAt;
    /** 0 while the season is running. Ended seasons record no new events. */
    public long endedAt;
    public List<WarEvent> events = new ArrayList<>();

    public boolean isActive() {
        return endedAt == 0L;
    }
}
