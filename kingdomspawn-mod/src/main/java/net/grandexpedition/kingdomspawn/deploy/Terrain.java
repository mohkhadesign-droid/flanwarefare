package net.grandexpedition.kingdomspawn.deploy;

import net.grandexpedition.kingdomspawn.WorldUtil;
import net.grandexpedition.kingdomspawn.logic.Slots;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.ChunkGenerator;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;

import java.util.List;

/**
 * Terrain checks for deployment points.
 *
 * <ul>
 *   <li>{@link #noiseFlat}: a cheap pre-check from the world generator's height estimate (no
 *   chunk is generated): above sea level and not steep.</li>
 *   <li>{@link #findCenter}: the real check on generated chunks: a safe centre in the biome
 *   with enough safe arrival spots 5-10 blocks around it, all at about the same height.</li>
 * </ul>
 */
public final class Terrain {
    private Terrain() {
    }

    /** True if the generator's height estimate around x,z is above sea level and fairly flat. */
    public static boolean noiseFlat(ServerWorld world, int x, int z, int maxSlope) {
        ChunkGenerator gen = world.getChunkProvider().getChunkGenerator();
        int sea = gen.getSeaLevel();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -8; dx <= 8; dx += 4) {
            for (int dz = -8; dz <= 8; dz += 4) {
                int h = gen.getHeight(x + dx, z + dz, Heightmap.Type.WORLD_SURFACE_WG);
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        return min > sea && max - min <= maxSlope + 2;
    }

    /** Chunk radius (blocks) that {@link #findCenter} reads around x,z. */
    public static int readRadius(int radiusMax) {
        return 8 + radiusMax + 1;
    }

    /**
     * A safe deployment centre near x,z (within 8 blocks), in the biome, with at least
     * {@code minSlots} safe arrival spots between rMin and rMax blocks away, each within
     * {@code maxSlope} blocks of the centre's height. Chunks must already be loaded. Null if none.
     */
    public static BlockPos findCenter(ServerWorld world, int x, int z, ResourceLocation biome, int rMin, int rMax,
                                      int maxSlope, int minSlots) {
        List<int[]> slots = Slots.offsets(rMin, rMax);
        for (int r = 0; r <= 8; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    BlockPos c = WorldUtil.surface(world, x + dx, z + dz);
                    if (!WorldUtil.safeStand(world, c) || !inBiome(world, c, biome)) {
                        continue;
                    }
                    int ok = 0;
                    for (int[] o : slots) {
                        BlockPos p = WorldUtil.surface(world, c.getX() + o[0], c.getZ() + o[1]);
                        if (Math.abs(p.getY() - c.getY()) <= maxSlope && WorldUtil.safeStand(world, p)) {
                            ok++;
                            if (ok >= minSlots) {
                                return c;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    public static boolean inBiome(ServerWorld world, BlockPos pos, ResourceLocation biome) {
        ResourceLocation here = WorldUtil.biomeId(world, world.getBiome(pos));
        return biome.equals(here);
    }
}
