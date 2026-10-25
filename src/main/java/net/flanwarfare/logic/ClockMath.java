package net.flanwarfare.logic;

import java.util.Locale;

/**
 * Pure conversion between wall-clock style "HH:MM" strings and Minecraft day ticks.
 *
 * <p>Minecraft's day is 24,000 ticks long and tick 0 is 06:00 (sunrise). One in-game hour is
 * 1,000 ticks and one in-game minute is 1000/60 (about 16.67) ticks. Therefore:
 * <pre>
 *   06:00 -> 0      12:00 -> 6000     15:00 -> 9000
 *   18:00 -> 12000  00:00 -> 18000    05:00 -> 23000
 * </pre>
 *
 * <p>A window is half-open: a tick-of-day {@code t} is inside {@code [start, end)}. If
 * {@code start > end} the window wraps past tick 24000/0 (06:00). A window with
 * {@code start == end} is treated as empty and is rejected by configuration validation.
 */
public final class ClockMath {
    public static final int DAY_TICKS = 24000;

    private ClockMath() {
    }

    /** Parses "HH:MM" (24-hour) into a tick of day. Throws IllegalArgumentException if malformed. */
    public static int parseClock(String text) {
        if (text == null) {
            throw new IllegalArgumentException("time is missing");
        }
        String trimmed = text.trim();
        String[] parts = trimmed.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("expected HH:MM but got '" + text + "'");
        }
        int hour;
        int minute;
        try {
            hour = Integer.parseInt(parts[0]);
            minute = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected HH:MM but got '" + text + "'", e);
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("hour must be 0-23 and minute 0-59, got '" + text + "'");
        }
        int minutesSinceSunrise = ((hour - 6 + 24) % 24) * 60 + minute;
        return (int) (Math.round(minutesSinceSunrise * 1000.0 / 60.0) % DAY_TICKS);
    }

    /** Formats a tick of day (or any absolute tick) as "HH:MM". */
    public static String formatClock(long ticks) {
        int tod = tickOfDay(ticks);
        int totalMinutes = (int) (tod * 60L / 1000L);
        int hour = (6 + totalMinutes / 60) % 24;
        int minute = totalMinutes % 60;
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    /** Tick within the current day, always in [0, 24000), also for negative absolute times. */
    public static int tickOfDay(long absoluteDayTime) {
        return (int) Math.floorMod(absoluteDayTime, (long) DAY_TICKS);
    }

    public static boolean inWindow(int tickOfDay, int start, int end) {
        if (start == end) {
            return false;
        }
        if (start < end) {
            return tickOfDay >= start && tickOfDay < end;
        }
        return tickOfDay >= start || tickOfDay < end;
    }

    public static boolean inWindowAbsolute(long absoluteDayTime, int start, int end) {
        return inWindow(tickOfDay(absoluteDayTime), start, end);
    }

    /** Length of the window in ticks. */
    public static int windowLength(int start, int end) {
        return Math.floorMod(end - start, DAY_TICKS);
    }

    /**
     * Absolute day-time at which the window containing {@code absoluteDayTime} began. This value
     * uniquely identifies one raid window, so it is used as the window id. Only meaningful while
     * {@link #inWindowAbsolute} is true.
     */
    public static long windowStart(long absoluteDayTime, int start, int end) {
        int tod = tickOfDay(absoluteDayTime);
        int delta = Math.floorMod(tod - start, DAY_TICKS);
        return absoluteDayTime - delta;
    }

    /**
     * Ticks until the next transition (window opening if currently closed, closing if open),
     * assuming time advances naturally from {@code absoluteDayTime}.
     */
    public static long ticksUntilChange(long absoluteDayTime, int start, int end) {
        if (start == end) {
            return Long.MAX_VALUE;
        }
        int tod = tickOfDay(absoluteDayTime);
        if (inWindow(tod, start, end)) {
            long startAbs = windowStart(absoluteDayTime, start, end);
            return startAbs + windowLength(start, end) - absoluteDayTime;
        }
        return Math.floorMod(start - tod, DAY_TICKS);
    }

    /**
     * Limits a night skip so it can never advance time into or past a raid window. If a raid is
     * active now, time does not move. If a raid would start before {@code target}, time stops
     * at the raid start.
     */
    public static long clampSleepTarget(long now, long target, int start, int end) {
        if (target <= now || start == end) {
            return target;
        }
        if (inWindowAbsolute(now, start, end)) {
            return now;
        }
        long nextStart = now + ticksUntilChange(now, start, end);
        return Math.min(target, nextStart);
    }
}
