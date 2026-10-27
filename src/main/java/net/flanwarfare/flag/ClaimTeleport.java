package net.flanwarfare.flag;

import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.TeleportSpots;
import net.flanwarfare.util.Msg;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.server.ServerWorld;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Claim teleports land beside the flag, on the floor.
 *
 * <p>Flan's {@code /flan teleport} sends the player to the claim's home position and then
 * climbs the column's heightmap until the player fits. With the home at the flag (Flan Warfare
 * sets it there after a conquest), that put players on top of the flagpole or banner, and on
 * the roof when the flag is indoors.
 *
 * <p>Flan keeps its own permission check, the 5-second "don't move" delay and the movement
 * cancel. At the moment Flan would teleport, {@code PlayerClaimDataMixin} calls
 * {@link #teleportNow}. If the destination is in a claim with a flag in this dimension, this
 * class picks the destination instead: a spot about one block beside the flag's base, inside
 * the same claim, with solid ground, room for the player, and no liquid or fire. The spot is
 * checked at that moment, so changes made during the delay count. If no spot is safe, the
 * teleport is cancelled with a message. Claims without a flag keep Flan's normal behaviour.
 */
public final class ClaimTeleport {
    private static final Set<Block> UNSAFE = new HashSet<>(Arrays.asList(
            Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
            Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.NETHER_PORTAL, Blocks.END_PORTAL,
            Blocks.COBWEB));

    private ClaimTeleport() {
    }

    /**
     * Called by the mixin on the tick Flan would teleport to {@code target}. Returns true if
     * Flan Warfare handled it (teleported beside the flag, or cancelled); false to let Flan
     * teleport normally.
     */
    public static boolean teleportNow(ServerPlayerEntity player, BlockPos target) {
        WarCore core = WarCore.ready();
        if (core == null || target == null) {
            return false;
        }
        ServerWorld world = player.getServerWorld();
        Claim claim = FlanBridge.parentAt(world, target);
        if (claim == null) {
            return false;
        }
        ClaimRecord record = core.data().claim(claim.getClaimID());
        if (record == null || !record.hasFlag || record.flagPos() == null
                || !record.dimension.equals(FlanBridge.dimensionId(world))) {
            return false;
        }
        BlockPos spot = safeSpot(core, world, player, record, claim.getClaimID());
        if (spot == null) {
            Msg.send(player, Msg.error("Teleport cancelled: there is no safe spot to stand next to this claim's flag."));
            return true;
        }
        BlockPos base = record.flagPos();
        double x = spot.getX() + 0.5D;
        double z = spot.getZ() + 0.5D;
        float yaw = (float) (MathHelper.atan2(base.getZ() + 0.5D - z, base.getX() + 0.5D - x) * (180.0D / Math.PI)) - 90.0F;
        if (player.isPassenger()) {
            player.stopRiding();
        }
        player.fallDistance = 0.0F;
        player.teleport(world, x, spot.getY(), z, yaw, 0.0F);
        return true;
    }

    /** The first safe feet position beside the flag's base, or null. */
    static BlockPos safeSpot(WarCore core, ServerWorld world, ServerPlayerEntity player, ClaimRecord record, UUID claimId) {
        FlagStructure s = FlagStructure.of(record);
        Direction side = s.side;
        String dimension = record.dimension;
        for (int[] o : TeleportSpots.offsets(side.getXOffset(), side.getZOffset())) {
            for (int dy : TeleportSpots.LEVELS) {
                BlockPos feet = s.base.add(o[0], dy, o[1]);
                if (isSafe(core, world, player, feet, claimId, dimension)) {
                    return feet;
                }
            }
        }
        return null;
    }

    private static boolean isSafe(WarCore core, ServerWorld world, ServerPlayerEntity player, BlockPos feet, UUID claimId,
                                  String dimension) {
        if (!world.isBlockLoaded(feet)) {
            world.getChunk(feet); // load it (Flan does the same before teleporting)
        }
        Claim here = FlanBridge.parentAt(world, feet);
        if (here == null || !claimId.equals(here.getClaimID())) {
            return false; // stay inside the same claim
        }
        FlagService flags = core.flags();
        if (flags.claimOfFlagBlock(dimension, feet) != null || flags.claimOfFlagBlock(dimension, feet.up()) != null
                || flags.claimOfFlagBlock(dimension, feet.down()) != null) {
            return false; // never inside or on top of a flag
        }
        BlockPos floor = feet.down();
        BlockState ground = world.getBlockState(floor);
        if (!ground.isSolidSide(world, floor, Direction.UP) || UNSAFE.contains(ground.getBlock())) {
            return false;
        }
        if (UNSAFE.contains(world.getBlockState(feet).getBlock()) || UNSAFE.contains(world.getBlockState(feet.up()).getBlock())) {
            return false;
        }
        AxisAlignedBB box = new AxisAlignedBB(feet.getX() + 0.2D, feet.getY(), feet.getZ() + 0.2D,
                feet.getX() + 0.8D, feet.getY() + 1.8D, feet.getZ() + 0.8D);
        return world.hasNoCollisions(player, box) && !world.containsAnyLiquid(box);
    }
}
