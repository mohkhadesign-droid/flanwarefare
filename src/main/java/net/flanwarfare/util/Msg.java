package net.flanwarfare.util;

import net.flanwarfare.config.WarConfig;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;

/** Small helpers for chat output. All text is plain vanilla components (no client mod needed). */
public final class Msg {
    private static final String PREFIX = "[War] ";

    private Msg() {
    }

    public static IFormattableTextComponent text(String s, TextFormatting color) {
        return new StringTextComponent(s).mergeStyle(color);
    }

    public static IFormattableTextComponent info(String s) {
        return text(PREFIX + s, TextFormatting.GOLD);
    }

    public static IFormattableTextComponent ok(String s) {
        return text(PREFIX + s, TextFormatting.GREEN);
    }

    public static IFormattableTextComponent warn(String s) {
        return text(PREFIX + s, TextFormatting.YELLOW);
    }

    public static IFormattableTextComponent error(String s) {
        return text(PREFIX + s, TextFormatting.RED);
    }

    public static void send(ServerPlayerEntity player, ITextComponent message) {
        if (player != null) {
            player.sendMessage(message, Util.DUMMY_UUID);
        }
    }

    public static void actionBar(ServerPlayerEntity player, ITextComponent message) {
        if (player != null) {
            player.sendStatusMessage(message, true);
        }
    }

    public static void broadcast(MinecraftServer server, ITextComponent message) {
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            player.sendMessage(message, Util.DUMMY_UUID);
        }
    }

    public static void admins(MinecraftServer server, ITextComponent message) {
        int level = adminLevel();
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissionLevel(level)) {
                player.sendMessage(message, Util.DUMMY_UUID);
            }
        }
    }

    /** Admin permission level; falls back to 2 while the server config is not loaded yet. */
    public static int adminLevel() {
        try {
            return WarConfig.ADMIN_PERMISSION_LEVEL.get();
        } catch (IllegalStateException notLoaded) {
            return 2;
        }
    }

    /** Human duration such as "9m 30s". */
    public static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return h + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }
}
