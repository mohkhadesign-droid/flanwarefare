package net.flanwarfare.data;

import java.util.ArrayList;
import java.util.List;

/** One custom rank. The id is stable; the display name can be renamed freely. */
public final class RankDef {
    public String id;
    public String name;
    /** One colour = solid; two or more = gradient (0xRRGGBB). */
    public List<Integer> colors = new ArrayList<>();
    /** RankColors.Effect name. */
    public String effect = "NONE";
    public String prefix = "";
    public String suffix = "";
    /** RankRules.Position name, or null to use the global default. */
    public String position;
    public long createdAt;
}
