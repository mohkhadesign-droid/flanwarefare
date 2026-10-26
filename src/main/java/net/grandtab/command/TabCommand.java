package net.grandtab.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.grandtab.GrandTab;
import net.grandtab.TabService;
import net.grandtab.config.ConfigStore;
import net.grandtab.config.TabConfig;
import net.grandtab.logic.TabLogic;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.ISuggestionProvider;
import net.minecraft.command.arguments.GameProfileArgument;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * /tabrank set &lt;player&gt; &lt;kingdom&gt; &lt;role&gt;   give a player a kingdom rank (works for offline players too)
 * /tabrank clear &lt;player&gt;                  remove a player's rank
 * /tabrank info &lt;player&gt;                   rank, resource pack status, sorting team
 * /tabrank list                            every assigned player, by kingdom and role
 * /tabrank kingdoms                        kingdoms and roles with their ids and colours
 * /tabrank kingdom &lt;id&gt; color|name|tag &lt;value&gt;
 * /tabrank role &lt;id&gt; name|color &lt;value&gt; | role &lt;id&gt; bold true|false
 * /tabrank title|subtitle|discord|website &lt;text&gt;
 * /tabrank enable | disable | reload | version
 * </pre>
 * Changes are saved to config/grandtab immediately and shown within a second.
 */
public final class TabCommand {
    private TabCommand() {
    }

    private static final SuggestionProvider<CommandSource> KINGDOMS = (ctx, b) -> {
        List<String> ids = new ArrayList<>();
        for (TabConfig.Kingdom k : GrandTab.store().config().kingdoms) {
            ids.add(k.id);
        }
        return ISuggestionProvider.suggest(ids, b);
    };

    private static final SuggestionProvider<CommandSource> ROLES = (ctx, b) -> {
        List<String> ids = new ArrayList<>();
        for (TabConfig.Role r : GrandTab.store().config().roles) {
            ids.add(r.id);
        }
        return ISuggestionProvider.suggest(ids, b);
    };

    private static final SuggestionProvider<CommandSource> COLORS = (ctx, b) ->
            ISuggestionProvider.suggest(new ArrayList<>(TabLogic.NAMED.keySet()), b);

    private static int level() {
        try {
            return GrandTab.store().config().commandPermissionLevel;
        } catch (RuntimeException e) {
            return 2;
        }
    }

    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        dispatcher.register(Commands.literal("tabrank")
                .requires(src -> src.hasPermissionLevel(level()))
                .then(Commands.literal("set").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("kingdom", StringArgumentType.word()).suggests(KINGDOMS)
                                .then(Commands.argument("role", StringArgumentType.word()).suggests(ROLES)
                                        .executes(TabCommand::set)))))
                .then(Commands.literal("clear").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(TabCommand::clear)))
                .then(Commands.literal("info").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(TabCommand::info)))
                .then(Commands.literal("list").executes(TabCommand::list))
                .then(Commands.literal("kingdoms").executes(TabCommand::kingdoms))
                .then(Commands.literal("kingdom").then(Commands.argument("kingdom", StringArgumentType.word()).suggests(KINGDOMS)
                        .then(Commands.literal("color").then(Commands.argument("value", StringArgumentType.word()).suggests(COLORS)
                                .executes(ctx -> editKingdom(ctx, "color"))))
                        .then(Commands.literal("name").then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(ctx -> editKingdom(ctx, "name"))))
                        .then(Commands.literal("tag").then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(ctx -> editKingdom(ctx, "tag"))))))
                .then(Commands.literal("role").then(Commands.argument("role", StringArgumentType.word()).suggests(ROLES)
                        .then(Commands.literal("name").then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(ctx -> editRole(ctx, "name"))))
                        .then(Commands.literal("color").then(Commands.argument("value", StringArgumentType.word()).suggests(COLORS)
                                .executes(ctx -> editRole(ctx, "color"))))
                        .then(Commands.literal("bold").then(Commands.argument("value", StringArgumentType.word())
                                .suggests((c, b) -> ISuggestionProvider.suggest(Arrays.asList("true", "false"), b))
                                .executes(ctx -> editRole(ctx, "bold"))))))
                .then(text("title", (cfg, v) -> cfg.header.title = v))
                .then(text("subtitle", (cfg, v) -> cfg.header.subtitle = v))
                .then(text("discord", (cfg, v) -> footerValue(cfg, 0, v)))
                .then(text("website", (cfg, v) -> footerValue(cfg, 1, v)))
                .then(Commands.literal("enable").executes(ctx -> setEnabled(ctx, true)))
                .then(Commands.literal("disable").executes(ctx -> setEnabled(ctx, false)))
                .then(Commands.literal("reload").executes(TabCommand::reload))
                .then(Commands.literal("version").executes(ctx -> ok(ctx, "Grand Tab List " + GrandTab.version()))));
    }

    interface TextEdit {
        void apply(TabConfig cfg, String value);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSource> text(String name, TextEdit edit) {
        return Commands.literal(name).then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
            edit.apply(GrandTab.store().config(), StringArgumentType.getString(ctx, "text"));
            return saved(ctx, name + " set.");
        }));
    }

    private static void footerValue(TabConfig cfg, int index, String value) {
        while (cfg.footer.lines.size() <= index) {
            cfg.footer.lines.add(new TabConfig.FooterLine(index == 0 ? "DISCORD: " : "WEBSITE: ", "#AAAAAA", "", "#55FFFF", true));
        }
        cfg.footer.lines.get(index).value = value;
    }

    // ------------------------------------------------------------------ helpers

    private static int ok(CommandContext<CommandSource> ctx, String text) {
        ctx.getSource().sendFeedback(new StringTextComponent("[Tab] " + text).mergeStyle(TextFormatting.GREEN), true);
        return 1;
    }

    private static int info(CommandContext<CommandSource> ctx, String text) {
        ctx.getSource().sendFeedback(new StringTextComponent(text).mergeStyle(TextFormatting.GOLD), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSource> ctx, String text) {
        ctx.getSource().sendErrorMessage(new StringTextComponent("[Tab] " + text));
        return 0;
    }

    private static int saved(CommandContext<CommandSource> ctx, String text) {
        if (!GrandTab.store().saveConfig()) {
            return fail(ctx, "Changed for now, but config.json could not be written (see the server log).");
        }
        refresh();
        return ok(ctx, text);
    }

    private static void refresh() {
        TabService service = GrandTab.service();
        if (service != null) {
            service.markDirty();
        }
    }

    private static GameProfile oneProfile(CommandContext<CommandSource> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            throw new SimpleCommandExceptionType(new StringTextComponent("Name exactly one player.")).create();
        }
        return profiles.iterator().next();
    }

    // ------------------------------------------------------------------ commands

    private static int set(CommandContext<CommandSource> ctx) throws CommandSyntaxException {
        GameProfile target = oneProfile(ctx);
        TabConfig cfg = GrandTab.store().config();
        TabConfig.Kingdom k = cfg.kingdom(StringArgumentType.getString(ctx, "kingdom"));
        if (k == null) {
            return fail(ctx, "Unknown kingdom. Use /tabrank kingdoms to see the ids.");
        }
        TabConfig.Role r = cfg.role(StringArgumentType.getString(ctx, "role"));
        if (r == null) {
            return fail(ctx, "Unknown role. Use /tabrank kingdoms to see the ids.");
        }
        GrandTab.store().assignments().put(target.getId(), new ConfigStore.Assignment(target.getName(), k.id, r.id));
        if (!GrandTab.store().saveAssignments()) {
            return fail(ctx, "Assigned for now, but assignments.json could not be written (see the server log).");
        }
        refresh();
        return ok(ctx, target.getName() + " is now " + TabLogic.prefix(cfg.players.prefixFormat, k.tag, k.name, r.name) + ".");
    }

    private static int clear(CommandContext<CommandSource> ctx) throws CommandSyntaxException {
        GameProfile target = oneProfile(ctx);
        if (GrandTab.store().assignments().remove(target.getId()) == null) {
            return fail(ctx, target.getName() + " has no rank.");
        }
        GrandTab.store().saveAssignments();
        refresh();
        return ok(ctx, target.getName() + " no longer has a rank.");
    }

    private static int info(CommandContext<CommandSource> ctx) throws CommandSyntaxException {
        GameProfile target = oneProfile(ctx);
        TabService service = GrandTab.service();
        ConfigStore.Assignment a = GrandTab.store().assignments().get(target.getId());
        if (a == null) {
            info(ctx, target.getName() + ": no rank.");
        } else {
            TabService.Rank rank = service == null ? null : service.rankOf(target.getId());
            info(ctx, target.getName() + ": kingdom " + a.kingdom + ", role " + a.role
                    + (rank == null ? "  (kingdom or role no longer exists in config.json: shown without a rank)" : ""));
        }
        if (service != null) {
            info(ctx, "Resource pack: " + service.packStatusOf(target.getId()));
        }
        return 1;
    }

    private static int list(CommandContext<CommandSource> ctx) {
        TabConfig cfg = GrandTab.store().config();
        Map<UUID, ConfigStore.Assignment> all = GrandTab.store().assignments();
        info(ctx, "Assigned players: " + all.size());
        for (TabConfig.Kingdom k : cfg.kingdoms) {
            for (TabConfig.Role r : sortedRoles(cfg)) {
                for (ConfigStore.Assignment a : all.values()) {
                    if (a.kingdom.equalsIgnoreCase(k.id) && a.role.equalsIgnoreCase(r.id)) {
                        IFormattableTextComponent line = new StringTextComponent(" " + TabLogic.prefix(cfg.players.prefixFormat, k.tag, k.name, r.name) + " ")
                                .mergeStyle(TextFormatting.YELLOW);
                        line.appendSibling(new StringTextComponent(a.name == null ? "?" : a.name).mergeStyle(TextFormatting.WHITE));
                        ctx.getSource().sendFeedback(line, false);
                    }
                }
            }
        }
        for (ConfigStore.Assignment a : all.values()) {
            if (cfg.kingdom(a.kingdom) == null || cfg.role(a.role) == null) {
                info(ctx, " " + a.name + ": " + a.kingdom + "/" + a.role + " (unknown kingdom or role)");
            }
        }
        TabService service = GrandTab.service();
        if (service != null && cfg.players.sortByRank) {
            for (String name : service.playersInForeignTeams()) {
                info(ctx, " Not sorted (in another mod's team): " + name);
            }
        }
        return 1;
    }

    private static List<TabConfig.Role> sortedRoles(TabConfig cfg) {
        List<TabConfig.Role> roles = new ArrayList<>(cfg.roles);
        roles.sort((a, b) -> Integer.compare(a.order, b.order));
        return roles;
    }

    private static int kingdoms(CommandContext<CommandSource> ctx) {
        TabConfig cfg = GrandTab.store().config();
        info(ctx, "Kingdoms (id, tag, name, colour):");
        for (TabConfig.Kingdom k : cfg.kingdoms) {
            info(ctx, " " + k.id + "  " + k.tag + "  " + k.name + "  " + k.color);
        }
        info(ctx, "Roles (id, name, order, bold, colour):");
        for (TabConfig.Role r : sortedRoles(cfg)) {
            info(ctx, " " + r.id + "  " + r.name + "  " + r.order + "  " + r.bold + "  " + (r.color.isEmpty() ? "kingdom colour" : r.color));
        }
        return 1;
    }

    private static int editKingdom(CommandContext<CommandSource> ctx, String field) {
        TabConfig.Kingdom k = GrandTab.store().config().kingdom(StringArgumentType.getString(ctx, "kingdom"));
        if (k == null) {
            return fail(ctx, "Unknown kingdom.");
        }
        String value = StringArgumentType.getString(ctx, "value").trim();
        if (field.equals("color")) {
            try {
                k.color = TabLogic.formatColor(TabLogic.parseColor(value));
            } catch (IllegalArgumentException e) {
                return fail(ctx, e.getMessage());
            }
        } else if (field.equals("name")) {
            k.name = value;
        } else {
            k.tag = value;
        }
        return saved(ctx, "Kingdom " + k.id + " " + field + " set to " + value + ".");
    }

    private static int editRole(CommandContext<CommandSource> ctx, String field) {
        TabConfig.Role r = GrandTab.store().config().role(StringArgumentType.getString(ctx, "role"));
        if (r == null) {
            return fail(ctx, "Unknown role.");
        }
        String value = StringArgumentType.getString(ctx, "value").trim();
        if (field.equals("color")) {
            if (value.equalsIgnoreCase("kingdom") || value.equalsIgnoreCase("none")) {
                r.color = "";
            } else {
                try {
                    r.color = TabLogic.formatColor(TabLogic.parseColor(value));
                } catch (IllegalArgumentException e) {
                    return fail(ctx, e.getMessage());
                }
            }
        } else if (field.equals("bold")) {
            r.bold = Boolean.parseBoolean(value.toLowerCase(Locale.ROOT));
        } else {
            r.name = value;
        }
        return saved(ctx, "Role " + r.id + " " + field + " set to " + value + ".");
    }

    private static int setEnabled(CommandContext<CommandSource> ctx, boolean enabled) {
        GrandTab.store().config().enabled = enabled;
        return saved(ctx, enabled ? "Custom tab list enabled." : "Custom tab list disabled (vanilla tab list).");
    }

    private static int reload(CommandContext<CommandSource> ctx) {
        boolean okLoad = GrandTab.store().load();
        TabService service = GrandTab.service();
        if (service != null) {
            service.cleanupTeams(!service.config().players.sortByRank);
            service.markDirty();
        }
        return okLoad ? ok(ctx, "Reloaded config.json and assignments.json.")
                : fail(ctx, "Reload problem: " + GrandTab.store().lastError());
    }
}
