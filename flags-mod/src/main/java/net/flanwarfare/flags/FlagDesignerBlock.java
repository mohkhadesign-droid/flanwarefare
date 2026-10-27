package net.flanwarfare.flags;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.material.MaterialColor;
import net.minecraft.block.material.PushReaction;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;

/**
 * The Flag Designer station: a decorative block that opens the Flag Designer for the claim flag
 * it is linked to.
 *
 * <ul>
 *   <li>Obtainable only from an admin: {@code /flanwarfare designer give} (or {@code /give}).
 *   It has no recipe, no creative-tab entry and no drops, and it cannot be mined in survival.</li>
 *   <li>An admin links it to a claim flag: sneak-right-click the station, then right-click the
 *   flag. The link is stored by Flan Warfare (server), not in this block.</li>
 *   <li>Right-click: Flan Warfare intercepts the click on the server, checks who may edit, and
 *   opens the designer menu. This block itself only swings the arm. If Flan Warfare is
 *   missing, it says so.</li>
 * </ul>
 */
public final class FlagDesignerBlock extends Block {
    public FlagDesignerBlock() {
        super(Block.Properties.create(Material.WOOD, MaterialColor.BROWN)
                .hardnessAndResistance(-1.0F, 3600000.0F)
                .sound(SoundType.WOOD)
                .noDrops());
    }

    /** Pistons cannot move it (that would orphan its link). */
    @Override
    @SuppressWarnings("deprecation")
    public PushReaction getPushReaction(BlockState state) {
        return PushReaction.BLOCK;
    }

    @Override
    @SuppressWarnings("deprecation")
    public ActionResultType onBlockActivated(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand,
                                             BlockRayTraceResult hit) {
        if (!world.isRemote && hand == Hand.MAIN_HAND) {
            // Only reached when Flan Warfare did not handle the click (it is missing or disabled).
            player.sendStatusMessage(new StringTextComponent("This Flag Designer needs Flan Warfare on the server."), true);
        }
        return ActionResultType.SUCCESS;
    }
}
