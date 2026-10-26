package net.flanwarfare.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure rules for kingdom ranks: text validation, display position priority, chat format. */
public final class RankRules {

    public enum Position { LEFT, ABOVE, BELOW }

    private RankRules() {
    }

    /** Returns null if the rank name is acceptable, otherwise the reason. */
    public static String checkName(String name, int maxLength) {
        if (name == null || name.trim().isEmpty()) {
            return "The rank name is empty.";
        }
        String n = name.trim();
        if (n.length() > maxLength) {
            return "Rank names can be at most " + maxLength + " characters.";
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '\'' || c == '.' || c == '&')) {
                return "Rank names may use letters, digits, spaces and - _ ' . & only.";
            }
        }
        if (n.contains("  ")) {
            return "Rank names cannot contain double spaces.";
        }
        return null;
    }

    /**
     * Returns null if a prefix/suffix decoration is acceptable. Control characters and the
     * section sign (Minecraft's legacy formatting code) are always refused, so decorations
     * cannot inject formatting. {@code allowedSymbols}, if not empty, restricts every
     * non-alphanumeric, non-space character to that set.
     */
    public static String checkDecoration(String text, int maxLength, String allowedSymbols) {
        if (text == null) {
            return "Missing text.";
        }
        if (text.codePointCount(0, text.length()) > maxLength) {
            return "Decorations can be at most " + maxLength + " characters.";
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || cp == 0x00A7 || Character.getType(cp) == Character.FORMAT
                    || Character.getType(cp) == Character.PRIVATE_USE || Character.getType(cp) == Character.UNASSIGNED) {
                return "That decoration contains characters that are not allowed.";
            }
            if (cp > 0xFFFF) {
                return "Minecraft 1.16.5 cannot display that symbol; use one from the basic Unicode range.";
            }
            if (!allowedSymbols.isEmpty() && !Character.isLetterOrDigit(cp) && cp != ' '
                    && allowedSymbols.indexOf(cp) < 0) {
                return "That symbol is not allowed on this server. Allowed symbols: " + allowedSymbols;
            }
        }
        return null;
    }

    public static Position parsePosition(String text) {
        try {
            return Position.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Effective position: player override, then rank default, then global default. Positions
     * the server disabled fall back to LEFT.
     */
    public static Position resolve(Position playerOverride, Position rankDefault, Position globalDefault,
                                   boolean overridesAllowed, boolean aboveAllowed, boolean belowAllowed) {
        Position p = overridesAllowed && playerOverride != null ? playerOverride
                : rankDefault != null ? rankDefault : globalDefault;
        if (p == null) {
            p = Position.LEFT;
        }
        if (p == Position.ABOVE && !aboveAllowed || p == Position.BELOW && !belowAllowed) {
            return Position.LEFT;
        }
        return p;
    }

    /** One piece of a chat format: literal text or a placeholder ({rank}, {player}, {message}). */
    public static final class Token {
        public final String text;
        public final boolean placeholder;

        Token(String text, boolean placeholder) {
            this.text = text;
            this.placeholder = placeholder;
        }
    }

    /** Splits a format such as "[{rank}] {player}: {message}" into literal and placeholder tokens. */
    public static List<Token> tokenize(String format) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        StringBuilder literal = new StringBuilder();
        while (i < format.length()) {
            boolean matched = false;
            for (String name : new String[]{"rank", "player", "message"}) {
                String ph = "{" + name + "}";
                if (format.startsWith(ph, i)) {
                    if (literal.length() > 0) {
                        tokens.add(new Token(literal.toString(), false));
                        literal.setLength(0);
                    }
                    tokens.add(new Token(name, true));
                    i += ph.length();
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                literal.append(format.charAt(i));
                i++;
            }
        }
        if (literal.length() > 0) {
            tokens.add(new Token(literal.toString(), false));
        }
        return tokens;
    }

    /** True if the format shows the rank, the player and the message exactly once each. */
    public static boolean validFormat(String format) {
        int rank = 0;
        int player = 0;
        int message = 0;
        for (Token t : tokenize(format)) {
            if (t.placeholder) {
                if (t.text.equals("rank")) {
                    rank++;
                } else if (t.text.equals("player")) {
                    player++;
                } else {
                    message++;
                }
            }
        }
        return rank == 1 && player == 1 && message == 1;
    }
}
