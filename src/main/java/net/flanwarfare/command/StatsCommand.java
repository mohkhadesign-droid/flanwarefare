package net.flanwarfare.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.data.Season;
import net.flanwarfare.data.WarEvent;
import net.flanwarfare.flan.Accounting;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.DurationParser;
import net.flanwarfare.logic.StatRow;
import net.flanwarfare.logic.StatsAggregator;
import net.flanwarfare.stats.WarStats;
import net.flanwarfare.util.Msg;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.ISuggestionProvider;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.TextFormatting;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Season statistics, war history, conquered-claim listing, recapture immunity and season
 * administration. Registered under the same {@code /war} root as {@link WarCommand}.
 *
 * <pre>
 * /war claims                                  your conquered claims, slots and immunity
 * /war leaderboard [players|kingdoms|defenses|territory] [page]
 * /war stats [player]
 * /war log [page] | log player &lt;player&gt; [page] | log claim [page] | log kingdom &lt;kingdom&gt; [page]
 * /war seasons [&lt;id&gt; [log [page]]]                archived seasons
 * /war admin claims &lt;player&gt;
 * /war admin immunity inspect|clear [&lt;claim&gt;]   (default: the claim you stand in)
 * /war admin immunity set &lt;duration&gt; [&lt;claim&gt;]  e.g. 24h, 90m, 1d12h, 0
 * /war admin season info|start &lt;name&gt;|end|archive|reset confirm
 * /war admin stats check
 * </pre>
 */
public final class StatsCommand {
    private StatsCommand() {
    }

    // ------------------------------------------------------------------ registration

    private static final SuggestionProvider<CommandSource> PLAYERS = (ctx, builder) -> {
        Set<String> names = new LinkedHashSet<>();
        for (ServerPlayerEntity p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            names.add(p.getGameProfile().getName());
        }
        WarCore core = WarCore.ready();
        if (core != null) {
            for (WarEvent e : core.stats().events()) {
                addIfPresent(names, e.newOwnerName);
                addIfPresent(names, e.previousOwnerName);
                for (String n : e.participantNames) {
                    addIfPresent(names, n);
                }
            }
        }
        return ISuggestionProvider.suggest(names, builder);
    };

    private static final SuggestionProvider<CommandSource> KINGDOMS = (ctx, builder) -> {
        Set<String> names = new LinkedHashSet<>();
        WarCore core = WarCore.ready();
        if (core != null) {
            for (StatRow row : core.stats().aggregate().kingdoms(StatRow.BY_CONQUESTS)) {
                addIfPresent(names, quote(row.name));
            }
        }
        return ISuggestionProvider.suggest(names, builder);
    };

    private static final SuggestionProvider<CommandSource> ARCHIVES = (ctx, builder) -> {
        WarCore core = WarCore.ready();
        return ISuggestionProvider.suggest(core == null ? Collections.<String>emptyList() : core.stats().archiveIds(), builder);
    };

    private static final SuggestionProvider<CommandSource> CLAIMS = (ctx, builder) -> {
        WarCore core = WarCore.ready();
        List<String> ids = new ArrayList<>();
        if (core != null) {
            for (UUID id : core.data().claims().keySet()) {
                ids.add(id.toString());
            }
        }
        return ISuggestionProvider.suggest(ids, builder);
    };

    private static void addIfPresent(Set<String> set, String value) {
        if (value != null && !value.isEmpty()) {
            set.add(value);
        }
    }

    private static String quote(String name) {
        return name != null && name.matches("[A-Za-z0-9_.+-]+") ? name : "\"" + name + "\"";
    }

    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        LiteralArgumentBuilder<CommandSource> leaderboard = Commands.literal("leaderboard")
                .executes(ctx -> leaderboard(ctx, Board.PLAYERS, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> leaderboard(ctx, Board.PLAYERS, IntegerArgumentType.getInteger(ctx, "page"))));
        for (Board board : Board.values()) {
            leaderboard.then(Commands.literal(board.literal)
                    .executes(ctx -> leaderboard(ctx, board, 1))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> leaderboard(ctx, board, IntegerArgumentType.getInteger(ctx, "page")))));
        }

        dispatcher.register(Commands.literal("war")
                .then(Commands.literal("claims").executes(ctx -> FlagCommand.withPlayer(ctx,
                        (core, p) -> listConquered(ctx, core, p.getUniqueID(), true))))
                .then(leaderboard)
                .then(Commands.literal("stats")
                        .executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) -> stats(ctx, core, p.getGameProfile())))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                .executes(ctx -> statsOf(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("log")
                        .executes(ctx -> log(ctx, 1, null, "all events"))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> log(ctx, IntegerArgumentType.getInteger(ctx, "page"), null, "all events")))
                        .then(Commands.literal("player")
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                        .executes(ctx -> logPlayer(ctx, 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> logPlayer(ctx, IntegerArgumentType.getInteger(ctx, "page"))))))
                        .then(Commands.literal("claim")
                                .executes(ctx -> logClaim(ctx, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> logClaim(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                        .then(Commands.literal("kingdom")
                                .then(Commands.argument("kingdom", StringArgumentType.string()).suggests(KINGDOMS)
                                        .executes(ctx -> logKingdom(ctx, 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> logKingdom(ctx, IntegerArgumentType.getInteger(ctx, "page")))))))
                .then(Commands.literal("seasons")
                        .executes(StatsCommand::listSeasons)
                        .then(Commands.argument("season", StringArgumentType.word()).suggests(ARCHIVES)
                                .executes(ctx -> showArchive(ctx, StringArgumentType.getString(ctx, "season")))
                                .then(Commands.literal("log")
                                        .executes(ctx -> archiveLog(ctx, 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> archiveLog(ctx, IntegerArgumentType.getInteger(ctx, "page")))))))
                .then(Commands.literal("admin")
                        .requires(src -> src.hasPermissionLevel(Msg.adminLevel()))
                        .then(Commands.literal("claims")
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                        .executes(StatsCommand::adminClaims)))
                        .then(Commands.literal("immunity")
                                .then(Commands.literal("inspect")
                                        .executes(ctx -> immunity(ctx, ImmunityAction.INSPECT, null))
                                        .then(Commands.argument("claim", StringArgumentType.word()).suggests(CLAIMS)
                                                .executes(ctx -> immunity(ctx, ImmunityAction.INSPECT, null))))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> immunity(ctx, ImmunityAction.CLEAR, null))
                                        .then(Commands.argument("claim", StringArgumentType.word()).suggests(CLAIMS)
                                                .executes(ctx -> immunity(ctx, ImmunityAction.CLEAR, null))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("duration", StringArgumentType.word())
                                                .executes(ctx -> immunity(ctx, ImmunityAction.SET, StringArgumentType.getString(ctx, "duration")))
                                                .then(Commands.argument("claim", StringArgumentType.word()).suggests(CLAIMS)
                                                        .executes(ctx -> immunity(ctx, ImmunityAction.SET,
                                                                StringArgumentType.getString(ctx, "duration")))))))
                        .then(Commands.literal("season")
                                .then(Commands.literal("info").executes(StatsCommand::seasonInfo))
                                .then(Commands.literal("start")
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> seasonOp(ctx, "start"))))
                                .then(Commands.literal("end").executes(ctx -> seasonOp(ctx, "end")))
                                .then(Commands.literal("archive").executes(ctx -> seasonOp(ctx, "archive")))
                                .then(Commands.literal("reset")
                                        .executes(ctx -> fail(ctx, "This clears the season's statistics (after saving a "
                                                + "backup archive). Claims, ownership, immunity and flags are not changed. "
                                                + "Run /war admin season reset confirm to proceed."))
                                        .then(Commands.literal("confirm").executes(ctx -> seasonOp(ctx, "reset")))))
                        .then(Commands.literal("stats")
                                .then(Commands.literal("check").executes(StatsCommand::statsCheck)))));
    }

    // ------------------------------------------------------------------ helpers

    private static WarCore core(CommandContext<CommandSource> ctx) {
        WarCore core = WarCore.ready();
        if (core == null) {
            fail(ctx, "Flan Warfare is not active (starting up or in safe mode).");
        }
        return core;
    }

    private static int fail(CommandContext<CommandSource> ctx, String text) {
        ctx.getSource().sendErrorMessage(Msg.error(text));
        return 0;
    }

    private static void line(CommandContext<CommandSource> ctx, String text, TextFormatting color) {
        ctx.getSource().sendFeedback(Msg.text(text, color), false);
    }

    private static void header(CommandContext<CommandSource> ctx, String title) {
        line(ctx, "===== " + title + " =====", TextFormatting.GOLD);
    }

    private static boolean statsEnabled(CommandContext<CommandSource> ctx) {
        if (!WarConfig.LB_ENABLED.get()) {
            fail(ctx, "Season statistics are disabled on this server.");
            return false;
        }
        return true;
    }

    private static DateTimeFormatter timeFormat() {
        return DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH).withZone(WarConfig.zone());
    }

    private static String when(long millis) {
        return timeFormat().format(Instant.ofEpochMilli(millis));
    }

    private static int pageSize() {
        return WarConfig.PAGE_SIZE.get();
    }

    private static int pages(int count) {
        return Math.max(1, (count + pageSize() - 1) / pageSize());
    }

    /** Resolves a player name to a profile: online, then the server's profile cache, then history. */
    private static GameProfile profile(WarCore core, String name) {
        ServerPlayerEntity online = core.server().getPlayerList().getPlayerByUsername(name);
        if (online != null) {
            return online.getGameProfile();
        }
        GameProfile cached = core.server().getPlayerProfileCache().getGameProfileForUsername(name);
        if (cached != null && cached.getId() != null) {
            return cached;
        }
        for (WarEvent e : core.stats().events()) {
            if (name.equalsIgnoreCase(e.newOwnerName) && e.newOwner != null) {
                return new GameProfile(e.newOwner, e.newOwnerName);
            }
            if (name.equalsIgnoreCase(e.previousOwnerName) && e.previousOwner != null) {
                return new GameProfile(e.previousOwner, e.previousOwnerName);
            }
            for (int i = 0; i < e.participants.size() && i < e.participantNames.size(); i++) {
                if (name.equalsIgnoreCase(e.participantNames.get(i))) {
                    return new GameProfile(e.participants.get(i), e.participantNames.get(i));
                }
            }
        }
        return null;
    }

    private static String maxText() {
        int max = WarConfig.MAX_CONQUERED_PER_PLAYER.get();
        return max < 0 ? "unlimited" : String.valueOf(max);
    }

    private static String display(String name, String id) {
        return name != null ? name : id.substring(0, Math.min(8, id.length()));
    }

    // ------------------------------------------------------------------ /war claims

    private static int listConquered(CommandContext<CommandSource> ctx, WarCore core, UUID player, boolean self) {
        List<ClaimRecord> owned = new ArrayList<>();
        for (ClaimRecord record : core.data().claims().values()) {
            if (!record.conquered) {
                continue;
            }
            Claim claim = FlanBridge.find(core.server(), record);
            if (claim != null && player.equals(claim.getOwner()) && !claim.isAdminClaim()) {
                owned.add(record);
            }
        }
        int count = Accounting.conqueredCount(core.server(), player);
        int max = WarConfig.MAX_CONQUERED_PER_PLAYER.get();
        header(ctx, self ? "YOUR CONQUERED TERRITORIES" : "CONQUERED TERRITORIES OF " + FlanBridge.playerName(core.server(), player));
        line(ctx, "Conquered Claims: " + count + " / " + maxText(), TextFormatting.YELLOW);
        line(ctx, "Remaining Slots: " + (max < 0 ? "unlimited" : String.valueOf(Math.max(0, max - count))), TextFormatting.YELLOW);
        long now = System.currentTimeMillis();
        int i = 1;
        for (ClaimRecord record : owned) {
            Claim claim = FlanBridge.find(core.server(), record);
            line(ctx, i++ + ". " + FlanBridge.label(claim), TextFormatting.WHITE);
            line(ctx, "   Dimension: " + record.dimension, TextFormatting.GRAY);
            line(ctx, "   Location: X " + record.flagX + ", Z " + record.flagZ, TextFormatting.GRAY);
            long immunity = record.immunityRemaining(now);
            if (immunity > 0) {
                line(ctx, "   Recapture immunity: " + Msg.duration(immunity) + " left", TextFormatting.AQUA);
            }
            if (!self) {
                line(ctx, "   Claim: " + record.claimId, TextFormatting.DARK_GRAY);
            }
        }
        if (owned.isEmpty()) {
            line(ctx, "No conquered territories.", TextFormatting.GRAY);
        }
        return 1;
    }

    private static int adminClaims(CommandContext<CommandSource> ctx) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        GameProfile profile = profile(core, StringArgumentType.getString(ctx, "player"));
        if (profile == null) {
            return fail(ctx, "Unknown player.");
        }
        return listConquered(ctx, core, profile.getId(), false);
    }

    // ------------------------------------------------------------------ leaderboards

    private enum Board {
        PLAYERS("players"), KINGDOMS("kingdoms"), DEFENSES("defenses"), TERRITORY("territory");

        final String literal;

        Board(String literal) {
            this.literal = literal;
        }
    }

    private static int leaderboard(CommandContext<CommandSource> ctx, Board board, int page) {
        WarCore core = core(ctx);
        if (core == null || !statsEnabled(ctx)) {
            return 0;
        }
        if (board == Board.KINGDOMS && !WarConfig.LB_KINGDOMS.get()) {
            return fail(ctx, "Kingdom statistics are disabled on this server.");
        }
        Season season = core.stats().season();
        StatsAggregator stats = core.stats().aggregate();
        List<StatRow> rows;
        String title;
        switch (board) {
            case KINGDOMS:
                rows = stats.kingdoms(StatRow.BY_CONQUESTS);
                title = "SEASON KINGDOM LEADERBOARD";
                break;
            case DEFENSES:
                rows = stats.players(StatRow.BY_DEFENSES);
                title = "SEASON DEFENSE LEADERBOARD";
                break;
            case TERRITORY:
                rows = stats.players(StatRow.BY_TERRITORY);
                title = "TERRITORY LEADERBOARD";
                break;
            default:
                rows = stats.players(StatRow.BY_CONQUESTS);
                title = "SEASON WAR LEADERBOARD";
                break;
        }
        header(ctx, title);
        line(ctx, season == null ? "No active season." : season.name + (season.isActive() ? "" : " (ended)"), TextFormatting.GRAY);
        int total = pages(rows.size());
        int p = Math.min(page, total);
        if (rows.isEmpty()) {
            line(ctx, "No entries yet.", TextFormatting.GRAY);
        }
        if (board == Board.KINGDOMS && rows.isEmpty()) {
            line(ctx, "Kingdom attribution needs FTB Teams parties. " + core.sides().status(), TextFormatting.DARK_GRAY);
        }
        for (int i = (p - 1) * pageSize(); i < Math.min(rows.size(), p * pageSize()); i++) {
            StatRow r = rows.get(i);
            String values;
            switch (board) {
                case TERRITORY:
                    values = r.totalHeld + " held (" + r.conqueredHeld + " conquered), " + r.conquests + " conquests";
                    break;
                case DEFENSES:
                    values = r.defenses + " defenses, " + r.conquests + " conquests";
                    break;
                default:
                    values = r.conquests + " conquests, " + r.defenses + " defenses, " + r.conqueredHeld + " held, "
                            + r.lost + " lost";
                    break;
            }
            line(ctx, "#" + (i + 1) + "  " + display(r.name, r.id) + "  -  " + values,
                    i == 0 ? TextFormatting.YELLOW : TextFormatting.WHITE);
        }
        line(ctx, "Page " + p + " / " + total, TextFormatting.DARK_GRAY);
        ServerPlayerEntity viewer = ctx.getSource().getEntity() instanceof ServerPlayerEntity
                ? (ServerPlayerEntity) ctx.getSource().getEntity() : null;
        if (viewer != null && board != Board.KINGDOMS) {
            String id = viewer.getUniqueID().toString();
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id.equals(id)) {
                    line(ctx, "Your Rank: #" + (i + 1), TextFormatting.GREEN);
                    break;
                }
            }
        }
        return 1;
    }

    // ------------------------------------------------------------------ /war stats

    private static int statsOf(CommandContext<CommandSource> ctx, String name) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        GameProfile profile = profile(core, name);
        if (profile == null) {
            return fail(ctx, "Unknown player: " + name);
        }
        return stats(ctx, core, profile);
    }

    private static int stats(CommandContext<CommandSource> ctx, WarCore core, GameProfile profile) {
        if (!statsEnabled(ctx)) {
            return 0;
        }
        StatsAggregator stats = core.stats().aggregate();
        StatRow row = stats.player(profile.getId());
        if (row == null) {
            row = new StatRow(profile.getId().toString(), profile.getName());
        }
        List<StatRow> ranked = stats.players(StatRow.BY_CONQUESTS);
        int rank = ranked.indexOf(row) + 1;
        Season season = core.stats().season();
        header(ctx, "SEASON STATS: " + profile.getName());
        line(ctx, season == null ? "No active season." : season.name, TextFormatting.GRAY);
        line(ctx, "Successful Conquests: " + row.conquests, TextFormatting.WHITE);
        line(ctx, "Successful Defenses: " + row.defenses, TextFormatting.WHITE);
        line(ctx, "Conquered Territory Held: " + row.conqueredHeld + " / " + maxText(), TextFormatting.WHITE);
        line(ctx, "Total Territory Held: " + row.totalHeld, TextFormatting.WHITE);
        line(ctx, "Territories Lost: " + row.lost, TextFormatting.WHITE);
        net.flanwarfare.team.SideResolver.Kingdom kingdom = core.sides().kingdomOf(profile.getId());
        line(ctx, "Kingdom: " + (kingdom == null ? "none / unavailable" : kingdom.name), TextFormatting.GRAY);
        if (rank > 0) {
            line(ctx, "Rank: #" + rank + " of " + ranked.size(), TextFormatting.GREEN);
        }
        return 1;
    }

    // ------------------------------------------------------------------ /war log

    private static int log(CommandContext<CommandSource> ctx, int page, Predicate<WarEvent> filter, String what) {
        WarCore core = core(ctx);
        if (core == null || !statsEnabled(ctx)) {
            return 0;
        }
        if (!WarConfig.LB_WAR_HISTORY.get()) {
            return fail(ctx, "The war log is disabled on this server.");
        }
        return printLog(ctx, core.stats().events(), page, filter, "WAR HISTORY (" + what + ")", null);
    }

    private static int printLog(CommandContext<CommandSource> ctx, List<WarEvent> source, int page,
                                Predicate<WarEvent> filter, String title, String perspectiveId) {
        List<WarEvent> events = new ArrayList<>();
        for (WarEvent e : source) {
            if (filter == null || filter.test(e)) {
                events.add(e);
            }
        }
        events.sort(Comparator.comparingLong((WarEvent e) -> e.at).reversed());
        header(ctx, title);
        if (events.isEmpty()) {
            line(ctx, "No war events recorded.", TextFormatting.GRAY);
            return 1;
        }
        int total = pages(events.size());
        int p = Math.min(page, total);
        for (int i = (p - 1) * pageSize(); i < Math.min(events.size(), p * pageSize()); i++) {
            WarEvent e = events.get(i);
            line(ctx, "[" + when(e.at) + "]", TextFormatting.DARK_GRAY);
            line(ctx, describe(e, perspectiveId), color(e));
        }
        line(ctx, "Page " + p + " / " + total + " (newest first)", TextFormatting.DARK_GRAY);
        return 1;
    }

    private static TextFormatting color(WarEvent e) {
        if (WarEvent.DEFENSE.equals(e.type)) {
            return TextFormatting.GREEN;
        }
        if (WarEvent.CONQUEST_REVERSED.equals(e.type) || e.reversed) {
            return TextFormatting.GRAY;
        }
        return TextFormatting.RED;
    }

    private static String kingdomPart(String id, String name) {
        return id == null ? "" : " [" + (name == null ? id : name) + "]";
    }

    private static String describe(WarEvent e, String perspectiveId) {
        String where = " at X " + e.x + ", Z " + e.z + " (" + e.dimension + ")";
        if (WarEvent.CONQUEST.equals(e.type)) {
            String text = e.newOwnerName + kingdomPart(e.actingKingdomId, e.actingKingdomName) + " conquered "
                    + e.claimName + " from " + e.previousOwnerName + kingdomPart(e.previousKingdomId, e.previousKingdomName)
                    + "." + where;
            if (perspectiveId != null && e.previousOwner != null && perspectiveId.equals(e.previousOwner.toString())) {
                text = "Territory lost: " + text;
            }
            return e.reversed ? text + " (REVERSED: " + e.reversedNote + ")" : text;
        }
        if (WarEvent.DEFENSE.equals(e.type)) {
            return String.join(", ", e.participantNames) + kingdomPart(e.actingKingdomId, e.actingKingdomName)
                    + " successfully defended " + e.claimName + " against "
                    + (e.opponentNames.isEmpty() ? "an enemy" : String.join(", ", e.opponentNames))
                    + kingdomPart(e.opposingKingdomId, e.opposingKingdomName) + " (peak " + e.peakPercent + "%)." + where;
        }
        if (WarEvent.CONQUEST_REVERSED.equals(e.type)) {
            return "Conquest of " + e.claimName + " reversed by an administrator: returned from " + e.previousOwnerName
                    + " to " + e.newOwnerName + " (" + e.reversedNote + ").";
        }
        return e.type + " " + e.claimName;
    }

    private static int logPlayer(CommandContext<CommandSource> ctx, int page) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "player");
        GameProfile profile = profile(core, name);
        if (profile == null) {
            return fail(ctx, "Unknown player: " + name);
        }
        UUID id = profile.getId();
        if (!statsEnabled(ctx) || !WarConfig.LB_WAR_HISTORY.get()) {
            return fail(ctx, "The war log is disabled on this server.");
        }
        return printLog(ctx, core.stats().events(), page, e -> id.equals(e.newOwner) || id.equals(e.previousOwner)
                || e.participants.contains(id), "WAR HISTORY: " + profile.getName(), id.toString());
    }

    private static int logClaim(CommandContext<CommandSource> ctx, int page) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().asPlayer();
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        Claim claim = FlanBridge.parentAt(player.getServerWorld(), player.getPosition());
        if (claim == null) {
            return fail(ctx, "Stand inside a claim.");
        }
        UUID claimId = claim.getClaimID();
        return log(ctx, page, e -> claimId.equals(e.claimId), "claim " + FlanBridge.label(claim));
    }

    private static int logKingdom(CommandContext<CommandSource> ctx, int page) {
        String name = StringArgumentType.getString(ctx, "kingdom");
        if (!WarConfig.LB_KINGDOMS.get()) {
            return fail(ctx, "Kingdom statistics are disabled on this server.");
        }
        return log(ctx, page, e -> matches(name, e.actingKingdomId, e.actingKingdomName)
                || matches(name, e.previousKingdomId, e.previousKingdomName)
                || matches(name, e.opposingKingdomId, e.opposingKingdomName), "kingdom " + name);
    }

    private static boolean matches(String query, String id, String name) {
        return (id != null && id.equalsIgnoreCase(query)) || (name != null && name.equalsIgnoreCase(query));
    }

    // ------------------------------------------------------------------ archives

    private static int listSeasons(CommandContext<CommandSource> ctx) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        Season season = core.stats().season();
        header(ctx, "SEASONS");
        if (season != null) {
            line(ctx, "Current: " + season.id + " - " + season.name + " (since " + when(season.startedAt) + ")"
                    + (season.isActive() ? "" : " - ended " + when(season.endedAt)), TextFormatting.YELLOW);
        }
        List<String> ids = core.stats().archiveIds();
        if (ids.isEmpty()) {
            line(ctx, "No archived seasons yet.", TextFormatting.GRAY);
        }
        for (String id : ids) {
            line(ctx, "Archived: " + id + "  (/war seasons " + id + ")", TextFormatting.WHITE);
        }
        return 1;
    }

    private static int showArchive(CommandContext<CommandSource> ctx, String id) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        WarStats.SeasonArchive archive;
        try {
            archive = core.stats().loadArchive(id);
        } catch (IOException e) {
            return fail(ctx, "Cannot read archive " + id + ": " + e.getMessage());
        }
        header(ctx, "ARCHIVED SEASON " + id);
        if (archive.season != null) {
            line(ctx, archive.season.name + ": " + when(archive.season.startedAt) + " to "
                    + when(archive.season.endedAt > 0 ? archive.season.endedAt : archive.archivedAt)
                    + " (" + archive.season.events.size() + " events)", TextFormatting.GRAY);
        }
        line(ctx, "Final player ranking:", TextFormatting.YELLOW);
        for (int i = 0; i < Math.min(10, archive.finalPlayers.size()); i++) {
            StatRow r = archive.finalPlayers.get(i);
            line(ctx, "#" + (i + 1) + "  " + display(r.name, r.id) + "  -  " + r.conquests + " conquests, " + r.defenses
                    + " defenses, " + r.conqueredHeld + " held, " + r.lost + " lost", TextFormatting.WHITE);
        }
        if (!archive.finalKingdoms.isEmpty()) {
            line(ctx, "Final kingdom ranking:", TextFormatting.YELLOW);
            for (int i = 0; i < Math.min(10, archive.finalKingdoms.size()); i++) {
                StatRow r = archive.finalKingdoms.get(i);
                line(ctx, "#" + (i + 1) + "  " + display(r.name, r.id) + "  -  " + r.conquests + " conquests, "
                        + r.defenses + " defenses, " + r.conqueredHeld + " held, " + r.lost + " lost", TextFormatting.WHITE);
            }
        }
        line(ctx, "War log: /war seasons " + id + " log", TextFormatting.DARK_GRAY);
        return 1;
    }

    private static int archiveLog(CommandContext<CommandSource> ctx, int page) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "season");
        try {
            WarStats.SeasonArchive archive = core.stats().loadArchive(id);
            List<WarEvent> events = archive.season == null ? new ArrayList<WarEvent>() : archive.season.events;
            return printLog(ctx, events, page, null, "WAR HISTORY OF SEASON " + id, null);
        } catch (IOException e) {
            return fail(ctx, "Cannot read archive " + id + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ immunity (admin)

    private enum ImmunityAction { INSPECT, CLEAR, SET }

    private static int immunity(CommandContext<CommandSource> ctx, ImmunityAction action, String duration)
            throws CommandSyntaxException {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        ClaimRecord record;
        String raw = null;
        try {
            raw = StringArgumentType.getString(ctx, "claim");
        } catch (IllegalArgumentException noClaimArgument) {
            raw = null;
        }
        if (raw != null) {
            UUID id;
            try {
                id = UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                return fail(ctx, "Not a claim UUID: " + raw);
            }
            record = core.data().claim(id);
        } else {
            ServerPlayerEntity admin = ctx.getSource().asPlayer();
            Claim claim = FlanBridge.parentAt(admin.getServerWorld(), admin.getPosition());
            if (claim == null || claim.isAdminClaim()) {
                return fail(ctx, "Stand inside an ordinary claim or give a claim UUID.");
            }
            record = core.lifecycle().recordFor(claim);
        }
        if (record == null) {
            return fail(ctx, "No Flan Warfare record for that claim.");
        }
        long now = System.currentTimeMillis();
        switch (action) {
            case CLEAR:
                record.immuneUntil = 0L;
                core.data().markDirty();
                core.saveNow();
                core.audit("Admin {} cleared recapture immunity of claim {}", ctx.getSource().getName(), record.claimId);
                line(ctx, "Recapture immunity cleared for " + record.claimId + ".", TextFormatting.GREEN);
                return 1;
            case SET:
                long millis;
                try {
                    millis = DurationParser.parseMillis(duration);
                } catch (IllegalArgumentException e) {
                    return fail(ctx, e.getMessage());
                }
                record.immuneUntil = millis == 0 ? 0L : now + millis;
                core.data().markDirty();
                core.saveNow();
                core.audit("Admin {} set recapture immunity of claim {} to {}", ctx.getSource().getName(), record.claimId,
                        duration);
                line(ctx, millis == 0 ? "Recapture immunity removed for " + record.claimId + "."
                        : "Claim " + record.claimId + " is immune to conquest for " + Msg.duration(millis)
                        + " (until " + when(record.immuneUntil) + ").", TextFormatting.GREEN);
                return 1;
            default:
                long left = record.immunityRemaining(now);
                line(ctx, "Claim " + record.claimId + (left > 0 ? ": immune for " + Msg.duration(left) + " (until "
                        + when(record.immuneUntil) + ")" : ": no recapture immunity") + ".", TextFormatting.YELLOW);
                return 1;
        }
    }

    // ------------------------------------------------------------------ seasons (admin)

    private static int seasonInfo(CommandContext<CommandSource> ctx) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        Season season = core.stats().season();
        if (season == null) {
            line(ctx, "No current season. Statistics are not being recorded. Start one with /war admin season start <name>.",
                    TextFormatting.YELLOW);
        } else {
            line(ctx, "Season " + season.id + " \"" + season.name + "\": started " + when(season.startedAt)
                    + (season.isActive() ? ", running" : ", ended " + when(season.endedAt) + " (not recording; archive it or start a new one)")
                    + ", " + season.events.size() + " events.", TextFormatting.YELLOW);
        }
        line(ctx, "Recording: " + core.stats().recording() + ". Archives: " + core.stats().archiveIds().size() + ".",
                TextFormatting.GRAY);
        return 1;
    }

    private static int seasonOp(CommandContext<CommandSource> ctx, String op) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        String admin = ctx.getSource().getName();
        String problem;
        String done;
        switch (op) {
            case "start":
                String name = StringArgumentType.getString(ctx, "name").trim();
                if (name.isEmpty() || name.length() > 64) {
                    return fail(ctx, "Season names must be 1-64 characters.");
                }
                problem = core.stats().startSeason(name);
                done = "Season \"" + name + "\" started.";
                break;
            case "end":
                problem = core.stats().endSeason();
                done = "Season ended. Statistics are frozen; archive it with /war admin season archive.";
                break;
            case "archive":
                problem = core.stats().archiveCurrent("archived by " + admin);
                done = "Season archived. View it with /war seasons.";
                break;
            default:
                problem = core.stats().resetSeason(admin);
                done = "Season statistics reset. A backup archive was written first; claims, ownership and immunity are unchanged.";
                break;
        }
        if (problem != null) {
            return fail(ctx, problem);
        }
        core.audit("Admin {}: season {}", admin, op);
        line(ctx, done, TextFormatting.GREEN);
        return 1;
    }

    private static int statsCheck(CommandContext<CommandSource> ctx) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        core.lifecycle().reconcileAll();
        for (String text : core.stats().reconcile()) {
            line(ctx, text, TextFormatting.YELLOW);
        }
        core.saveNow();
        core.audit("Admin {}: statistics check", ctx.getSource().getName());
        return 1;
    }
}
