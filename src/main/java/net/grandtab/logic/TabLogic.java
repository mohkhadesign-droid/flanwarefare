package net.grandtab.logic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Minecraft-independent rules of the tab list (tested by TabLogicSelfTest). */
public final class TabLogic {
    private TabLogic() {
    }

    /** The 16 Minecraft chat colours by their standard names. */
    public static final Map<String, Integer> NAMED;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("black", 0x000000);
        m.put("dark_blue", 0x0000AA);
        m.put("dark_green", 0x00AA00);
        m.put("dark_aqua", 0x00AAAA);
        m.put("dark_red", 0xAA0000);
        m.put("dark_purple", 0xAA00AA);
        m.put("gold", 0xFFAA00);
        m.put("gray", 0xAAAAAA);
        m.put("dark_gray", 0x555555);
        m.put("blue", 0x5555FF);
        m.put("green", 0x55FF55);
        m.put("aqua", 0x55FFFF);
        m.put("red", 0xFF5555);
        m.put("light_purple", 0xFF55FF);
        m.put("yellow", 0xFFFF55);
        m.put("white", 0xFFFFFF);
        NAMED = Collections.unmodifiableMap(m);
    }

    /** Parses a colour name (gold, dark_green ...) or #RRGGBB; throws IllegalArgumentException. */
    public static int parseColor(String text) {
        String s = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        Integer named = NAMED.get(s);
        if (named != null) {
            return named;
        }
        if (s.matches("#[0-9a-f]{6}")) {
            return Integer.parseInt(s.substring(1), 16);
        }
        throw new IllegalArgumentException("unknown colour '" + text + "' (use a name like gold or #RRGGBB)");
    }

    /** Like parseColor, but returns the fallback for empty or invalid input. */
    public static int colorOr(String text, int fallback) {
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            return parseColor(text);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static String formatColor(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /**
     * Rank prefix from a format such as "[{kingdom} {role}]". Placeholders: {kingdom} (the
     * kingdom tag, e.g. K1), {kingdom_name} (e.g. Kingdom I), {role} (e.g. MONARCH).
     */
    public static String prefix(String format, String kingdomTag, String kingdomName, String roleName) {
        return format.replace("{kingdom_name}", kingdomName)
                .replace("{kingdom}", kingdomTag)
                .replace("{role}", roleName);
    }

    /**
     * Scoreboard team used only for sorting (vanilla sorts the tab list by team name, then by
     * player name). Kingdom order first, then role order; unranked players last.
     */
    public static String sortTeam(int kingdomIndex, int roleOrder) {
        return String.format(Locale.ROOT, "gt%02d%02d", Math.max(0, Math.min(98, kingdomIndex)), Math.max(0, Math.min(99, roleOrder)));
    }

    public static final String UNRANKED_TEAM = "gt9999";

    public static boolean isSortTeam(String name) {
        return name != null && name.matches("gt\\d{4}");
    }

    /** Colour of the optional ping text: green, yellow, gold, red. */
    public static int pingColor(int ms) {
        if (ms < 0) {
            return 0xAAAAAA;
        }
        if (ms < 150) {
            return 0x55FF55;
        }
        if (ms < 300) {
            return 0xFFFF55;
        }
        if (ms < 600) {
            return 0xFFAA00;
        }
        return 0xFF5555;
    }

    /** Spaces that render as a horizontal rule when struck through. */
    public static String rule(int width) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(0, Math.min(200, width)); i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /** Fills {online} and {max} in the player-count line. */
    public static String count(String format, int online, int max) {
        return format.replace("{online}", Integer.toString(online)).replace("{max}", Integer.toString(max));
    }

    /** Lower-case id for kingdoms and roles; letters, digits, - and _ only. */
    public static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_\\-]{1,16}");
    }
}
