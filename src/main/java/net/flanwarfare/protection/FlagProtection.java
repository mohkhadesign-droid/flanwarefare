package net.flanwarfare.protection;

import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.flag.FlagService;
import net.flanwarfare.flag.FlagService.Role;
import net.flanwarfare.util.Msg;
import net.minecraft.block.PistonBlockStructureHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IWorld;
import net.minecraft.world.World;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.event.world.PistonEvent;

/**
 * Server-side flag invulnerability through standard Forge events. Anything that bypasses these
 * events (some modded machines set blocks directly) is caught by the periodic restoration in
 * {@link FlagService#tick}, which also removes the dropped banner item to prevent duplication.
 */
public final class FlagProtection {

    private static FlagService flags() {
        WarCore core = WarCore.ready();
        return core == null ? null : core.flags();
    }

    private static boolean enabled() {
        return WarCore.ready() != null && WarConfig.PROTECT_FLAGS.get();
    }

    public void onBreak(BlockEvent.BreakEvent event) {
        FlagService flags = flags();
        if (!enabled() || !(event.getWorld() instanceof World)) {
            return;
        }
        if (flags.isProtectedFlagBlock((World) event.getWorld(), event.getPos())) {
            event.setCanceled(true);
            PlayerEntity player = event.getPlayer();
            if (player instanceof ServerPlayerEntity && !(player instanceof FakePlayer)) {
                Msg.actionBar((ServerPlayerEntity) player, Msg.error("Flags and the block under them cannot be broken. Capture the flag instead."));
            }
        }
    }

    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!enabled() || !(event.getWorld() instanceof World)) {
            return;
        }
        World world = (World) event.getWorld();
        boolean blocked = blocksPlacement(world, event.getPos());
        if (!blocked && event instanceof BlockEvent.EntityMultiPlaceEvent) {
            for (BlockSnapshot snapshot : ((BlockEvent.EntityMultiPlaceEvent) event).getReplacedBlockSnapshots()) {
                if (blocksPlacement(world, snapshot.getPos())) {
                    blocked = true;
                    break;
                }
            }
        }
        if (blocked) {
            event.setCanceled(true);
            Entity entity = event.getEntity();
            if (entity instanceof ServerPlayerEntity && !(entity instanceof FakePlayer)) {
                Msg.actionBar((ServerPlayerEntity) entity, Msg.error("The space around a flag must stay clear."));
            }
        }
    }

    private static boolean blocksPlacement(World world, BlockPos pos) {
        Role role = flags().roleAt(world, pos);
        return role == Role.FLAG || role == Role.POCKET;
    }

    public void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        if (!enabled() || !(event.getWorld() instanceof World)) {
            return;
        }
        Role role = flags().roleAt((World) event.getWorld(), event.getPos());
        if (role != Role.NONE) {
            event.setNewState(event.getOriginalState());
        }
    }

    public void onExplosion(ExplosionEvent.Detonate event) {
        if (!enabled()) {
            return;
        }
        World world = event.getWorld();
        FlagService flags = flags();
        event.getAffectedBlocks().removeIf(pos -> flags.isProtectedFlagBlock(world, pos));
    }

    public void onPiston(PistonEvent.Pre event) {
        if (!enabled()) {
            return;
        }
        IWorld iworld = event.getWorld();
        if (!(iworld instanceof World)) {
            return;
        }
        World world = (World) iworld;
        PistonBlockStructureHelper helper = event.getStructureHelper();
        if (helper == null || !helper.canMove()) {
            return;
        }
        Direction move = event.getPistonMoveType() == PistonEvent.PistonMoveType.EXTEND
                ? event.getDirection() : event.getDirection().getOpposite();
        FlagService flags = flags();
        for (BlockPos pos : helper.getBlocksToMove()) {
            Role from = flags.roleAt(world, pos);
            Role to = flags.roleAt(world, pos.offset(move));
            if (from == Role.FLAG || from == Role.SUPPORT || to == Role.FLAG || to == Role.POCKET) {
                event.setCanceled(true);
                return;
            }
        }
        for (BlockPos pos : helper.getBlocksToDestroy()) {
            if (flags.isProtectedFlagBlock(world, pos)) {
                event.setCanceled(true);
                return;
            }
        }
        BlockPos head = event.getFaceOffsetPos();
        if (event.getPistonMoveType() == PistonEvent.PistonMoveType.EXTEND && flags.roleAt(world, head) != Role.NONE
                && flags.roleAt(world, head) != Role.SUPPORT) {
            event.setCanceled(true);
        }
    }
}
