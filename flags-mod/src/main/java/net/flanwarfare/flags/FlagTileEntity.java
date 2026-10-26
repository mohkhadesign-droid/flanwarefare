package net.flanwarfare.flags;

import com.mojang.datafixers.util.Pair;
import net.minecraft.block.BlockState;
import net.minecraft.item.DyeColor;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SUpdateTileEntityPacket;
import net.minecraft.tileentity.BannerPattern;
import net.minecraft.tileentity.BannerTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Flag data, written by Flan Warfare and synced to clients:
 * <ul>
 *   <li>{@code Base}: banner base colour id (DyeColor), {@code Patterns}: the vanilla banner
 *   pattern list (same format as a banner's BlockEntityTag), so every design from a loom or
 *   {@code /flag design} works unchanged;</li>
 *   <li>{@code Raise}: 0 (banner at the bottom of the pole) .. 1 (fully raised), driven by the
 *   capture animation.</li>
 * </ul>
 */
public final class FlagTileEntity extends TileEntity {
    private DyeColor base = DyeColor.WHITE;
    private ListNBT patterns = new ListNBT();
    private float raise = 1.0F;

    // Client-side rendering state (never saved).
    private List<Pair<BannerPattern, DyeColor>> patternCache;
    private float shownRaise = -1.0F;
    private long lastNanos;

    public FlagTileEntity() {
        super(FlagsMod.FLAG_TILE.get());
    }

    public DyeColor getBase() {
        return base;
    }

    public float getRaise() {
        return raise;
    }

    /** Pattern layers for the vanilla banner renderer (computed once per change). */
    public List<Pair<BannerPattern, DyeColor>> patternLayers() {
        if (patternCache == null) {
            patternCache = BannerTileEntity.getPatternColorData(base, patterns);
        }
        return patternCache;
    }

    /**
     * Client: the raise height actually drawn. It follows {@link #raise} smoothly (about 0.4
     * of the travel per second), so the stepped capture progress from the server looks like a
     * continuous movement.
     */
    @OnlyIn(Dist.CLIENT)
    public float animatedRaise() {
        long now = System.nanoTime();
        float deltaTicks = lastNanos == 0L ? 0.0F : (now - lastNanos) / 50_000_000.0F;
        lastNanos = now;
        if (shownRaise < 0) {
            shownRaise = raise;
            return shownRaise;
        }
        float step = 0.02F * Math.max(0.0F, Math.min(5.0F, deltaTicks));
        if (Math.abs(raise - shownRaise) <= step) {
            shownRaise = raise;
        } else {
            shownRaise += raise > shownRaise ? step : -step;
        }
        return shownRaise;
    }

    @Override
    public void read(BlockState state, CompoundNBT nbt) {
        super.read(state, nbt);
        base = DyeColor.byId(nbt.getInt("Base"));
        patterns = nbt.contains("Patterns", 9) ? nbt.getList("Patterns", 10) : new ListNBT();
        raise = nbt.contains("Raise") ? Math.max(0.0F, Math.min(1.0F, nbt.getFloat("Raise"))) : 1.0F;
        patternCache = null;
    }

    @Override
    public CompoundNBT write(CompoundNBT nbt) {
        super.write(nbt);
        nbt.putInt("Base", base.getId());
        nbt.put("Patterns", patterns.copy());
        nbt.putFloat("Raise", raise);
        return nbt;
    }

    @Override
    public CompoundNBT getUpdateTag() {
        return write(new CompoundNBT());
    }

    @Override
    public SUpdateTileEntityPacket getUpdatePacket() {
        return new SUpdateTileEntityPacket(getPos(), 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SUpdateTileEntityPacket pkt) {
        read(getBlockState(), pkt.getNbtCompound());
    }

    /** The model is about 6.2 blocks tall and 1.3 wide: never cull it by the base block alone. */
    @Override
    @OnlyIn(Dist.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(pos.add(-1, -1, -1), pos.add(2, 8, 2));
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public double getMaxRenderDistanceSquared() {
        return 160.0D * 160.0D;
    }
}
