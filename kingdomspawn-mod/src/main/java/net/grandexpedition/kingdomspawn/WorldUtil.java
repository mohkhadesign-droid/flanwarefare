package net.grandexpedition.kingdomspawn;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Direction;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.MutableRegistry;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** World access helpers. Everything here runs on the server thread. */
public final class WorldUtil {
    private static final Set<net.minecraft.block.Block> UNSAFE = new HashSet<>(Arrays.asList(
            Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
            Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.COBWEB));

    private WorldUtil() {
    }

    public static ServerWorld world(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryCreate(dimension == null ? "" : dimension);
        if (id == null || server == null) {
            return null;
        }
        return server.getWorld(RegistryKey.getOrCreateKey(Registry.WORLD_KEY, id));
    }

    public static String dimensionId(World world) {
        return world.getDimensionKey().getLocation().toString();
    }

    public static MutableRegistry<Biome> biomes(MinecraftServer server) {
        return server.getWorld(World.OVERWORLD).func_241828_r().getRegistry(Registry.BIOME_KEY);
    }

    public static boolean biomeExists(MinecraftServer server, ResourceLocation id) {
        return biomes(server).containsKey(id);
    }

    public static ResourceLocation biomeId(ServerWorld world, Biome biome) {
        return world.func_241828_r().getRegistry(Registry.BIOME_KEY).getKey(biome);
    }

    /** Biome at a block position from the world generator (no chunk is loaded or generated). */
    public static Biome noiseBiome(ServerWorld world, int x, int y, int z) {
        return world.getNoiseBiomeRaw(x >> 2, y >> 2, z >> 2);
    }

    /** Feet position on the surface at x,z (top of the highest motion-blocking block, leaves ignored). */
    public static BlockPos surface(ServerWorld world, int x, int z) {
        return new BlockPos(x, world.getHeight(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    private static boolean unsafe(BlockState s) {
        return UNSAFE.contains(s.getBlock());
    }

    /** Solid ground below, two free blocks for the player, no liquid, fire or hazard. */
    public static boolean safeStand(ServerWorld world, BlockPos feet) {
        if (feet.getY() < 1 || feet.getY() > world.getHeight() - 2) {
            return false;
        }
        BlockPos below = feet.down();
        BlockState ground = world.getBlockState(below);
        if (!ground.isSolidSide(world, below, Direction.UP) || ground.isIn(BlockTags.LEAVES)
                || !ground.getFluidState().isEmpty() || unsafe(ground)) {
            return false;
        }
        for (BlockPos p : new BlockPos[]{feet, feet.up()}) {
            BlockState s = world.getBlockState(p);
            if (!s.getCollisionShape(world, p).isEmpty() || !s.getFluidState().isEmpty() || unsafe(s)) {
                return false;
            }
        }
        return true;
    }
}
