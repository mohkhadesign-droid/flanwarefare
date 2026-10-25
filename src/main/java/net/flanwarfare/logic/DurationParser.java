package net.flanwarfare.logic;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses real-time durations such as "24h", "90m", "1d12h", "45s", "0". */
public final class DurationParser {
    private static final Pattern PART = Pattern.compile("(\\d+)([dhms])");

    private DurationParser() {
    }

    /** Milliseconds; throws IllegalArgumentException for malformed input. */
    public static long parseMillis(String text) {
        if (text == null) {
            throw new IllegalArgumentException("duration is missing");
        }
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.equals("0")) {
            return 0L;
        }
        Matcher m = PART.matcher(s);
        int end = 0;
        long total = 0;
        boolean any = false;
        while (m.find()) {
            if (m.start() != end) {
                throw new IllegalArgumentException("cannot read duration '" + text + "' (use e.g. 24h, 90m, 1d12h)");
            }
            long value = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "d":
                    total += value * 86_400_000L;
                    break;
                case "h":
                    total += value * 3_600_000L;
                    break;
                case "m":
                    total += value * 60_000L;
                    break;
                default:
                    total += value * 1_000L;
                    break;
            }
            end = m.end();
            any = true;
        }
        if (!any || end != s.length()) {
            throw new IllegalArgumentException("cannot read duration '" + text + "' (use e.g. 24h, 90m, 1d12h)");
        }
        if (total > 365L * 86_400_000L) {
            throw new IllegalArgumentException("duration too long (maximum 365d)");
        }
        return total;
    }
}
