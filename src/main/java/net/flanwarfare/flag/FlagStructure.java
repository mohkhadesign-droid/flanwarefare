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

    public boolean isPole() {
        return height > 0;
    }

    /** Fully raised level (1..height for poles, 0 for a legacy banner). */
    public int topLevel() {
        return height;
    }

    public List<BlockPos> poleBlocks() {
        List<BlockPos> list = new ArrayList<>();
        for (int y = 0; isPole() && y <= height; y++) {
            list.add(base.up(y));
        }
        return list;
    }

    /** Where the banner is at a level (legacy: the base). */
    public BlockPos bannerAt(int level) {
        return isPole() ? base.offset(side).up(Math.max(1, Math.min(height, level))) : base;
    }

    /** Every position the banner can occupy. */
    public List<BlockPos> bannerSlots() {
        List<BlockPos> list = new ArrayList<>();
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
