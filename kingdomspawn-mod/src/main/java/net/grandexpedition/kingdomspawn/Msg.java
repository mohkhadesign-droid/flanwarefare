package net.grandexpedition.kingdomspawn;

import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;

/** Chat helpers. */
public final class Msg {
    private Msg() {
    }

    public static IFormattableTextComponent prefix() {
        return new StringTextComponent("[Kingdoms] ").mergeStyle(TextFormatting.GOLD);
    }

    public static ITextComponent info(String text) {
        return prefix().appendSibling(new StringTextComponent(text).mergeStyle(TextFormatting.YELLOW));
    }

    public static ITextComponent ok(String text) {
        return prefix().appendSibling(new StringTextComponent(text).mergeStyle(TextFormatting.GREEN));
    }

    public static ITextComponent error(String text) {
        return prefix().appendSibling(new StringTextComponent(text).mergeStyle(TextFormatting.RED));
    }

    /** A clickable chat button that runs a command. */
    public static IFormattableTextComponent button(String label, String command) {
        return new StringTextComponent("[" + label + "]").mergeStyle(TextFormatting.AQUA)
                .modifyStyle(s -> s.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new StringTextComponent(command))));
    }

    public static void send(ServerPlayerEntity player, ITextComponent text) {
        if (player != null) {
            player.sendStatusMessage(text, false);
        }
    }

    public static void send(CommandSource source, ITextComponent text) {
        source.sendFeedback(text, false);
    }
}
