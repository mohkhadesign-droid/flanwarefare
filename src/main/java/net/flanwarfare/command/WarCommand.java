package net.flanwarfare.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.player.PlayerClaimData;
import net.flanwarfare.FlanWarfare;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.config.WarConfig.RaidMode;
import net.flanwarfare.conquest.ConquestService;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.data.ClaimState;
import net.flanwarfare.data.ConquestTxn;
import net.flanwarfare.flan.Accounting;
import net.flanwarfare.flag.FlagService;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.ClockMath;
import net.flanwarfare.util.Msg;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.ISuggestionProvider;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * /war status                          raid state and next transition
 * /war capture                         captures on your claims or near you
 * /war alerts on|off                   mute or unmute live raid alerts for yourself
 * /war admin reload                    re-validate config and rebuild caches
 * /war admin version                   version, build number, Minecraft/Forge/Flan versions
 * /war admin status                    diagnostics
 * /war admin mode disabled|always|custom
 * /war admin capturetime [seconds|reset] global capture duration (default 90 s)
 * /war admin setwindow HH:MM HH:MM     custom raid window
 * /war admin flag restore <claim>      rebuild a missing flag (loads its chunk)
 * /war admin flag relocate <claim>     put the flag where you stand (also activates a pending claim)
 * /war admin claim inspect [<claim>]   state, flag, ownership, accounting, conquest journal
 * /war admin claim repair <claim>      re-run reconciliation for one claim
 * /war admin capture cancel <claim>
 * /war admin conquest rollback <claim> hand the claim back to its previous owner
 * </pre>
 * Claim arguments are Flan claim UUIDs (tab-completed). Admin commands need the configured
 * permission level (default 2, i.e. operators).
 */
public final class WarCommand {
    private WarCommand() {
    }

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

    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        dispatcher.register(Commands.literal("war")
                .then(Commands.literal("status").executes(WarCommand::status))
                .then(Commands.literal("capture").executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) -> {
                    List<String> lines = core.capture().describeFor(p);
                    if (lines.isEmpty()) {
                        Msg.send(p, Msg.info("No captures in progress near you or on your claims."));
                    }
                    for (String line : lines) {
                        Msg.send(p, Msg.info(line));
                    }
                    return 1;
                })))
                .then(Commands.literal("alerts")
                        .then(Commands.literal("on").executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) -> {
                            core.data().setMuted(p.getUniqueID(), false);
                            Msg.send(p, Msg.ok("Raid alerts for your claims are ON."));
                            return 1;
                        })))
                        .then(Commands.literal("off").executes(ctx -> FlagCommand.withPlayer(ctx, (core, p) -> {
                            core.data().setMuted(p.getUniqueID(), true);
                            Msg.send(p, Msg.warn("Raid alerts for your claims are OFF. Turn them back on with /war alerts on."));
                            return 1;
                        }))))
                .then(Commands.literal("admin")
                        .requires(src -> src.hasPermissionLevel(Msg.adminLevel()))
                        .then(Commands.literal("reload").executes(WarCommand::reload))
                        .then(Commands.literal("version").executes(ctx -> {
                            for (String line : FlanWarfare.versionLines()) {
                                reply(ctx, line);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("status").executes(WarCommand::adminStatus))
                        .then(Commands.literal("mode")
                                .then(Commands.literal("disabled").executes(ctx -> setMode(ctx, RaidMode.DISABLED)))
                                .then(Commands.literal("always").executes(ctx -> setMode(ctx, RaidMode.ALWAYS)))
                                .then(Commands.literal("custom").executes(ctx -> setMode(ctx, RaidMode.CUSTOM))))
                        .then(Commands.literal("capturetime")
                                .executes(WarCommand::showCaptureTime)
                                .then(Commands.literal("reset")
                                        .executes(ctx -> setCaptureTime(ctx, WarConfig.DEFAULT_CAPTURE_SECONDS)))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600))
                                        .executes(ctx -> setCaptureTime(ctx, IntegerArgumentType.getInteger(ctx, "seconds")))))
                        .then(captureZoneNode())
                        .then(Commands.literal("setwindow")
                                .then(Commands.argument("window", StringArgumentType.greedyString()).executes(WarCommand::setWindow)))
                        .then(Commands.literal("flag")
                                .then(Commands.literal("restore").then(claimArg().executes(WarCommand::flagRestore)))
                                .then(Commands.literal("relocate").then(claimArg().executes(WarCommand::flagRelocate))))
                        .then(Commands.literal("claim")
                                .then(Commands.literal("inspect")
                                        .executes(ctx -> inspect(ctx, null))
                                        .then(claimArg().executes(ctx -> inspect(ctx, StringArgumentType.getString(ctx, "claim")))))
                                .then(Commands.literal("repair").then(claimArg().executes(WarCommand::repair))))
                        .then(Commands.literal("capture")
                                .then(Commands.literal("cancel").then(claimArg().executes(WarCommand::captureCancel))))
                        .then(Commands.literal("conquest")
                                .then(Commands.literal("rollback").then(claimArg().executes(WarCommand::rollback))))));
    }

    /** /... capturezone radius [blocks|reset]: shared by /war admin and /flanwarfare. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSource> captureZoneNode() {
        return Commands.literal("capturezone")
                .executes(WarCommand::showCaptureRadius)
                .then(Commands.literal("radius")
                        .executes(WarCommand::showCaptureRadius)
                        .then(Commands.literal("reset")
                                .executes(ctx -> setCaptureRadius(ctx, WarConfig.DEFAULT_CAPTURE_RADIUS)))
                        .then(Commands.argument("blocks", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(1.0, 32.0))
                                .executes(ctx -> setCaptureRadius(ctx,
                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "blocks")))));
    }

    /** Admin-only root command: /flanwarfare capturezone radius [blocks|reset]. */
    public static void registerRoot(CommandDispatcher<CommandSource> dispatcher) {
        dispatcher.register(Commands.literal("flanwarfare")
                .requires(src -> src.hasPermissionLevel(Msg.adminLevel()))
                .then(captureZoneNode())
                .then(Commands.literal("designer")
                        .then(Commands.literal("give")
                                .executes(ctx -> giveStation(ctx, java.util.Collections.singletonList(ctx.getSource().asPlayer())))
                                .then(Commands.argument("targets", net.minecraft.command.arguments.EntityArgument.players())
                                        .executes(ctx -> giveStation(ctx,
                                                net.minecraft.command.arguments.EntityArgument.getPlayers(ctx, "targets")))))
                        .then(Commands.literal("list").executes(WarCommand::listStations))));
    }

    /** /flanwarfare designer give [targets]: the only way to obtain a Flag Designer station. */
    private static int giveStation(CommandContext<CommandSource> ctx,
                                   java.util.Collection<net.minecraft.entity.player.ServerPlayerEntity> targets) {
        net.minecraft.item.Item item = net.flanwarfare.flag.FlagDesigner.stationItem();
        if (item == null) {
            ctx.getSource().sendErrorMessage(new net.minecraft.util.text.StringTextComponent(
                    "The Flag Designer block needs Flan Warfare Flags 1.3 or newer on the server."));
            return 0;
        }
        for (net.minecraft.entity.player.ServerPlayerEntity target : targets) {
            net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(item);
            if (!target.inventory.addItemStackToInventory(stack)) {
                target.dropItem(stack, false);
            }
        }
        reply(ctx, "Gave a Flag Designer to " + targets.size() + " player(s). Place it, sneak-right-click it, "
                + "then right-click a claim flag to link them.");
        return targets.size();
    }

    private static int listStations(CommandContext<CommandSource> ctx) {
        WarCore core = WarCore.ready();
        if (core == null) {
            return 0;
        }
        java.util.Map<String, java.util.UUID> stations = core.designer().stations();
        if (stations.isEmpty()) {
            reply(ctx, "No Flag Designer stations are linked.");
            return 1;
        }
        StringBuilder sb = new StringBuilder("Flag Designer stations (" + stations.size() + "):");
        for (java.util.Map.Entry<String, java.util.UUID> e : stations.entrySet()) {
            String key = e.getKey();
            net.minecraft.util.math.BlockPos pos = net.minecraft.util.math.BlockPos.fromLong(
                    Long.parseLong(key.substring(key.lastIndexOf('|') + 1)));
            net.flanwarfare.data.ClaimRecord record = core.data().claim(e.getValue());
            sb.append("\n  ").append(key, 0, key.lastIndexOf('|')).append(' ')
                    .append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ())
                    .append(" -> claim ").append(e.getValue()).append(record == null || !record.hasFlag ? " (flag gone)" : "");
        }
        reply(ctx, sb.toString());
        return stations.size();
    }

    private static int showCaptureRadius(CommandContext<CommandSource> ctx) {
        double r = WarConfig.CAPTURE_RADIUS.get();
        reply(ctx, "Capture zone radius: " + fmt(r) + " blocks from the centre of the flagpole (" + fmt(2 * r)
                + " blocks across), for every flag. Height: within " + fmt(WarConfig.VERTICAL_TOLERANCE.get())
                + " blocks above or below the flag's base. Default " + fmt(WarConfig.DEFAULT_CAPTURE_RADIUS)
                + ". Change it with /flanwarfare capturezone radius <blocks>.");
        return 1;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? Integer.toString((int) v) : Double.toString(v);
    }

    /**
     * Sets the global capture radius. It lives only in the server config (the single setting
     * read live by capture detection, contesting, the ring and the distance readout), so it
     * applies to every flag at the next evaluation and survives restarts.
     */
    private static int setCaptureRadius(CommandContext<CommandSource> ctx, double blocks) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        double previous = WarConfig.CAPTURE_RADIUS.get();
        WarConfig.CAPTURE_RADIUS.set(blocks);
        WarConfig.CAPTURE_RADIUS.save();
        audit(core, ctx, "capture radius " + previous + " -> " + blocks);
        ctx.getSource().sendFeedback(Msg.ok("[Flan Warfare] Capture zone radius changed: " + fmt(previous) + " \u2192 "
                + fmt(blocks) + " blocks (" + fmt(2 * blocks) + " across) for all flags, effective immediately."
                + (core.capture().activeCount() > 0 ? " Running captures keep their percentage." : "")), true);
        if (WarConfig.MIN_FLAG_DISTANCE.get() < 2 * blocks) {
            ctx.getSource().sendFeedback(Msg.warn("Note: flags.min_flag_distance is " + WarConfig.MIN_FLAG_DISTANCE.get()
                    + ", so zones of flags closer than " + fmt(2 * blocks) + " blocks overlap."), false);
        }
        return 1;
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSource, String> claimArg() {
        return Commands.argument("claim", StringArgumentType.word()).suggests(CLAIMS);
    }

    private static void reply(CommandContext<CommandSource> ctx, String text) {
        ctx.getSource().sendFeedback(Msg.info(text), false);
    }

    private static int fail(CommandContext<CommandSource> ctx, String text) {
        ctx.getSource().sendErrorMessage(Msg.error(text));
        return 0;
    }

    private static WarCore core(CommandContext<CommandSource> ctx) {
        WarCore core = WarCore.ready();
        if (core == null) {
            fail(ctx, "Flan Warfare is not active (starting up or in safe mode). See /war admin status.");
        }
        return core;
    }

    private static ClaimRecord record(CommandContext<CommandSource> ctx, WarCore core) {
        String raw = StringArgumentType.getString(ctx, "claim");
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            fail(ctx, "Not a claim UUID: " + raw);
            return null;
        }
        ClaimRecord record = core.data().claim(id);
        if (record == null) {
            fail(ctx, "No Flan Warfare record for claim " + id + ".");
        }
        return record;
    }

    private static void audit(WarCore core, CommandContext<CommandSource> ctx, String what) {
        core.audit("Admin {}: {}", ctx.getSource().getName(), what);
    }

    // ------------------------------------------------------------------

    private static int status(CommandContext<CommandSource> ctx) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        reply(ctx, core.raid().statusLine() + " (Flan Warfare " + FlanWarfare.version() + ", capture zone "
                + WarConfig.CAPTURE_RADIUS.get() + " blocks)");
        return 1;
    }

    private static int reload(CommandContext<CommandSource> ctx) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        List<String> problems = WarConfig.validate();
        core.sides().init();
        core.flags().rebuildIndex();
        audit(core, ctx, "reload");
        reply(ctx, "Configuration re-validated (Forge reloads the file automatically when it is saved). "
                + (problems.isEmpty() ? "No problems." : "Problems: " + String.join("; ", problems)));
        return 1;
    }

    private static int adminStatus(CommandContext<CommandSource> ctx) {
        WarCore core = WarCore.get();
        if (core == null) {
            return fail(ctx, "Flan Warfare has not started.");
        }
        if (!core.isReady()) {
            return fail(ctx, "SAFE MODE: " + core.safeModeReason()
                    + ". Flan's ordinary protection applies; raids and flags are off. Repair or remove "
                    + WarCore.dataFile(core.server()) + " and restart.");
        }
        int pending = 0;
        int active = 0;
        int recovery = 0;
        int conquered = 0;
        List<String> recoveryIds = new ArrayList<>();
        for (ClaimRecord record : core.data().claims().values()) {
            if (record.state == ClaimState.PENDING_FLAG) {
                pending++;
            } else if (record.state == ClaimState.ACTIVE) {
                active++;
            } else {
                recovery++;
                recoveryIds.add(record.claimId.toString());
            }
            if (record.conquered) {
                conquered++;
            }
        }
        reply(ctx, "Flan Warfare " + FlanWarfare.version() + " | capture radius " + WarConfig.CAPTURE_RADIUS.get()
                + " blocks | capture time " + WarConfig.CAPTURE_SECONDS.get() + "s | conquest limit "
                + WarConfig.MAX_CONQUERED_PER_PLAYER.get() + " | flagpoles " + (WarConfig.POLE_ENABLED.get() ? "on" : "off")
                + " | member notices " + (WarConfig.MEMBER_NOTICES.get() ? "on" : "off"));
        reply(ctx, core.raid().statusLine());
        reply(ctx, "Claims: " + active + " active, " + pending + " without flag, " + recovery
                + " needing recovery, " + conquered + " conquered. Captures running: " + core.capture().activeCount());
        reply(ctx, "Teams: " + core.sides().status());
        net.flanwarfare.data.Season season = core.stats().season();
        reply(ctx, season == null ? "Season: none - statistics are NOT being recorded (/war admin season start <name>)."
                : "Season " + season.id + " \"" + season.name + "\": " + (season.isActive() ? "running" : "ended, not recording")
                + ", " + season.events.size() + " events.");
        for (String id : recoveryIds) {
            reply(ctx, "Needs recovery: " + id);
        }
        for (ClaimRecord record : core.lifecycle().expiredPending()) {
            reply(ctx, "Expired without flag (owner " + FlanBridge.playerName(core.server(), record.owner) + "): "
                    + record.claimId + (WarConfig.AUTO_ABANDON_EXPIRED.get() ? "" : " - auto_abandon is off"));
        }
        for (ConquestTxn txn : core.data().transactions()) {
            if (txn.phase == ConquestTxn.Phase.NEEDS_ADMIN || txn.phase == ConquestTxn.Phase.PENDING) {
                reply(ctx, "Conquest " + txn.txnId + " on claim " + txn.claimId + " is " + txn.phase + ": " + txn.note);
            }
        }
        for (String problem : WarConfig.validate()) {
            reply(ctx, "Config problem: " + problem);
        }
        reply(ctx, "Data file: " + core.data().file());
        return 1;
    }

    private static int setMode(CommandContext<CommandSource> ctx, RaidMode mode) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        WarConfig.RAID_MODE.set(mode);
        WarConfig.RAID_MODE.save();
        audit(core, ctx, "raid mode set to " + mode);
        reply(ctx, "Raid mode set to " + mode + ". " + core.raid().statusLine());
        return 1;
    }

    private static int showCaptureTime(CommandContext<CommandSource> ctx) {
        reply(ctx, "Global capture time: " + WarConfig.CAPTURE_SECONDS.get() + "s of uncontested capture (default "
                + WarConfig.DEFAULT_CAPTURE_SECONDS + "s). Change it with /war admin capturetime <seconds>.");
        return 1;
    }

    /**
     * Sets the global capture duration. The value lives only in the server config file (the
     * single authoritative setting), so it survives restarts and needs no rebuild. Running
     * captures keep their percentage and rescale on their next evaluation.
     */
    private static int setCaptureTime(CommandContext<CommandSource> ctx, int seconds) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        int previous = WarConfig.CAPTURE_SECONDS.get();
        WarConfig.CAPTURE_SECONDS.set(seconds);
        WarConfig.CAPTURE_SECONDS.save();
        audit(core, ctx, "capture time " + previous + "s -> " + seconds + "s");
        ctx.getSource().sendFeedback(Msg.ok("[Flan Warfare] Global capture time changed: " + previous + "s \u2192 " + seconds
                + "s." + (core.capture().activeCount() > 0
                ? " " + core.capture().activeCount() + " running capture(s) keep their percentage." : "")), true);
        return 1;
    }

    private static int setWindow(CommandContext<CommandSource> ctx) {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        String[] parts = StringArgumentType.getString(ctx, "window").trim().split("\\s+");
        if (parts.length != 2) {
            return fail(ctx, "Usage: /war admin setwindow HH:MM HH:MM (example: 15:00 06:00)");
        }
        int start;
        int end;
        try {
            start = ClockMath.parseClock(parts[0]);
            end = ClockMath.parseClock(parts[1]);
        } catch (IllegalArgumentException e) {
            return fail(ctx, e.getMessage());
        }
        if (start == end) {
            return fail(ctx, "Start and end must differ.");
        }
        WarConfig.RAID_START.set(parts[0]);
        WarConfig.RAID_END.set(parts[1]);
        WarConfig.RAID_START.save();
        WarConfig.RAID_END.save();
        audit(core, ctx, "raid window set to " + parts[0] + "-" + parts[1]);
        reply(ctx, "Raid window set to " + parts[0] + "-" + parts[1] + " (ticks " + start + "-" + end + ")."
                + (WarConfig.RAID_MODE.get() == RaidMode.CUSTOM ? "" : " Note: mode is " + WarConfig.RAID_MODE.get()
                + "; use /war admin mode custom to use the window."));
        return 1;
    }

    private static int flagRestore(CommandContext<CommandSource> ctx) {
        WarCore core = core(ctx);
        ClaimRecord record = core == null ? null : record(ctx, core);
        if (record == null) {
            return 0;
        }
        if (!record.hasFlag) {
            return fail(ctx, "This claim has no registered flag position. Use /war admin flag relocate.");
        }
        ServerWorld world = FlanBridge.world(core.server(), record.dimension);
        if (world == null) {
            return fail(ctx, "Dimension " + record.dimension + " is not loaded.");
        }
        BlockPos pos = record.flagPos();
        world.getChunk(pos); // admin action: load the chunk synchronously
        String problem = core.flags().restore(world, record, true);
        core.saveNow();
        audit(core, ctx, "flag restore " + record.claimId + (problem == null ? " ok" : " failed: " + problem));
        if (problem != null) {
            return fail(ctx, "Restore failed: " + problem + ". Clear the position or use relocate.");
        }
        reply(ctx, "Flag restored at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ". State: " + record.state);
        return 1;
    }

    private static int flagRelocate(CommandContext<CommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity admin = ctx.getSource().asPlayer();
        WarCore core = core(ctx);
        ClaimRecord record = core == null ? null : record(ctx, core);
        if (record == null) {
            return 0;
        }
        Claim claim = FlanBridge.find(core.server(), record);
        if (claim == null) {
            return fail(ctx, "The claim no longer exists in Flan.");
        }
        String problem = core.flags().adminRelocate(record, claim, admin);
        if (problem != null) {
            return fail(ctx, problem);
        }
        reply(ctx, "Flag placed at your position; claim is ACTIVE.");
        return 1;
    }

    private static int inspect(CommandContext<CommandSource> ctx, String rawId) throws CommandSyntaxException {
        WarCore core = core(ctx);
        if (core == null) {
            return 0;
        }
        ClaimRecord record;
        Claim claim;
        if (rawId == null) {
            ServerPlayerEntity admin = ctx.getSource().asPlayer();
            claim = FlanBridge.parentAt(admin.getServerWorld(), admin.getPosition());
            if (claim == null) {
                return fail(ctx, "You are not inside a claim.");
            }
            if (claim.isAdminClaim()) {
                reply(ctx, "Admin claim " + claim.getClaimID() + ": ignored by Flan Warfare (always protected).");
                return 1;
            }
            record = core.lifecycle().recordFor(claim);
        } else {
            record = record(ctx, core);
            if (record == null) {
                return 0;
            }
            claim = FlanBridge.find(core.server(), record);
        }
        reply(ctx, "Claim " + record.claimId + " in " + record.dimension);
        reply(ctx, "State " + record.state + (record.recoveryReason == null ? "" : " (" + record.recoveryReason + ")")
                + ", conquered=" + record.conquered + ", times conquered=" + record.timesConquered
                + ", live state " + core.warState(record));
        if (claim == null) {
            reply(ctx, "Flan: claim NOT FOUND.");
        } else {
            reply(ctx, "Flan owner " + FlanBridge.playerName(core.server(), claim.getOwner()) + " (" + claim.getOwner()
                    + "), record owner " + record.owner + ", area " + claim.getPlane() + " blocks, "
                    + claim.getAllSubclaims().size() + " subclaims, " + FlanBridge.members(claim).size() + " members, admin="
                    + claim.isAdminClaim());
            ServerPlayerEntity owner = core.server().getPlayerList().getPlayerByUUID(claim.getOwner());
            int exempt = Accounting.conqueredBlocks(core.server(), claim.getOwner());
            if (owner != null) {
                PlayerClaimData data = PlayerClaimData.get(owner);
                reply(ctx, "Owner accounting: used " + data.usedClaimBlocks() + " of " + (data.getClaimBlocks()
                        + data.getAdditionalClaims()) + " (conquered land exempted: " + exempt + ")");
            } else {
                reply(ctx, "Owner offline; conquered land exempted from their usage: " + exempt);
            }
        }
        if (record.hasFlag) {
            ServerWorld world = FlanBridge.world(core.server(), record.dimension);
            BlockPos pos = record.flagPos();
            String present = world == null || !world.isBlockLoaded(pos) ? "chunk not loaded"
                    : (core.flags().intact(world, record) ? "intact" : "DAMAGED (auto-restore pending)");
            reply(ctx, "Flag: " + FlagService.describe(record) + " - " + present + ", restore failures "
                    + record.restoreFailures + (core.flags().isAnimating(record.claimId) ? ", capture animation running" : ""));
        } else {
            reply(ctx, "No flag placed" + (record.flagEverPlaced ? " (one was placed before!)" : "") + ".");
        }
        int shown = 0;
        List<ConquestTxn> txns = core.data().transactions();
        for (int i = txns.size() - 1; i >= 0 && shown < 5; i--) {
            ConquestTxn txn = txns.get(i);
            if (txn.claimId.equals(record.claimId)) {
                reply(ctx, "Conquest " + txn.txnId + ": " + txn.phase + ", " + FlanBridge.playerName(core.server(), txn.oldOwner)
                        + " -> " + FlanBridge.playerName(core.server(), txn.newOwner)
                        + (txn.note == null ? "" : " (" + txn.note + ")"));
                shown++;
            }
        }
        return 1;
    }

    private static int repair(CommandContext<CommandSource> ctx) {
        WarCore core = core(ctx);
        ClaimRecord record = core == null ? null : record(ctx, core);
        if (record == null) {
            return 0;
        }
        Claim claim = FlanBridge.find(core.server(), record);
        if (claim == null) {
            core.lifecycle().reconcileAll();
            core.saveNow();
            audit(core, ctx, "repair " + record.claimId + ": claim missing, record removed");
            reply(ctx, "Claim no longer exists in Flan; its record and flag were removed.");
            return 1;
        }
        core.lifecycle().recordFor(claim);
        boolean retried = false;
        for (ConquestTxn txn : core.data().transactions()) {
            if (txn.claimId.equals(record.claimId) && txn.phase == ConquestTxn.Phase.NEEDS_ADMIN) {
                txn.phase = ConquestTxn.Phase.PENDING;
                retried = true;
            }
        }
        if (retried) {
            core.conquest().recoverPendingTransactions();
        }
        if (record.hasFlag) {
            ServerWorld world = FlanBridge.world(core.server(), record.dimension);
            if (world != null) {
                world.getChunk(record.flagPos());
                String problem = core.flags().restore(world, record, true);
                if (problem != null) {
                    reply(ctx, "Flag could not be restored: " + problem);
                }
            }
        }
        core.flags().rebuildIndex();
        core.data().markDirty();
        core.saveNow();
        audit(core, ctx, "repair " + record.claimId + " -> state " + record.state);
        reply(ctx, "Repair finished. State: " + record.state + (record.recoveryReason == null ? "" : " (" + record.recoveryReason + ")"));
        return 1;
    }

    private static int captureCancel(CommandContext<CommandSource> ctx) {
        WarCore core = core(ctx);
        ClaimRecord record = core == null ? null : record(ctx, core);
        if (record == null) {
            return 0;
        }
        boolean had = core.capture().cancelByAdmin(record.claimId);
        audit(core, ctx, "capture cancel " + record.claimId);
        reply(ctx, had ? "Capture cancelled." : "No capture was running on that claim.");
        return 1;
    }

    private static int rollback(CommandContext<CommandSource> ctx) {
        WarCore core = core(ctx);
        ClaimRecord record = core == null ? null : record(ctx, core);
        if (record == null) {
            return 0;
        }
        ConquestService.RollbackResult result = core.conquest().rollback(record.claimId, ctx.getSource().getName());
        if (!result.ok) {
            return fail(ctx, result.message);
        }
        reply(ctx, "Conquest rolled back. " + result.message);
        return 1;
    }
}
