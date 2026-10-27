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

    /**
     * Longest pattern id accepted on the wire, in characters. Vanilla ids are 1 to 3
     * characters ("bs", "cre"), but mods register patterns through Forge with free-form ids
     * that are often longer than 16 (for example "modname_pattern_name"). The client writes and
     * the server reads with this same limit; anything longer is refused, never truncated.
     */
    public static final int MAX_ID_LENGTH = 64;

    /** The base layer id; never a selectable pattern. */
    public static final String BASE = "b";

    /**
     * Returns null if the design is acceptable, otherwise the reason.
     *
     * @param baseColor   dye id of the base (0..15)
     * @param hashes      pattern ids, bottom to top
     * @param colors      dye id per pattern
     * @param known       every valid pattern id of this game (vanilla plus installed mods)
     * @param itemLocked  pattern ids that need a banner pattern item (vanilla's six plus every
     *                    pattern that some installed mod's banner pattern item applies)
     * @param unlocked    item-locked pattern ids the player may use (the item is in the
     *                    designer's Banner Pattern Item slot, or the flag already had that
     *                    pattern before this edit)
     */
    public static String check(int baseColor, List<String> hashes, List<Integer> colors, Collection<String> known,
                               Collection<String> itemLocked, Collection<String> unlocked) {
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
            if (h == null || h.length() > MAX_ID_LENGTH || BASE.equals(h) || !known.contains(h)) {
                return "Unknown banner pattern.";
            }
            if (c < 0 || c > 15) {
                return "Invalid dye colour.";
            }
            if ((ITEM_PATTERNS.contains(h) || itemLocked.contains(h)) && !unlocked.contains(h)) {
                return "That pattern needs its banner pattern item in the Banner Pattern Item slot.";
            }
        }
        return null;
    }

    /**
     * Shortens text to at most {@code maxChars} UTF-16 characters for a network string, never
     * splitting a surrogate pair. {@code PacketBuffer.readString(n)} refuses more than n
     * characters, so the writer clips with the same n.
     */
    public static String clip(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        int end = maxChars;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
