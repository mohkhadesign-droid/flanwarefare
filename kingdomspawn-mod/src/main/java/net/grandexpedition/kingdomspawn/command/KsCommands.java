package net.grandexpedition.kingdomspawn.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.grandexpedition.kingdomspawn.KingdomSpawn;
import net.grandexpedition.kingdomspawn.Msg;
import net.grandexpedition.kingdomspawn.Validation;
import net.grandexpedition.kingdomspawn.WorldUtil;
import net.grandexpedition.kingdomspawn.config.KsConfig;
import net.grandexpedition.kingdomspawn.data.KsData;
import net.grandexpedition.kingdomspawn.deploy.Places;
import net.grandexpedition.kingdomspawn.deploy.Terrain;
import net.grandexpedition.kingdomspawn.search.BiomeSearch;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.ISuggestionProvider;
import net.minecraft.command.arguments.ResourceLocationArgument;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /kingdomspawn (operators) plus the player commands /spawn and /return (names configurable). */
public final class KsCommands {
    private KsCommands() {
    }

    private static final SuggestionProvider<CommandSource> KINGDOMS = (ctx, b) ->
            ISuggestionProvider.suggest(KingdomSpawn.config().kingdoms.keySet(), b);

    private static final SuggestionProvider<CommandSource> BIOMES = (ctx, b) ->
            ISuggestionProvider.suggestIterable(WorldUtil.biomes(ctx.getSource().getServer()).keySet(), b);

    private static final SuggestionProvider<CommandSource> PLAYERS = (ctx, b) -> {
        List<String> names = new ArrayList<>(Arrays.asList(ctx.getSource().getServer().getOnlinePlayerNames()));
        for (KsConfig.Kingdom k : KingdomSpawn.config().kingdoms.values()) {
            for (KsConfig.PlayerEntry p : k.players) {
                names.add(p.name != null && !p.name.isEmpty() ? p.name : p.uuid);
            }
        }
        return ISuggestionProvider.suggest(names, b);
    };

    public static void register(CommandDispatcher<CommandSource> d) {
        KsConfig cfg = KingdomSpawn.config();
        int level = cfg.admin_permission_level;
        d.register(Commands.literal("kingdomspawn").requires(s -> s.hasPermissionLevel(level))
                .then(Commands.literal("kingdoms").executes(KsCommands::kingdoms))
                .then(Commands.literal("assign").then(player().then(kingdom().executes(KsCommands::assign))))
                .then(Commands.literal("unassign").then(player().executes(KsCommands::unassign)))
                .then(Commands.literal("players").then(kingdom().executes(KsCommands::players)))
                .then(Commands.literal("resetplayer").then(player().executes(KsCommands::resetPlayer)))
                .then(Commands.literal("setbiome").then(kingdom().then(Commands.argument("biome",
                        ResourceLocationArgument.resourceLocation()).suggests(BIOMES).executes(KsCommands::setBiome))))
                .then(Commands.literal("find").then(kingdom().executes(ctx -> find(ctx,
                        Collections.singletonList(StringArgumentType.getString(ctx, "kingdom"))))))
                .then(Commands.literal("findall").executes(KsCommands::findAll))
                .then(Commands.literal("cancel").executes(KsCommands::cancel))
                .then(Commands.literal("status").executes(KsCommands::status))
                .then(Commands.literal("preview").then(kingdom().executes(KsCommands::preview)))
                .then(Commands.literal("approve").then(kingdom().executes(KsCommands::approve)))
                .then(Commands.literal("sethere").then(kingdom().executes(KsCommands::setHere)))
                .then(Commands.literal("tp").then(kingdom().executes(KsCommands::tp)))
                .then(Commands.literal("info").then(kingdom().executes(KsCommands::info)))
                .then(Commands.literal("sethub").executes(KsCommands::setHub))
                .then(Commands.literal("setholding").executes(KsCommands::setHolding))
                .then(Commands.literal("reload").executes(KsCommands::reload)));
        if (cfg.teleport.register_spawn_commands) {
            d.register(Commands.literal(cfg.teleport.spawn_command)
                    .executes(ctx -> KingdomSpawn.teleports().requestSpawn(ctx.getSource().asPlayer())));
            d.register(Commands.literal(cfg.teleport.return_command)
                    .executes(ctx -> KingdomSpawn.teleports().requestReturn(ctx.getSource().asPlayer())));
        }
    }

    private static RequiredArgumentBuilder<CommandSource, String> kingdom() {
        return Commands.argument("kingdom", StringArgumentType.word()).suggests(KINGDOMS);
    }

    private static RequiredArgumentBuilder<CommandSource, String> player() {
        return Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS);
    }

    // ------------------------------------------------------------------ helpers

    private static void reply(CommandContext<CommandSource> ctx, String text) {
        Msg.send(ctx.getSource(), Msg.info(text));
    }

    private static int error(CommandContext<CommandSource> ctx, String text) {
        Msg.send(ctx.getSource(), Msg.error(text));
        return 0;
    }

    private static String kingdomArg(CommandContext<CommandSource> ctx) {
        String k = StringArgumentType.getString(ctx, "kingdom");
        return KingdomSpawn.config().kingdoms.containsKey(k) ? k : null;
    }

    /** UUID or name. Names of players who never joined are resolved through the profile cache (may contact Mojang). */
    private static GameProfile resolve(MinecraftServer server, String arg) {
        UUID id = KsConfig.parseUuid(arg);
        if (id != null) {
            GameProfile known = server.getPlayerProfileCache().getProfileByUUID(id);
            return known != null ? known : new GameProfile(id, null);
        }
        ServerPlayerEntity online = server.getPlayerList().getPlayerByUsername(arg);
        if (online != null) {
            return online.getGameProfile();
        }
        for (KsConfig.Kingdom k : KingdomSpawn.config().kingdoms.values()) {
            for (KsConfig.PlayerEntry p : k.players) {
                if (arg.equalsIgnoreCase(p.name) && KsConfig.parseUuid(p.uuid) != null) {
                    return new GameProfile(KsConfig.parseUuid(p.uuid), p.name);
                }
            }
        }
        return server.getPlayerProfileCache().getGameProfileForUsername(arg);
    }

    private static String label(GameProfile p) {
        return (p.getName() != null ? p.getName() + " " : "") + "(" + p.getId() + ")";
    }

    // ------------------------------------------------------------------ assignments

    private static int kingdoms(CommandContext<CommandSource> ctx) {
        KsConfig c = KingdomSpawn.config();
        KsData data = KingdomSpawn.data();
        for (Map.Entry<String, KsConfig.Kingdom> e : c.kingdoms.entrySet()) {
            KsData.Location a = data.approved.get(e.getKey());
            reply(ctx, e.getKey() + " \"" + c.displayName(e.getKey()) + "\": biome " + (e.getValue().biome.isEmpty()
                    ? "(none)" : e.getValue().biome) + ", " + e.getValue().players.size() + " players, location "
                    + (a == null ? "not approved" : a.center.toString()));
        }
        return c.kingdoms.size();
    }

    private static int assign(CommandContext<CommandSource> ctx) {
        String kingdom = kingdomArg(ctx);
        if (kingdom == null) {
            return error(ctx, "Unknown kingdom. Known: " + KingdomSpawn.config().kingdoms.keySet());
        }
        MinecraftServer server = ctx.getSource().getServer();
        GameProfile profile = resolve(server, StringArgumentType.getString(ctx, "player"));
        if (profile == null || profile.getId() == null) {
            return error(ctx, "Unknown player. Use their UUID if they never joined and the name cannot be looked up.");
        }
        KsConfig c = KingdomSpawn.config();
        String previous = c.kingdomOf(profile.getId());
        for (KsConfig.Kingdom k : c.kingdoms.values()) {
            k.players.removeIf(p -> profile.getId().equals(KsConfig.parseUuid(p.uuid)));
        }
        c.kingdoms.get(kingdom).players.add(new KsConfig.PlayerEntry(profile.getId().toString(), profile.getName()));
        String err = KingdomSpawn.saveConfig();
        if (err != null) {
            return error(ctx, err);
        }
        reply(ctx, label(profile) + " assigned to " + kingdom + (previous != null && !previous.equals(kingdom)
                ? " (was " + previous + "; an already deployed player is not moved)" : "") + ".");
        int sent = KingdomSpawn.deployer().deployWaiting(server, kingdom);
        if (sent > 0) {
            reply(ctx, sent + " waiting player(s) sent to " + kingdom + ".");
        }
        return 1;
    }

    private static int unassign(CommandContext<CommandSource> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        GameProfile profile = resolve(server, StringArgumentType.getString(ctx, "player"));
        if (profile == null || profile.getId() == null) {
            return error(ctx, "Unknown player.");
        }
        KsConfig c = KingdomSpawn.config();
        boolean removed = false;
        for (KsConfig.Kingdom k : c.kingdoms.values()) {
            removed |= k.players.removeIf(p -> profile.getId().equals(KsConfig.parseUuid(p.uuid)));
        }
        if (!removed) {
            return error(ctx, label(profile) + " is not assigned to any kingdom.");
        }
        String err = KingdomSpawn.saveConfig();
        if (err != null) {
            return error(ctx, err);
        }
        reply(ctx, label(profile) + " unassigned. (Their deployment status is unchanged.)");
        return 1;
    }

    private static int players(CommandContext<CommandSource> ctx) {
        String kingdom = kingdomArg(ctx);
        if (kingdom == null) {
            return error(ctx, "Unknown kingdom.");
        }
        KsData data = KingdomSpawn.data();
        List<KsConfig.PlayerEntry> list = KingdomSpawn.config().kingdoms.get(kingdom).players;
        reply(ctx, kingdom + " has " + list.size() + " assigned player(s):");
        for (KsConfig.PlayerEntry p : list) {
            UUID id = KsConfig.parseUuid(p.uuid);
            String state = id == null ? "invalid UUID" : data.deployed.containsKey(id.toString()) ? "deployed"
                    : data.held.contains(id.toString()) ? "waiting in holding area"
                    : data.forced.contains(id.toString()) ? "will be deployed at next login" : "not joined yet";
            Msg.send(ctx.getSource(), new StringTextComponent("  " + (p.name != null ? p.name + " " : "") + p.uuid
                    + " - " + state).mergeStyle(TextFormatting.GRAY));
        }
        return list.size();
    }

    private static int resetPlayer(CommandContext<CommandSource> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        GameProfile profile = resolve(server, StringArgumentType.getString(ctx, "player"));
        if (profile == null || profile.getId() == null) {
            return error(ctx, "Unknown player.");
        }
        KsData data = KingdomSpawn.data();
        String id = profile.getId().toString();
        data.deployed.remove(id);
        data.forced.add(id);
        data.markDirty();
        data.save();
        reply(ctx, label(profile) + " will be deployed to their kingdom at their next login.");
        return 1;
    }

    // ------------------------------------------------------------------ biomes and search

    private static int setBiome(CommandContext<CommandSource> ctx) {
        String kingdom = kingdomArg(ctx);
        if (kingdom == null) {
            return error(ctx, "Unknown kingdom.");
        }
        ResourceLocation biome = ResourceLocationArgument.getResourceLocation(ctx, "biome");
        if (!WorldUtil.biomeExists(ctx.getSource().getServer(), biome)) {
            return error(ctx, "Biome '" + biome + "' is not registered on this server. Use the exact registry id "
                    + "(tab completion lists them). No substitute is chosen.");
        }
        KingdomSpawn.config().kingdoms.get(kingdom).biome = biome.toString();
        String err = KingdomSpawn.saveConfig();
        if (err != null) {
            return error(ctx, err);
        }
        reply(ctx, kingdom + " biome set to " + biome + ". Search with /kingdomspawn find " + kingdom
                + " or /kingdomspawn findall.");
        return 1;
    }

    private static int find(CommandContext<CommandSource> ctx, List<String> kingdoms) {
        for (String k : kingdoms) {
            if (!KingdomSpawn.config().kingdoms.containsKey(k)) {
                return error(ctx, "Unknown kingdom: " + k);
            }
        }
        BiomeSearch running = KingdomSpawn.search();
        if (running != null && running.running()) {
            return error(ctx, "A search is already running: " + running.status() + " (/kingdomspawn cancel)");
        }
        UUID initiator = ctx.getSource().getEntity() instanceof ServerPlayerEntity
                ? ctx.getSource().getEntity().getUniqueID() : null;
        try {
            KingdomSpawn.setSearch(new BiomeSearch(ctx.getSource().getServer(), initiator, kingdoms,
                    KingdomSpawn.config(), KingdomSpawn.data()));
        } catch (IllegalArgumentException e) {
            return error(ctx, "Cannot search: " + e.getMessage());
        }
        if (initiator == null) {
            reply(ctx, "Search started; progress is written to the server log.");
        }
        return 1;
    }

    private static int findAll(CommandContext<CommandSource> ctx) {
        List<String> all = new ArrayList<>();
        for (Map.Entry<String, KsConfig.Kingdom> e : KingdomSpawn.config().kingdoms.entrySet()) {
            if (e.getValue().biome == null || e.getValue().biome.isEmpty()) {
                return error(ctx, e.getKey() + " has no biome yet: /kingdomspawn setbiome " + e.getKey() + " <biome_id>");
            }
            all.add(e.getKey());
        }
        return find(ctx, all);
    }

    private static int cancel(CommandContext<CommandSource> ctx) {
        BiomeSearch s = KingdomSpawn.search();
        if (s == null || !s.running()) {
            return error(ctx, "No search is running.");
        }
        s.cancel("by " + ctx.getSource().getName());
        return 1;
    }

    private static int status(CommandContext<CommandSource> ctx) {
        BiomeSearch s = KingdomSpawn.search();
        reply(ctx, s == null ? "No search has been run since the server started." : s.status());
        return 1;
    }

    // ------------------------------------------------------------------ locations

    private static int preview(CommandContext<CommandSource> ctx) {
        String kingdom = kingdomArg(ctx);
        if (kingdom == null) {
            return error(ctx, "Unknown kingdom.");
        }
        KsData.Location p = KingdomSpawn.data().proposals.get(kingdom);
        if (p == null) {
            return error(ctx, "No proposal for " + kingdom + ". Run /kingdomspawn find " + kingdom + " or findall.");
        }
        IFormattableTextComponent t = new StringTextComponent("").appendSibling(Msg.info(kingdom + " proposal: "
                + p.center + ", " + (int) p.center.distanceFromCenter() + " blocks from 0,0, biome " + p.biome
                + separations(kingdom, p.center) + " "));
        t.appendSibling(Msg.button("teleport there", "/kingdomspawn tp " + kingdom)).appendSibling(
                new StringTextComponent(" ")).appendSibling(Msg.button("approve", "/kingdomspawn approve " + kingdom));
        Msg.send(ctx.getSource(), t);
        return 1;
    }

    /** Distances to the other kingdoms' approved (or proposed) points. */
    private static String separations(String kingdom, KsData.Point at) {
        StringBuilder sb = new StringBuilder();
        KsData data = KingdomSpawn.data();
        for (String other : KingdomSpawn.config().kingdoms.keySet()) {
            if (other.equals(kingdom)) {
                continue;
            }
            KsData.Location l = data.approved.containsKey(other) ? data.approved.get(other) : data.proposals.get(other);
            if (l != null && l.center != null && l.center.dimension.equals(at.dimension)) {
                double d = Math.hypot(l.center.x - at.x, l.center.z - at.z);
                sb.append(sb.length() == 0 ? ". Distance to " : ", ").append(other).append(' ').append((int) d);
            }
        }
        return sb.toString();
    }

    private static int approve(CommandContext<CommandSource> ctx) {
        String kingdom = kingdomArg(ctx);
        if (kingdom == null) {
            return error(ctx, "Unknown kingdom.");
        }
        KsData data = KingdomSpawn.data();
        KsData.Location p = data.proposals.remove(kingdom);
        if (p == null) {
            return error(ctx, "No proposal for " + kingdom + " to approve.");
        }
        data.approved.put(kingdom, p);
        data.markDirty();
        data.save();
        reply(ctx, kingdom + " deployment point approved: " + p.center + ". New members arrive there on first join.");
        int sent = KingdomSpawn.deployer().deployWaiting(ctx.getSource().getServer(), kingdom);
        if (sent > 0) {
            reply(ctx, sent + " waiting player(s) sent to " + kingdom + ".");
        }
        return 1;
    }

    private static int setHere(CommandContext<CommandSource> ctx) throws CommandSyntaxException {
        String kingdom = kingdomArg(ctx);
        if (kingdom == null) {
            return error(ctx, "Unknown kingdom.");
        }
        ServerPlayerEntity admin = ctx.getSource().asPlayer();
        ServerWorld world = admin.getServerWorld();
        BlockPos pos = admin.getPosition();
        KsData.Location loc = new KsData.Location();
        loc.center = new KsData.Point(WorldUtil.dimensionId(world), pos.getX(), pos.getY(), pos.getZ());
        String biome = KingdomSpawn.config().kingdoms.get(kingdom).biome;
        ResourceLocation here = WorldUtil.biomeId(world, world.getBiome(pos));
        loc.biome = here == null ? biome : here.toString();
        loc.source = "manual";
        loc.time = System.currentTimeMillis();
        KsData data = KingdomSpawn.data();
        data.approved.put(kingdom, loc);
        data.proposals.remove(kingdom);
        data.markDirty();
        data.save();
        reply(ctx, kingdom + " deployment point set to your position " + loc.center + separations(kingdom, loc.center) + ".");
        if (!biome.isEmpty() && here != null && !biome.equals(here.toString())) {
            Msg.send(ctx.getSource(), Msg.error("Note: you are in " + here + ", not " + kingdom + "'s biome " + biome + "."));
        }
        KsConfig.Deployment dc = KingdomSpawn.config().deployment;
        if (Terrain.findCenter(world, pos.getX(), pos.getZ(), here, dc.radius_min, dc.radius_max,
                KingdomSpawn.config().search.max_slope, KingdomSpawn.config().search.min_safe_slots) == null) {
            Msg.send(ctx.getSource(), Msg.error("Warning: fewer than " + KingdomSpawn.config().search.min_safe_slots
                    + " safe, level arrival spots 5-10 blocks around here. Members may be held back."));
        }
        int sent = KingdomSpawn.deployer().deployWaiting(ctx.getSource().getServer(), kingdom);
        if (sent > 0) {
            reply(ctx, sent + " waiting player(s) sent to " + kingdom + ".");
        }
        return 1;
    }

    private static int tp(CommandContext<CommandSource> ctx) throws CommandSyntaxException {
        String kingdom = kingdomArg(ctx);
        if (kingdom == null) {
            return error(ctx, "Unknown kingdom.");
        }
        KsData data = KingdomSpawn.data();
        KsData.Location l = data.proposals.containsKey(kingdom) ? data.proposals.get(kingdom) : data.approved.get(kingdom);
        if (l == null) {
            return error(ctx, kingdom + " has no proposed or approved point.");
        }
        ServerPlayerEntity admin = ctx.getSource().asPlayer();
        ServerWorld world = WorldUtil.world(ctx.getSource().getServer(), l.center.dimension);
        if (world == null) {
            return error(ctx, "Unknown dimension " + l.center.dimension);
        }
        admin.teleport(world, l.center.x + 0.5D, l.center.y, l.center.z + 0.5D, admin.rotationYaw, admin.rotationPitch);
        reply(ctx, "Teleported to " + kingdom + "'s " + (data.proposals.containsKey(kingdom) ? "proposed" : "approved")
                + " point " + l.center + ".");
        return 1;
    }

    private static int info(CommandContext<CommandSource> ctx) {
        String kingdom = kingdomArg(ctx);
        if (kingdom == null) {
            return error(ctx, "Unknown kingdom.");
        }
        KsConfig c = KingdomSpawn.config();
        KsData data = KingdomSpawn.data();
        KsConfig.Kingdom k = c.kingdoms.get(kingdom);
        reply(ctx, kingdom + " \"" + c.displayName(kingdom) + "\"");
        reply(ctx, "  Biome: " + (k.biome.isEmpty() ? "(none)" : k.biome));
        KsData.Location a = data.approved.get(kingdom);
        reply(ctx, "  Approved point: " + (a == null ? "none" : a.center + ", " + (int) a.center.distanceFromCenter()
                + " blocks from 0,0 (" + a.source + ")" + separations(kingdom, a.center)));
        KsData.Location p = data.proposals.get(kingdom);
        if (p != null) {
            reply(ctx, "  Pending proposal: " + p.center + ", " + (int) p.center.distanceFromCenter() + " blocks from 0,0");
        }
        int deployed = 0;
        for (KsData.Deployed d : data.deployed.values()) {
            if (kingdom.equals(d.kingdom)) {
                deployed++;
            }
        }
        reply(ctx, "  Players: " + k.players.size() + " assigned, " + deployed + " deployed. /kingdomspawn players " + kingdom);
        return 1;
    }

    private static int setHub(CommandContext<CommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity admin = ctx.getSource().asPlayer();
        Places.set(KingdomSpawn.config().hub, admin);
        String err = KingdomSpawn.saveConfig();
        if (err != null) {
            return error(ctx, err);
        }
        reply(ctx, "Hub set to your position. /" + KingdomSpawn.config().teleport.spawn_command + " now leads here.");
        return 1;
    }

    private static int setHolding(CommandContext<CommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity admin = ctx.getSource().asPlayer();
        KsConfig c = KingdomSpawn.config();
        if (c.holding.location == null) {
            c.holding.location = new KsConfig.Place();
        }
        Places.set(c.holding.location, admin);
        String err = KingdomSpawn.saveConfig();
        if (err != null) {
            return error(ctx, err);
        }
        reply(ctx, "Holding area set to your position.");
        return 1;
    }

    private static int reload(CommandContext<CommandSource> ctx) {
        for (String m : KingdomSpawn.reloadConfig()) {
            reply(ctx, m);
        }
        for (String m : Validation.check(ctx.getSource().getServer(), KingdomSpawn.config())) {
            Msg.send(ctx.getSource(), Msg.error(m));
        }
        reply(ctx, "Note: renamed /spawn or /return commands take effect after a server restart.");
        return 1;
    }
}
