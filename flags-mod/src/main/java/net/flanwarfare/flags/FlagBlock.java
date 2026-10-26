package net.flanwarfare.flags;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.material.MaterialColor;
import net.minecraft.state.DirectionProperty;
import net.minecraft.state.IntegerProperty;
import net.minecraft.state.StateContainer;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.world.IBlockReader;

/**
 * One block of a claim flag. PART 0 is the base (pedestal, tile entity, renders the whole
 * model); PART 1..5 are the upper pole column (nothing rendered, a thin pole hitbox).
 *
 * <p>Collision is only the pedestal and the 2-pixel pole, so the tall model never blocks
 * movement beside it; the banner and crossbar have no collision. No item, no drops; Flan Warfare
 * places and removes these blocks itself.
 */
public final class FlagBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 5);

    private static final VoxelShape POLE = Block.makeCuboidShape(7, 0, 7, 9, 16, 9);
    private static final VoxelShape BASE = VoxelShapes.or(
            Block.makeCuboidShape(0, 0, 0, 16, 2, 16),
            Block.makeCuboidShape(4, 2, 4, 12, 6, 12),
            Block.makeCuboidShape(7, 6, 7, 9, 16, 9));

    public FlagBlock() {
        super(Block.Properties.create(Material.WOOD, MaterialColor.BROWN)
                .hardnessAndResistance(-1.0F, 3600000.0F)
                .sound(SoundType.WOOD)
                .notSolid()
                .noDrops());
        setDefaultState(getStateContainer().getBaseState().with(FACING, Direction.SOUTH).with(PART, 0));
    }

    @Override
    protected void fillStateContainer(StateContainer.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    public VoxelShape getShape(BlockState state, IBlockReader world, BlockPos pos, ISelectionContext context) {
        return state.get(PART) == 0 ? BASE : POLE;
    }

    /** The model is drawn by the tile entity renderer of the base block only. */
    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, IBlockReader reader, BlockPos pos) {
        return true;
    }

    @Override
    public boolean hasTileEntity(BlockState state) {
        return state.get(PART) == 0;
    }

    @Override
    public TileEntity createTileEntity(BlockState state, IBlockReader world) {
        return state.get(PART) == 0 ? new FlagTileEntity() : null;
    }
}
