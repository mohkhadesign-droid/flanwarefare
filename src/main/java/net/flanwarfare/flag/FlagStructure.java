package net.flanwarfare.flag;

import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.data.OrphanFlag;
import net.minecraft.block.AbstractBannerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Block geometry of one registered flag, derived from its record. The base B is always the
 * authoritative position (and the capture-zone centre).
 *
 * <p><b>Flagpole</b> ({@code height > 0}): pole blocks at B .. B+height; the banner is a vanilla
 * wall banner hanging on side S of the pole at one of the levels 1..height (B.offset(S).up(level),
 * attached to the pole block at the same height). Level {@code height} is fully raised. Wall
 * banners only exist at whole-block heights in 1.16.5, so moving the banner is a stepped
 * animation of one block per step.
 *
 * <p><b>Legacy</b> ({@code height == 0}): a single standing banner at B.
 *
 * <p><b>Model</b> (pole block {@code flanwarfareflags:flag}, needs the Flan Warfare Flags mod on
 * server and clients): B is the base block (PART 0, a tile entity holding the banner design and
 * its raise height, drawing the whole 6.2-block model), B+1..B+5 are the invisible upper pole
 * (PART 1..5). There are no banner blocks; the banner is part of the model and slides smoothly
 * along the pole during captures ({@link #MODEL_LEVELS} animation steps).
 */
public final class FlagStructure {
    public final BlockPos base;
    public final int height;
    public final Direction side;
    public final String poleBlockId;

    private FlagStructure(BlockPos base, int height, Direction side, String poleBlockId) {
        this.base = base;
        this.height = height;
        this.side = side;
        this.poleBlockId = poleBlockId;
    }

    public static FlagStructure of(ClaimRecord record) {
        return new FlagStructure(record.flagPos(), record.poleHeight, sideOf(record.poleSide), record.poleBlock);
    }

    public static FlagStructure of(OrphanFlag orphan) {
        return new FlagStructure(new BlockPos(orphan.x, orphan.y, orphan.z), orphan.poleHeight, sideOf(orphan.poleSide),
                orphan.poleBlock);
    }

    public static FlagStructure planned(BlockPos base, int height, Direction side, String poleBlockId) {
        return new FlagStructure(base, height, side, poleBlockId);
    }

    private static Direction sideOf(int index) {
        Direction d = Direction.byIndex(index);
        return d.getAxis().isHorizontal() ? d : Direction.NORTH;
    }

    public static final String MODEL_BLOCK = "flanwarfareflags:flag";
    /** Column blocks above the base of a model flag (B+1..B+5). */
    public static final int MODEL_HEIGHT = 5;
    /** Capture animation steps of a model flag (the client smooths between them). */
    public static final int MODEL_LEVELS = 24;

    public boolean isPole() {
        return height > 0;
    }

    /** True for the Flan Warfare Flags model (banner inside the base tile entity). */
    public boolean isModel() {
        return MODEL_BLOCK.equals(poleBlockId);
    }

    /** True if the Flan Warfare Flags mod is installed (its block is registered). */
    public static boolean modelAvailable() {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(MODEL_BLOCK));
        return block != null && block != Blocks.AIR;
    }

    /** Fully raised level (1..levels for poles, 0 for a legacy banner). */
    public int topLevel() {
        return isModel() ? MODEL_LEVELS : height;
    }

    /** Number of banner positions the capture animation uses. */
    public int animationLevels() {
        return isModel() ? MODEL_LEVELS : height;
    }

    /** Raise height 0..1 of the model banner at an animation level. */
    public float raiseAt(int level) {
        int top = animationLevels();
        if (top <= 1) {
            return 1.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, (level - 1) / (float) (top - 1)));
    }

    /** Block state of a column position (model: PART and FACING; otherwise the pole block). */
    public BlockState poleStateAt(BlockPos pos) {
        Block block = poleBlock();
        BlockState state = block.getDefaultState();
        if (!isModel()) {
            return state;
        }
        net.minecraft.state.Property<?> part = block.getStateContainer().getProperty("part");
        net.minecraft.state.Property<?> facing = block.getStateContainer().getProperty("facing");
        if (part instanceof net.minecraft.state.IntegerProperty) {
            int index = Math.max(0, Math.min(MODEL_HEIGHT, pos.getY() - base.getY()));
            state = state.with((net.minecraft.state.IntegerProperty) part, index);
        }
        if (facing instanceof net.minecraft.state.DirectionProperty) {
            state = state.with((net.minecraft.state.DirectionProperty) facing, side);
        }
        return state;
    }

    public List<BlockPos> poleBlocks() {
        List<BlockPos> list = new ArrayList<>();
        for (int y = 0; isPole() && y <= height; y++) {
            list.add(base.up(y));
        }
        return list;
    }

    /** Where the banner is at a level (legacy and model: the base). */
    public BlockPos bannerAt(int level) {
        return isPole() && !isModel() ? base.offset(side).up(Math.max(1, Math.min(height, level))) : base;
    }

    /** Every position the banner can occupy. */
    public List<BlockPos> bannerSlots() {
        List<BlockPos> list = new ArrayList<>();
        if (isModel()) {
            return list; // the banner is drawn by the base block
        }
        if (!isPole()) {
            list.add(base);
            return list;
        }
        for (int level = 1; level <= height; level++) {
            list.add(bannerAt(level));
        }
        return list;
    }

    /** Pole blocks plus banner slots: everything that belongs to the flag. */
    public List<BlockPos> allBlocks() {
        List<BlockPos> list = new ArrayList<>(poleBlocks());
        for (BlockPos pos : bannerSlots()) {
            if (!list.contains(pos)) {
                list.add(pos);
            }
        }
        return list;
    }

    public BlockPos support() {
        return base.down();
    }

    /** Where to put a player standing at the base after the pole is built (opposite the banner). */
    public BlockPos standingSpot() {
        return base.offset(side.getOpposite());
    }

    public Block poleBlock() {
        return resolvePoleBlock(poleBlockId);
    }

    /** The configured pole block, or dark oak fence if the id is unknown or unsuitable. */
    public static Block resolvePoleBlock(String id) {
        if (MODEL_BLOCK.equals(id) && modelAvailable()) {
            return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(MODEL_BLOCK));
        }
        ResourceLocation location = id == null ? null : ResourceLocation.tryCreate(id);
        Block block = location == null ? null : ForgeRegistries.BLOCKS.getValue(location);
        if (block == null || block == Blocks.AIR || !block.getDefaultState().getMaterial().isSolid()
                || block instanceof AbstractBannerBlock) {
            return Blocks.DARK_OAK_FENCE;
        }
        return block;
    }

    public boolean isPoleBlock(BlockState state) {
        return state.getBlock() == poleBlock();
    }

    public static boolean isBanner(BlockState state) {
        return state.getBlock() instanceof AbstractBannerBlock;
    }
}
