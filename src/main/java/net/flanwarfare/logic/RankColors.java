package net.flanwarfare.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Colour maths for rank tags (pure; no Minecraft classes). Colours are 0xRRGGBB ints, which
 * Minecraft 1.16.5 text components accept directly (RGB chat colours exist since 1.16).
 *
 * <p>Animated effects are frames: the caller passes a phase that advances on a controlled
 * interval, and sends the new frame only where it can be updated (nametags and labels). Chat
 * lines cannot change after they are sent, so chat shows one frame.
 */
public final class RankColors {

    public enum Effect { NONE, BOLD, GLOW, RAINBOW, PULSE, WAVE, OBFUSCATED }

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

    private RankColors() {
    }

    /** Parses a standard colour name or #RRGGBB. Throws IllegalArgumentException otherwise. */
    public static int parse(String text, boolean allowHex) {
        String s = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        Integer named = NAMED.get(s);
        if (named != null) {
            return named;
        }
        if (s.matches("#[0-9a-f]{6}")) {
            if (!allowHex) {
                throw new IllegalArgumentException("hex colours are disabled on this server; use a colour name");
            }
            return Integer.parseInt(s.substring(1), 16);
        }
        throw new IllegalArgumentException("unknown colour '" + text + "' (use a name like gold, dark_green, or #RRGGBB)");
    }

    public static String format(int rgb) {
        for (Map.Entry<String, Integer> e : NAMED.entrySet()) {
            if (e.getValue() == rgb) {
                return e.getKey();
            }
        }
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** Linear interpolation across several colour stops, one colour per character. */
    public static int[] gradient(List<Integer> stops, int length) {
        int[] out = new int[Math.max(0, length)];
        if (length == 0 || stops.isEmpty()) {
            return out;
        }
        if (stops.size() == 1 || length == 1) {
            java.util.Arrays.fill(out, stops.get(0));
            return out;
        }
        for (int i = 0; i < length; i++) {
            double t = (double) i / (length - 1) * (stops.size() - 1);
            int seg = Math.min(stops.size() - 2, (int) Math.floor(t));
            out[i] = lerp(stops.get(seg), stops.get(seg + 1), t - seg);
        }
        return out;
    }

    public static int lerp(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Scales brightness (factor 0..1 darkens, >1 lightens towards white). */
    public static int brightness(int rgb, double factor) {
        if (factor >= 1.0) {
            return lerp(rgb, 0xFFFFFF, Math.min(1.0, factor - 1.0));
        }
        return lerp(0x000000, rgb, Math.max(0.0, factor));
    }

    public static int hsv(double hue, double saturation, double value) {
        double h = (hue % 1.0 + 1.0) % 1.0 * 6.0;
        int sector = (int) Math.floor(h);
        double f = h - sector;
        double p = value * (1 - saturation);
        double q = value * (1 - saturation * f);
        double t = value * (1 - saturation * (1 - f));
        double r;
        double g;
        double b;
        switch (sector) {
            case 0: r = value; g = t; b = p; break;
            case 1: r = q; g = value; b = p; break;
            case 2: r = p; g = value; b = t; break;
            case 3: r = p; g = q; b = value; break;
            case 4: r = t; g = p; b = value; break;
            default: r = value; g = p; b = q; break;
        }
        return ((int) Math.round(r * 255) << 16) | ((int) Math.round(g * 255) << 8) | (int) Math.round(b * 255);
    }

    /** True for effects whose appearance changes over time. */
    public static boolean animated(Effect effect) {
        return effect == Effect.RAINBOW || effect == Effect.PULSE || effect == Effect.WAVE;
    }

    /**
     * Per-character colours for a tag of {@code length} characters at animation frame
     * {@code phase} (0..1, wraps). {@code stops} is one colour (solid) or several (gradient).
     */
    public static int[] colors(List<Integer> stops, Effect effect, int length, double phase) {
        List<Integer> base = stops.isEmpty() ? Collections.singletonList(0xFFFFFF) : stops;
        int[] out;
        switch (effect) {
            case RAINBOW:
                out = new int[length];
                for (int i = 0; i < length; i++) {
                    out[i] = hsv(phase + (double) i / Math.max(8, length), 0.75, 1.0);
                }
                return out;
            case WAVE: {
                // Cyclic gradient sliding left to right.
                List<Integer> cycle = new ArrayList<>(base);
                if (cycle.size() == 1) {
                    cycle.add(brightness(cycle.get(0), 1.5));
                }
                cycle.add(cycle.get(0));
                int resolution = 64;
                int[] ring = gradient(cycle, resolution + 1);
                out = new int[length];
                for (int i = 0; i < length; i++) {
                    double pos = ((double) i / Math.max(6, length) - phase) % 1.0;
                    if (pos < 0) {
                        pos += 1.0;
                    }
                    out[i] = ring[(int) Math.round(pos * resolution)];
                }
                return out;
            }
            case PULSE: {
                double factor = 0.7 + 0.45 * (0.5 + 0.5 * Math.sin(phase * 2 * Math.PI));
                out = gradient(base, length);
                for (int i = 0; i < length; i++) {
                    out[i] = brightness(out[i], factor);
                }
                return out;
            }
            case GLOW:
                out = gradient(base, length);
                for (int i = 0; i < length; i++) {
                    out[i] = brightness(out[i], 1.35);
                }
                return out;
            default:
                return gradient(base, length);
        }
    }
}
