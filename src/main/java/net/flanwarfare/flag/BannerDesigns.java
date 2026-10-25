package net.flanwarfare.flag;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.flanwarfare.FlanWarfare;
import net.flanwarfare.config.WarConfig;
import net.minecraft.block.BannerBlock;
import net.minecraft.block.BlockState;
import net.minecraft.item.BannerItem;
import net.minecraft.item.DyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.tileentity.BannerTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

/**
 * Vanilla banner designs, stored as the SNBT of a one-item banner stack (base colour comes
 * from the item, the pattern layers from its {@code BlockEntityTag.Patterns}). Copying a design
 * never consumes the player's banner.
 */
public final class BannerDesigns {
    private BannerDesigns() {
    }

    public static boolean isBanner(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BannerItem;
    }

    public static String toSnbt(ItemStack banner) {
        ItemStack copy = banner.copy();
        copy.setCount(1);
        return copy.write(new CompoundNBT()).toString();
    }

    /** Parses a stored design; falls back to the default design if it is unreadable. */
    public static ItemStack fromSnbt(String snbt) {
        if (snbt != null) {
            try {
                ItemStack stack = ItemStack.read(JsonToNBT.getTagFromJson(snbt));
                if (isBanner(stack)) {
                    return stack;
                }
            } catch (CommandSyntaxException e) {
                FlanWarfare.LOG.warn("Unreadable stored banner design, using default: {}", e.getMessage());
            }
        }
        return defaultBanner();
    }

    public static ItemStack defaultBanner() {
        DyeColor color = DyeColor.byTranslationKey(WarConfig.DEFAULT_BANNER_COLOR.get(), DyeColor.WHITE);
        return new ItemStack(BannerBlock.forColor(color));
    }

    public static String defaultSnbt() {
        return toSnbt(defaultBanner());
    }

    public static DyeColor color(ItemStack banner) {
        return ((BannerItem) banner.getItem()).getColor();
    }

    public static BlockState standingState(ItemStack banner, int rotation) {
        return BannerBlock.forColor(color(banner)).getDefaultState()
                .with(BlockStateProperties.ROTATION_0_15, rotation & 15);
    }

    /** Places (or replaces) the standing banner and writes its pattern layers. */
    public static void place(ServerWorld world, BlockPos pos, ItemStack banner, int rotation) {
        BlockState state = standingState(banner, rotation);
        world.setBlockState(pos, state, 3);
        writePatterns(world, pos, banner);
    }

    /** Copies the pattern layers of {@code banner} into the banner tile entity at {@code pos}. */
    public static void writePatterns(ServerWorld world, BlockPos pos, ItemStack banner) {
        TileEntity tile = world.getTileEntity(pos);
        if (!(tile instanceof BannerTileEntity)) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        CompoundNBT data = tile.write(new CompoundNBT());
        CompoundNBT source = banner.getChildTag("BlockEntityTag");
        if (source != null && source.contains("Patterns", 9)) {
            data.put("Patterns", source.getList("Patterns", 10).copy());
        } else {
            data.remove("Patterns");
        }
        data.remove("CustomName");
        tile.read(state, data);
        tile.markDirty();
        world.notifyBlockUpdate(pos, state, state, 3);
    }
}
