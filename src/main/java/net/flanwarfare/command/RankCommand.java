package net.flanwarfare.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.RankDef;
import net.flanwarfare.data.RankRegistry;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.RankColors;
import net.flanwarfare.ranks.RankRenderer;
import net.flanwarfare.ranks.RankService;
import net.flanwarfare.util.Msg;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.ISuggestionProvider;
import net.minecraft.command.arguments.GameProfileArgument;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.Util;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kingdom rank commands. Rank names containing spaces are typed in quotes ("Royal Guard") or by
 * their stable id (shown by /war rank list); /war rank create takes the rest of the line.
 * <pre>
 * /war rank setmain                         your claim you stand in becomes your kingdom's main claim
 * /war rank list | members [rank] | info [player] | preview &lt;rank&gt;
 * /war rank create &lt;name...&gt; | delete &lt;rank&gt; | rename &lt;rank&gt; &lt;new name...&gt;
 * /war rank color &lt;rank&gt; &lt;color&gt;           name (gold, dark_green ...) or #RRGGBB
 * /war rank gradient &lt;rank&gt; &lt;c1&gt; &lt;c2&gt; [..] | gradient &lt;rank&gt; clear
 * /war rank effect &lt;rank&gt; &lt;none|static|bold|glow|rainbow|pulse|wave|obfuscated&gt;
 * /war rank position &lt;rank&gt; &lt;left|above|below|default&gt;
 * /war rank prefix|suffix &lt;rank&gt; &lt;text&gt; | decoration &lt;rank&gt; clear
 * /war rank assign &lt;player&gt; &lt;rank&gt; | remove &lt;player&gt;
 * /war rank playerposition &lt;player&gt; &lt;left|above|below|reset&gt;
 * /war admin rank list | inspect &lt;kingdom&gt; | create &lt;kingdom&gt; &lt;name...&gt; | reset &lt;kingdom&gt;
 * /war admin rank assign &lt;kingdom&gt; &lt;player&gt; &lt;rank&gt; | remove &lt;player&gt; | position &lt;player&gt; &lt;pos&gt;
 * </pre>
 * &lt;kingdom&gt; is a main-claim UUID or its claim name (if unique).
 */
public final class RankCommand {
    private RankCommand() {
    }

    // ------------------------------------------------------------------ suggestions

    private static String quote(String s) {
        if (s.matches("[0-9A-Za-z_\\-.+]+")) {
            return s;
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final SuggestionProvider<CommandSource> RANKS = (ctx, builder) -> {
        List<String> names = new ArrayList<>();
        WarCore core = WarCore.ready();
        Entity entity = ctx.getSource().getEntity();
        if (core != null && entity instanceof ServerPlayerEntity) {
            RankRegistry registry = viewRegistry(core, (ServerPlayerEntity) entity);
            if (registry != null) {
                for (RankDef rank : registry.ranks.values()) {
                    names.add(quote(rank.name));
                }
            }
        }
        return ISuggestionProvider.suggest(names, builder);
    };

    private static final SuggestionProvider<CommandSource> KINGDOMS = (ctx, builder) -> {
        List<String> names = new ArrayList<>();
        WarCore core = WarCore.ready();
        if (core != null) {
            for (RankRegistry registry : core.ranks().allRegistries()) {
                names.add(registry.claimId.toString());
                names.add(quote(core.ranks().kingdomName(registry)));
            }
        }
        return ISuggestionProvider.suggest(names, builder);
    };

    private static final SuggestionProvider<CommandSource> COLORS = (ctx, builder) ->
            ISuggestionProvider.suggest(new ArrayList<>(RankColors.NAMED.keySet()), builder);

    private static final SuggestionProvider<CommandSource> EFFECTS = (ctx, builder) ->
            ISuggestionProvider.suggest(Arrays.asList("none", "static", "bold", "glow", "rainbow", "pulse", "wave", "obfuscated"), builder);

    private static final SuggestionProvider<CommandSource> RANK_POSITIONS = (ctx, builder) ->
            ISuggestionProvider.suggest(Arrays.asList("left", "above", "below", "default"), builder);

    private static final SuggestionProvider<CommandSource> PLAYER_POSITIONS = (ctx, builder) ->
            ISuggestionProvider.suggest(Arrays.asList("left", "above", "below", "reset"), builder);

    private static RequiredArgumentBuilder<CommandSource, String> rankArg() {
        return Commands.argument("rank", StringArgumentType.string()).suggests(RANKS);
    }

    private static RequiredArgumentBuilder<CommandSource, String> kingdomArg() {
        return Commands.argument("kingdom", StringArgumentType.string()).suggests(KINGDOMS);
    }

    // ------------------------------------------------------------------ registration

    interface ManageAction {
        String run(WarCore core, RankRegistry registry, RankDef rank, String actor) throws CommandSyntaxException;
    }

    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        LiteralArgumentBuilder<CommandSource> rank = Commands.literal("rank")
                .then(Commands.literal("setmain").executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) -> {
                    if (!enabled(ctx)) {
                        return 0;
                    }
                    String problem = core.ranks().setMain(p);
                    if (problem != null) {
                        return fail(ctx, problem);
                    }
                    RankRegistry registry = core.ranks().contextRegistry(p);
                    return ok(ctx, "This claim is now your kingdom's main claim for ranks"
                            + (registry == null ? "." : ": " + core.ranks().kingdomName(registry) + ". Create ranks with /war rank create <name>."));
                })))
                .then(Commands.literal("list").executes(ctx -> FlagCommand.withPlayer(ctx, RankCommand::list)))
                .then(Commands.literal("members")
                        .executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) -> members(ctx, core, p, null)))
                        .then(rankArg().executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) ->
                                members(ctx, core, p, StringArgumentType.getString(ctx, "rank"))))))
                .then(Commands.literal("info")
                        .executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) -> info(ctx, core, p.getGameProfile())))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) -> info(ctx, core, oneProfile(ctx))))))
                .then(Commands.literal("preview").then(rankArg().executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) -> preview(ctx, core, p)))))
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) -> {
                            RankRegistry registry = manageRegistry(ctx, core, p);
                            if (registry == null) {
                                return 0;
                            }
                            String name = StringArgumentType.getString(ctx, "name");
                            return result(ctx, core.ranks().create(registry, name, p.getGameProfile().getName()),
                                    "Rank '" + name.trim() + "' created (white). Style it with /war rank color, gradient, effect, prefix.");
                        }))))
                .then(Commands.literal("delete").then(rankArg().executes(ctx -> manage(ctx, (core, registry, r, actor) ->
                        core.ranks().delete(registry, r, actor), "Rank deleted; its holders no longer have a rank."))))
                .then(Commands.literal("rename").then(rankArg().then(Commands.argument("newname", StringArgumentType.greedyString())
                        .executes(ctx -> manage(ctx, (core, registry, r, actor) ->
                                core.ranks().rename(registry, r, StringArgumentType.getString(ctx, "newname"), actor), "Rank renamed.")))))
                .then(Commands.literal("color").then(rankArg().then(Commands.argument("color", StringArgumentType.word()).suggests(COLORS)
                        .executes(ctx -> manage(ctx, (core, registry, r, actor) ->
                                core.ranks().setColor(registry, r, StringArgumentType.getString(ctx, "color"), actor), "Colour set.")))))
                .then(Commands.literal("gradient").then(rankArg()
                        .then(Commands.literal("clear").executes(ctx -> manage(ctx, (core, registry, r, actor) ->
                                core.ranks().clearGradient(registry, r, actor), "Gradient cleared (the first colour is kept).")))
                        .then(Commands.argument("colors", StringArgumentType.greedyString()).suggests(COLORS)
                                .executes(ctx -> manage(ctx, (core, registry, r, actor) -> core.ranks().setGradient(registry, r,
                                        Arrays.asList(StringArgumentType.getString(ctx, "colors").trim().split("\\s+")), actor), "Gradient set.")))))
                .then(Commands.literal("effect").then(rankArg().then(Commands.argument("effect", StringArgumentType.word()).suggests(EFFECTS)
                        .executes(ctx -> manage(ctx, (core, registry, r, actor) ->
                                core.ranks().setEffect(registry, r, StringArgumentType.getString(ctx, "effect"), actor), "Effect set.")))))
                .then(Commands.literal("position").then(rankArg().then(Commands.argument("position", StringArgumentType.word()).suggests(RANK_POSITIONS)
                        .executes(ctx -> manage(ctx, (core, registry, r, actor) ->
                                core.ranks().setPosition(registry, r, StringArgumentType.getString(ctx, "position"), actor), "Display position set.")))))
                .then(Commands.literal("prefix").then(rankArg().then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> manage(ctx, (core, registry, r, actor) ->
                                core.ranks().setDecoration(registry, r, true, StringArgumentType.getString(ctx, "text"), actor), "Prefix set.")))))
                .then(Commands.literal("suffix").then(rankArg().then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> manage(ctx, (core, registry, r, actor) ->
                                core.ranks().setDecoration(registry, r, false, StringArgumentType.getString(ctx, "text"), actor), "Suffix set.")))))
                .then(Commands.literal("decoration").then(rankArg().then(Commands.literal("clear")
                        .executes(ctx -> manage(ctx, (core, registry, r, actor) ->
                                core.ranks().clearDecorations(registry, r, actor), "Prefix and suffix cleared.")))))
                .then(Commands.literal("assign").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(rankArg().executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) -> {
                            RankRegistry registry = manageRegistry(ctx, core, p);
                            if (registry == null) {
                                return 0;
                            }
                            RankDef r = rankOrFail(ctx, core, registry);
                            if (r == null) {
                                return 0;
                            }
                            GameProfile target = oneProfile(ctx);
                            String problem = core.ranks().assign(registry, target.getId(), target.getName(), r,
                                    core.ranks().isAdmin(p), p.getGameProfile().getName());
                            if (problem == null) {
                                notifyTarget(core, target.getId(), Msg.info("You now hold the rank '" + r.name + "' in "
                                        + core.ranks().kingdomName(registry) + "."));
                            }
                            return result(ctx, problem, target.getName() + " now holds '" + r.name + "'.");
                        })))))
                .then(Commands.literal("remove").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) -> {
                            GameProfile target = oneProfile(ctx);
                            RankRegistry registry = core.ranks().registryOf(target.getId());
                            if (registry == null) {
                                return fail(ctx, target.getName() + " has no rank.");
                            }
                            if (!core.ranks().isManager(p, registry)) {
                                return fail(ctx, "Only the owner of " + core.ranks().kingdomName(registry) + " can remove that rank.");
                            }
                            return result(ctx, core.ranks().unassign(target.getId(), target.getName(), p.getGameProfile().getName()),
                                    target.getName() + " no longer has a rank.");
                        }))))
                .then(Commands.literal("playerposition").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("position", StringArgumentType.word()).suggests(PLAYER_POSITIONS)
                                .executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) -> {
                                    GameProfile target = oneProfile(ctx);
                                    RankRegistry registry = core.ranks().registryOf(target.getId());
                                    if (registry == null) {
                                        return fail(ctx, target.getName() + " has no rank.");
                                    }
                                    boolean self = target.getId().equals(p.getUniqueID());
                                    if (!self && !core.ranks().isManager(p, registry)) {
                                        return fail(ctx, "You can change your own display position, or that of members of a kingdom you own.");
                                    }
                                    return result(ctx, core.ranks().setPlayerPosition(target.getId(), target.getName(),
                                            StringArgumentType.getString(ctx, "position"), p.getGameProfile().getName()), "Display position updated.");
                                })))));

        LiteralArgumentBuilder<CommandSource> adminRank = Commands.literal("rank")
                .then(Commands.literal("list").executes(RankCommand::adminList))
                .then(Commands.literal("inspect").then(kingdomArg().executes(RankCommand::adminInspect)))
                .then(Commands.literal("create").then(kingdomArg().then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> admin(ctx, (core, registry, actor) -> core.ranks().create(registry,
                                StringArgumentType.getString(ctx, "name"), actor), "Rank created.")))))
                .then(Commands.literal("reset").then(kingdomArg().executes(ctx -> admin(ctx, (core, registry, actor) -> {
                    for (RankDef r : new ArrayList<>(registry.ranks.values())) {
                        core.ranks().delete(registry, r, actor);
                    }
                    registry.assignments.clear();
                    registry.positionOverrides.clear();
                    core.data().markDirty();
                    return null;
                }, "All ranks and assignments of that kingdom were removed."))))
                .then(Commands.literal("assign").then(kingdomArg().then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(rankNameArg().executes(ctx -> admin(ctx, (core, registry, actor) -> {
                            RankDef r = core.ranks().findRank(registry, StringArgumentType.getString(ctx, "rankname"));
                            if (r == null) {
                                return "No such rank in that kingdom.";
                            }
                            GameProfile target = oneProfile(ctx);
                            return core.ranks().assign(registry, target.getId(), target.getName(), r, true, actor);
                        }, "Rank assigned."))))))
                .then(Commands.literal("remove").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ctx -> {
                            WarCore core = readyOrFail(ctx);
                            if (core == null) {
                                return 0;
                            }
                            GameProfile target = oneProfile(ctx);
                            return result(ctx, core.ranks().unassign(target.getId(), target.getName(), ctx.getSource().getName()),
                                    target.getName() + " no longer has a rank.");
                        })))
                .then(Commands.literal("position").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("position", StringArgumentType.word()).suggests(PLAYER_POSITIONS)
                                .executes(ctx -> {
                                    WarCore core = readyOrFail(ctx);
                                    if (core == null) {
                                        return 0;
                                    }
                                    GameProfile target = oneProfile(ctx);
                                    return result(ctx, core.ranks().setPlayerPosition(target.getId(), target.getName(),
                                            StringArgumentType.getString(ctx, "position"), ctx.getSource().getName()), "Display position updated.");
                                }))));

        dispatcher.register(Commands.literal("war")
                .then(rank)
                .then(Commands.literal("admin")
                        .requires(src -> src.hasPermissionLevel(Msg.adminLevel()))
                        .then(adminRank)));
    }

    /** Rank argument for admin commands; suggestions come from the kingdom argument. */
    private static RequiredArgumentBuilder<CommandSource, String> rankNameArg() {
        return Commands.argument("rankname", StringArgumentType.string()).suggests((ctx, builder) -> {
            List<String> names = new ArrayList<>();
            WarCore core = WarCore.ready();
            if (core != null) {
                try {
                    RankRegistry registry = core.ranks().findRegistry(StringArgumentType.getString(ctx, "kingdom"));
                    if (registry != null) {
                        for (RankDef r : registry.ranks.values()) {
                            names.add(quote(r.name));
                        }
                    }
                } catch (IllegalArgumentException noKingdomYet) {
                    // suggestions only
                }
            }
            return ISuggestionProvider.suggest(names, builder);
        });
    }

    // ------------------------------------------------------------------ helpers

    private static boolean enabled(CommandContext<CommandSource> ctx) {
        if (!RankService.enabled()) {
            ctx.getSource().sendErrorMessage(Msg.error("Kingdom ranks are disabled on this server."));
            return false;
        }
        return true;
    }

    private static WarCore readyOrFail(CommandContext<CommandSource> ctx) {
        WarCore core = WarCore.ready();
        if (core == null) {
            ctx.getSource().sendErrorMessage(Msg.error("Flan Warfare is not active (starting up or in safe mode)."));
        }
        return core;
    }

    private static int ok(CommandContext<CommandSource> ctx, String text) {
        ctx.getSource().sendFeedback(Msg.ok(text), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSource> ctx, String text) {
        ctx.getSource().sendErrorMessage(Msg.error(text));
        return 0;
    }

    private static int result(CommandContext<CommandSource> ctx, String problem, String success) {
        return problem == null ? ok(ctx, success) : fail(ctx, problem);
    }

    private static GameProfile oneProfile(CommandContext<CommandSource> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    new StringTextComponent("Name exactly one player.")).create();
        }
        return profiles.iterator().next();
    }

    private static void notifyTarget(WarCore core, UUID player, IFormattableTextComponent message) {
        ServerPlayerEntity online = core.server().getPlayerList().getPlayerByUUID(player);
        Msg.send(online, message);
    }

    /** Registry the player manages (owner of the kingdom, or an admin standing in it); else error. */
    private static RankRegistry manageRegistry(CommandContext<CommandSource> ctx, WarCore core, ServerPlayerEntity p) {
        if (!enabled(ctx)) {
            return null;
        }
        RankRegistry registry = core.ranks().contextRegistry(p);
        if (registry == null) {
            fail(ctx, "You do not manage a kingdom here. Owners: stand in your main claim and run /war rank setmain first."
                    + (core.ranks().isAdmin(p) ? " Admins: stand in a kingdom's main claim or use /war admin rank." : ""));
        }
        return registry;
    }

    /** Registry for read-only commands: the one managed, else the one the player holds a rank in, else the one stood in. */
    private static RankRegistry viewRegistry(WarCore core, ServerPlayerEntity p) {
        RankRegistry registry = core.ranks().contextRegistry(p);
        if (registry == null) {
            registry = core.ranks().registryOf(p.getUniqueID());
        }
        if (registry == null) {
            io.github.flemmli97.flan.claim.Claim here = FlanBridge.parentAt(p.getServerWorld(), p.getPosition());
            if (here != null) {
                registry = core.data().rankRegistries().get(here.getClaimID());
            }
        }
        return registry;
    }

    private static RankDef rankOrFail(CommandContext<CommandSource> ctx, WarCore core, RankRegistry registry) {
        RankDef r = core.ranks().findRank(registry, StringArgumentType.getString(ctx, "rank"));
        if (r == null) {
            fail(ctx, "No rank '" + StringArgumentType.getString(ctx, "rank") + "' in " + core.ranks().kingdomName(registry)
                    + ". Names with spaces go in quotes, e.g. \"Royal Guard\".");
        }
        return r;
    }

    private static int manage(CommandContext<CommandSource> ctx, ManageAction action, String success) throws CommandSyntaxException {
        return FlagCommand.withPlayer(ctx, (core, p) -> {
            RankRegistry registry = manageRegistry(ctx, core, p);
            if (registry == null) {
                return 0;
            }
            RankDef r = rankOrFail(ctx, core, registry);
            if (r == null) {
                return 0;
            }
            return result(ctx, action.run(core, registry, r, p.getGameProfile().getName()), success);
        });
    }

    interface AdminAction {
        String run(WarCore core, RankRegistry registry, String actor) throws CommandSyntaxException;
    }

    private static int admin(CommandContext<CommandSource> ctx, AdminAction action, String success) throws CommandSyntaxException {
        WarCore core = readyOrFail(ctx);
        if (core == null) {
            return 0;
        }
        String key = StringArgumentType.getString(ctx, "kingdom");
        RankRegistry registry = core.ranks().findRegistry(key);
        if (registry == null) {
            return fail(ctx, "No kingdom '" + key + "' (or the name is not unique: use the main-claim UUID from /war admin rank list).");
        }
        return result(ctx, action.run(core, registry, ctx.getSource().getName()), success);
    }

    // ------------------------------------------------------------------ views

    private static IFormattableTextComponent rankLine(WarCore core, RankRegistry registry, RankDef r) {
        int holders = core.ranks().holders(registry, r).size();
        IFormattableTextComponent line = new StringTextComponent(" - ").mergeStyle(TextFormatting.GRAY);
        line.appendSibling(RankRenderer.tag(r, core.rankDisplay().phase()));
        StringBuilder details = new StringBuilder("  [id ").append(r.id).append(", ");
        details.append(r.colors.size() > 1 ? "gradient of " + r.colors.size() : RankColors.format(r.colors.isEmpty() ? 0xFFFFFF : r.colors.get(0)));
        if (!"NONE".equals(r.effect)) {
            details.append(", ").append(r.effect.toLowerCase());
        }
        details.append(", ").append(r.position == null ? "default position" : r.position.toLowerCase());
        details.append(", ").append(holders).append(holders == 1 ? " holder]" : " holders]");
        line.appendSibling(new StringTextComponent(details.toString()).mergeStyle(TextFormatting.DARK_GRAY));
        return line;
    }

    private static int list(WarCore core, ServerPlayerEntity p) {
        RankRegistry registry = viewRegistry(core, p);
        if (registry == null) {
            Msg.send(p, Msg.warn("No kingdom ranks here. Kingdom owners start with /war rank setmain inside their main claim."));
            return 0;
        }
        Msg.send(p, Msg.info("Ranks of " + core.ranks().kingdomName(registry) + " (" + registry.ranks.size() + "/"
                + WarConfig.RANKS_MAX_PER_KINGDOM.get() + "):"));
        if (registry.ranks.isEmpty()) {
            Msg.send(p, Msg.info("No ranks yet."));
        }
        for (RankDef r : registry.ranks.values()) {
            p.sendMessage(rankLine(core, registry, r), Util.DUMMY_UUID);
        }
        return 1;
    }

    private static int members(CommandContext<CommandSource> ctx, WarCore core, ServerPlayerEntity p, String rankName) {
        RankRegistry registry = viewRegistry(core, p);
        if (registry == null) {
            return fail(ctx, "No kingdom ranks here.");
        }
        RankDef only = null;
        if (rankName != null) {
            only = core.ranks().findRank(registry, rankName);
            if (only == null) {
                return fail(ctx, "No such rank.");
            }
        }
        Msg.send(p, Msg.info("Rank holders in " + core.ranks().kingdomName(registry) + ":"));
        int shown = 0;
        for (Map.Entry<UUID, String> e : registry.assignments.entrySet()) {
            RankDef r = registry.ranks.get(e.getValue());
            if (r == null || only != null && r != only) {
                continue;
            }
            boolean suspended = !core.ranks().eligible(registry, e.getKey());
            IFormattableTextComponent line = new StringTextComponent(" - " + FlanBridge.playerName(core.server(), e.getKey()) + " ")
                    .mergeStyle(TextFormatting.WHITE);
            line.appendSibling(RankRenderer.tag(r, 0));
            if (suspended) {
                line.appendSibling(new StringTextComponent("  (suspended: not a Flan member of this kingdom)").mergeStyle(TextFormatting.RED));
            }
            p.sendMessage(line, Util.DUMMY_UUID);
            shown++;
        }
        if (shown == 0) {
            Msg.send(p, Msg.info("Nobody."));
        }
        return 1;
    }

    private static int info(CommandContext<CommandSource> ctx, WarCore core, GameProfile target) {
        RankRegistry registry = core.ranks().registryOf(target.getId());
        if (registry == null) {
            return ok(ctx, target.getName() + " has no kingdom rank.");
        }
        RankDef r = registry.ranks.get(registry.assignments.get(target.getId()));
        IFormattableTextComponent line = new StringTextComponent("[War] " + target.getName() + ": ").mergeStyle(TextFormatting.GOLD);
        if (r != null) {
            line.appendSibling(RankRenderer.tag(r, core.rankDisplay().phase()));
        }
        line.appendSibling(new StringTextComponent(" in " + core.ranks().kingdomName(registry)).mergeStyle(TextFormatting.GOLD));
        ctx.getSource().sendFeedback(line, false);
        RankService.Active active = core.ranks().active(target.getId());
        if (active == null) {
            ctx.getSource().sendFeedback(Msg.warn("Not displayed: " + (!RankService.enabled() ? "ranks are disabled."
                    : "suspended until they are a Flan member of this kingdom again.")), false);
        } else {
            String override = registry.positionOverrides.get(target.getId());
            ctx.getSource().sendFeedback(Msg.info("Position: " + active.position + (override != null ? " (personal setting)" : "")
                    + ". Nametag: " + core.rankDisplay().describe(target.getId()) + ". Chat: "
                    + (WarConfig.RANK_CHAT_ENABLED.get() ? "on" : "off") + "."), false);
        }
        return 1;
    }

    private static int preview(CommandContext<CommandSource> ctx, WarCore core, ServerPlayerEntity p) {
        RankRegistry registry = viewRegistry(core, p);
        if (registry == null) {
            return fail(ctx, "No kingdom ranks here.");
        }
        RankDef r = rankOrFail(ctx, core, registry);
        if (r == null) {
            return 0;
        }
        Msg.send(p, Msg.info("Preview of '" + r.name + "' (animated effects move on nametags; chat shows one frame):"));
        IFormattableTextComponent tag = new StringTextComponent("  Tag: ").mergeStyle(TextFormatting.GRAY);
        tag.appendSibling(RankRenderer.tag(r, core.rankDisplay().phase()));
        p.sendMessage(tag, Util.DUMMY_UUID);
        IFormattableTextComponent left = new StringTextComponent("  Nametag (left): ").mergeStyle(TextFormatting.GRAY);
        left.appendSibling(RankRenderer.leftPrefix(r, core.rankDisplay().phase()));
        left.appendSibling(new StringTextComponent(p.getGameProfile().getName()).mergeStyle(TextFormatting.WHITE));
        p.sendMessage(left, Util.DUMMY_UUID);
        IFormattableTextComponent chat = new StringTextComponent("  Chat: ").mergeStyle(TextFormatting.GRAY);
        chat.appendSibling(RankRenderer.chat(r, core.rankDisplay().phase(), p.getGameProfile().getName(), "Hello kingdom!"));
        p.sendMessage(chat, Util.DUMMY_UUID);
        return 1;
    }

    private static int adminList(CommandContext<CommandSource> ctx) {
        WarCore core = readyOrFail(ctx);
        if (core == null) {
            return 0;
        }
        List<RankRegistry> all = core.ranks().allRegistries();
        reply(ctx, "Kingdom rank registries: " + all.size());
        for (RankRegistry registry : all) {
            String owner = "unknown";
            io.github.flemmli97.flan.claim.Claim claim = core.ranks().claimOf(registry);
            if (claim != null) {
                owner = FlanBridge.playerName(core.server(), claim.getOwner());
            }
            reply(ctx, " - " + core.ranks().kingdomName(registry) + " [" + registry.claimId + "] owner " + owner + ", "
                    + registry.ranks.size() + " ranks, " + registry.assignments.size() + " assigned");
        }
        return 1;
    }

    private static int adminInspect(CommandContext<CommandSource> ctx) {
        WarCore core = readyOrFail(ctx);
        if (core == null) {
            return 0;
        }
        String key = StringArgumentType.getString(ctx, "kingdom");
        RankRegistry registry = core.ranks().findRegistry(key);
        if (registry == null) {
            return fail(ctx, "No kingdom '" + key + "' (or the name is not unique: use the UUID).");
        }
        io.github.flemmli97.flan.claim.Claim claim = core.ranks().claimOf(registry);
        reply(ctx, core.ranks().kingdomName(registry) + " [" + registry.claimId + "] in " + registry.dimension
                + ", managed by " + (claim == null ? "nobody (claim missing)" : FlanBridge.playerName(core.server(), claim.getOwner())));
        for (RankDef r : registry.ranks.values()) {
            ctx.getSource().sendFeedback(rankLine(core, registry, r), false);
        }
        for (Map.Entry<UUID, String> e : registry.assignments.entrySet()) {
            RankDef r = registry.ranks.get(e.getValue());
            String override = registry.positionOverrides.get(e.getKey());
            reply(ctx, "   " + FlanBridge.playerName(core.server(), e.getKey()) + " -> " + (r == null ? "?" : r.name)
                    + (core.ranks().eligible(registry, e.getKey()) ? "" : " (suspended)")
                    + (override == null ? "" : ", position " + override.toLowerCase()));
        }
        return 1;
    }

    private static void reply(CommandContext<CommandSource> ctx, String text) {
        ctx.getSource().sendFeedback(Msg.info(text), false);
    }
}
