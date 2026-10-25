package net.flanwarfare.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.data.ClaimState;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.util.Msg;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.player.ServerPlayerEntity;

/**
 * Player commands:
 * <pre>
 * /flag place                 place the flag of the claim you stand in (activates it)
 * /flag move                  move your claim's flag to where you stand (never during raids)
 * /flag info                  activation, flag and conquest information for this claim
 * /flag abandon [confirm]     delete a claim that never received a flag
 * /flag design set|apply|reset  save the banner in your hand / show it / reset to default
 * </pre>
 */
public final class FlagCommand {
    private FlagCommand() {
    }

    interface PlayerAction {
        int run(WarCore core, ServerPlayerEntity player) throws CommandSyntaxException;
    }

    static int withPlayer(CommandContext<CommandSource> ctx, PlayerAction action) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().asPlayer();
        WarCore core = WarCore.ready();
        if (core == null) {
            ctx.getSource().sendErrorMessage(Msg.error("Flan Warfare is not active (starting up or in safe mode)."));
            return 0;
        }
        return action.run(core, player);
    }

    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        dispatcher.register(Commands.literal("flag")
                .then(Commands.literal("place").executes(ctx -> withPlayer(ctx, (core, p) -> core.flags().place(p))))
                .then(Commands.literal("move").executes(ctx -> withPlayer(ctx, (core, p) -> core.flags().move(p))))
                .then(Commands.literal("info").executes(ctx -> withPlayer(ctx, FlagCommand::info)))
                .then(Commands.literal("abandon")
                        .executes(ctx -> withPlayer(ctx, (core, p) -> abandon(core, p, false)))
                        .then(Commands.literal("confirm").executes(ctx -> withPlayer(ctx, (core, p) -> abandon(core, p, true)))))
                .then(Commands.literal("design")
                        .then(Commands.literal("set").executes(ctx -> withPlayer(ctx, (core, p) -> core.flags().saveDesign(p))))
                        .then(Commands.literal("apply").executes(ctx -> withPlayer(ctx, (core, p) -> core.flags().applyDesign(p))))
                        .then(Commands.literal("reset").executes(ctx -> withPlayer(ctx, (core, p) -> core.flags().resetDesign(p))))));
    }

    private static int info(WarCore core, ServerPlayerEntity player) {
        Claim claim = FlanBridge.parentAt(player.getServerWorld(), player.getPosition());
        if (claim == null) {
            Msg.send(player, Msg.info("You are not inside a claim. " + core.raid().statusLine()));
            return 1;
        }
        if (claim.isAdminClaim()) {
            Msg.send(player, Msg.info("Admin claim: permanently protected, never raidable or capturable."));
            return 1;
        }
        if (core.isExcluded(claim)) {
            Msg.send(player, Msg.info("This claim is excluded from warfare (ordinary Flan protection)."));
            return 1;
        }
        ClaimRecord record = core.lifecycle().recordFor(claim);
        long now = System.currentTimeMillis();
        Msg.send(player, Msg.info("Claim '" + FlanBridge.label(claim) + "' owned by "
                + FlanBridge.playerName(core.server(), claim.getOwner())));
        String state;
        if (record.state == ClaimState.PENDING_FLAG) {
            state = "INACTIVE (no flag): no protection, no management.";
            if (now < record.graceEndsAt) {
                state += " Reminder period ends in " + Msg.duration(record.graceEndsAt - now) + ".";
            }
            if (record.expiresAt > 0) {
                state += now < record.expiresAt
                        ? " Expires in " + Msg.duration(record.expiresAt - now) + "."
                        : " Expired; awaiting administrator cleanup.";
            }
        } else if (record.state == ClaimState.RECOVERY_REQUIRED) {
            state = "RECOVERY REQUIRED: flag needs an administrator (still raidable and capturable).";
        } else {
            state = "ACTIVE (" + core.warState(record) + ").";
        }
        Msg.send(player, Msg.info("State: " + state));
        if (record.hasFlag) {
            Msg.send(player, Msg.info("Flag at " + record.flagX + ", " + record.flagY + ", " + record.flagZ));
        }
        if (record.conquered) {
            Msg.send(player, Msg.info("Conquered territory (free of claim blocks and slots; cannot be resized or transferred)."));
        }
        long immunity = record.immunityRemaining(now);
        Msg.send(player, Msg.info(immunity > 0
                ? "Recapture immunity: " + Msg.duration(immunity) + " left (it can still be raided, not captured)."
                : "Recapture immunity: none."));
        Msg.send(player, Msg.info(core.raid().statusLine()));
        return 1;
    }

    private static int abandon(WarCore core, ServerPlayerEntity player, boolean confirmed) {
        Claim claim = FlanBridge.parentAt(player.getServerWorld(), player.getPosition());
        if (claim == null || claim.isAdminClaim() || !claim.getOwner().equals(player.getUniqueID())) {
            Msg.send(player, Msg.error("Stand inside one of your own claims."));
            return 0;
        }
        ClaimRecord record = core.lifecycle().recordFor(claim);
        if (record.state != ClaimState.PENDING_FLAG || record.flagEverPlaced) {
            Msg.send(player, Msg.error("Only claims that never received a flag can be abandoned this way. "
                    + "Active claims are managed with Flan's own commands."));
            return 0;
        }
        if (!confirmed) {
            Msg.send(player, Msg.warn("This deletes the claim (the land and blocks stay). Run /flag abandon confirm."));
            return 1;
        }
        if (!core.lifecycle().deleteThroughFlan(claim)) {
            Msg.send(player, Msg.error("Flan refused to delete the claim."));
            return 0;
        }
        core.data().claims().remove(record.claimId);
        core.data().markDirty();
        core.saveNow();
        core.audit("Owner {} abandoned inactive claim {}", player.getGameProfile().getName(), record.claimId);
        Msg.send(player, Msg.ok("Inactive claim abandoned."));
        return 1;
    }
}
