package net.flanwarfare.logic;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Validation of a banner design submitted by the Flag Designer (pure; tested by LogicSelfTest).
 * The server never trusts the client: colours, pattern ids, the layer count and the use of
 * item-locked patterns are all checked here before anything is saved.
 */
public final class DesignRules {
    private DesignRules() {
    }

    /** Vanilla 1.16.5 survival limit of pattern layers on a banner (the loom's limit). */
    public static final int MAX_LAYERS = 6;

    /** Pattern ids that need a banner pattern item in a loom: creeper, skull, flower, mojang, globe, piglin. */
    public static final List<String> ITEM_PATTERNS = Collections.unmodifiableList(
            Arrays.asList("cre", "sku", "flo", "moj", "glb", "pig"));

    /** The base layer id; never a selectable pattern. */
    public static final String BASE = "b";

    /**
     * Returns null if the design is acceptable, otherwise the reason.
     *
     * @param baseColor   dye id of the base (0..15)
     * @param hashes      pattern ids, bottom to top
     * @param colors      dye id per pattern
     * @param known       every valid pattern id of this Minecraft version
     * @param unlocked    item-locked pattern ids the player may use (carries the item, or the
     *                    flag already had that pattern before this edit)
     */
    public static String check(int baseColor, List<String> hashes, List<Integer> colors, Collection<String> known,
                               Collection<String> unlocked) {
        if (baseColor < 0 || baseColor > 15) {
            return "Invalid base colour.";
        }
        if (hashes.size() != colors.size()) {
            return "Malformed design.";
        }
        if (hashes.size() > MAX_LAYERS) {
            return "A banner can have at most " + MAX_LAYERS + " pattern layers.";
        }
        for (int i = 0; i < hashes.size(); i++) {
            String h = hashes.get(i);
            int c = colors.get(i);
            if (h == null || BASE.equals(h) || !known.contains(h)) {
                return "Unknown banner pattern.";
            }
            if (c < 0 || c > 15) {
                return "Invalid dye colour.";
            }
            if (ITEM_PATTERNS.contains(h) && !unlocked.contains(h)) {
                return "That pattern needs its banner pattern item in your inventory.";
            }
        }
        return null;
    }
}
