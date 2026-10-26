package net.flanwarfare.ranks;

import net.flanwarfare.config.WarConfig;
import net.flanwarfare.data.RankDef;
import net.flanwarfare.logic.RankColors;
import net.flanwarfare.logic.RankRules;
import net.minecraft.util.text.Color;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.ForgeHooks;

import java.util.Locale;

/**
 * Builds rank tags as vanilla RGB text components (Minecraft 1.16 supports per-character RGB
 * colours in chat, nametags, team prefixes and custom names), so no client mod is needed.
 */
public final class RankRenderer {
    private RankRenderer() {
    }

    public static RankColors.Effect effect(RankDef rank) {
        try {
            RankColors.Effect effect = RankColors.Effect.valueOf(rank.effect.toUpperCase(Locale.ROOT));
            // An effect the server disabled after it was chosen renders as a plain colour.
            return RankService.effectAllowed(effect) ? effect : RankColors.Effect.NONE;
        } catch (IllegalArgumentException | NullPointerException e) {
            return RankColors.Effect.NONE;
        }
    }

    /** Visible text of a tag: prefix, name and suffix separated by spaces. */
    public static String text(RankDef rank) {
        StringBuilder sb = new StringBuilder();
        if (rank.prefix != null && !rank.prefix.isEmpty()) {
            sb.append(rank.prefix).append(' ');
        }
        sb.append(rank.name);
        if (rank.suffix != null && !rank.suffix.isEmpty()) {
            sb.append(' ').append(rank.suffix);
        }
        return sb.toString();
    }

    /** The tag at animation phase {@code phase} (0..1). Static effects ignore the phase. */
    public static IFormattableTextComponent tag(RankDef rank, double phase) {
        RankColors.Effect effect = effect(rank);
        String text = text(rank);
        int[] codePoints = text.codePoints().toArray();
        int[] colors = RankColors.colors(rank.colors, effect, codePoints.length, phase);
        boolean bold = effect == RankColors.Effect.BOLD || effect == RankColors.Effect.GLOW;
        boolean obfuscated = effect == RankColors.Effect.OBFUSCATED;
        int nameStart = rank.prefix == null || rank.prefix.isEmpty() ? 0 : rank.prefix.codePointCount(0, rank.prefix.length()) + 1;
        int nameEnd = nameStart + rank.name.codePointCount(0, rank.name.length());

        IFormattableTextComponent root = new StringTextComponent("");
        int i = 0;
        while (i < codePoints.length) {
            // Merge runs of equal colour (and equal obfuscation) into one component.
            int j = i + 1;
            boolean scramble = obfuscated && i >= nameStart && i < nameEnd;
            while (j < codePoints.length && colors[j] == colors[i]
                    && (obfuscated && j >= nameStart && j < nameEnd) == scramble) {
                j++;
            }
            IFormattableTextComponent part = new StringTextComponent(new String(codePoints, i, j - i));
            final int rgb = colors[i];
            part.modifyStyle(style -> style.setColor(Color.fromInt(rgb)));
            if (bold) {
                part.mergeStyle(TextFormatting.BOLD);
            }
            if (scramble) {
                part.mergeStyle(TextFormatting.OBFUSCATED); // decorations stay readable
            }
            root.append(part);
            i = j;
        }
        return root;
    }

    /** LEFT placement: the tag followed by the configured separator, as a team prefix. */
    public static IFormattableTextComponent leftPrefix(RankDef rank, double phase) {
        IFormattableTextComponent prefix = tag(rank, phase);
        String separator = WarConfig.RANK_SEPARATOR.get();
        if (!separator.isEmpty()) {
            prefix.append(new StringTextComponent(separator).mergeStyle(TextFormatting.DARK_GRAY));
        }
        return prefix;
    }

    /** A chat line built from the configured format; the message keeps Forge's link handling. */
    public static ITextComponent chat(RankDef rank, double phase, String playerName, String message) {
        String format = WarConfig.RANK_CHAT_FORMAT.get();
        if (!RankRules.validFormat(format)) {
            format = "[{rank}] {player}: {message}";
        }
        IFormattableTextComponent line = new StringTextComponent("");
        for (RankRules.Token token : RankRules.tokenize(format)) {
            if (!token.placeholder) {
                line.append(new StringTextComponent(token.text).mergeStyle(TextFormatting.GRAY));
            } else if (token.text.equals("rank")) {
                line.append(tag(rank, phase));
            } else if (token.text.equals("player")) {
                line.append(new StringTextComponent(playerName).mergeStyle(TextFormatting.WHITE));
            } else {
                line.append(ForgeHooks.newChatWithLinks(message));
            }
        }
        return line;
    }
}
