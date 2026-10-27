package net.flanwarfare.designer;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.material.MaterialColor;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.ToolType;

/**
 * The Flag Designer: a craftable workstation. Place it inside your claim and right-click it to
 * design that claim's flag. Mined with an axe like a loom; drops itself.
 *
 * <p>Flan Warfare (server) handles the right-click: it finds the claim at this block, checks who
 * may edit and opens the designer. This method only runs when Flan Warfare did not handle the
 * click (it is missing or the designer is disabled).
 */
public final class FlagDesignerBlock extends Block {
    public FlagDesignerBlock() {
        super(Block.Properties.create(Material.WOOD, MaterialColor.BROWN)
                .hardnessAndResistance(2.5F)
                .harvestTool(ToolType.AXE)
                .sound(SoundType.WOOD));
    }

    @Override
    @SuppressWarnings("deprecation")
    public ActionResultType onBlockActivated(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand,
                                             BlockRayTraceResult hit) {
        if (!world.isRemote && hand == Hand.MAIN_HAND) {
            player.sendStatusMessage(new StringTextComponent("The Flag Designer needs Flan Warfare on the server."), true);
        }
        return ActionResultType.SUCCESS;
    }
}
