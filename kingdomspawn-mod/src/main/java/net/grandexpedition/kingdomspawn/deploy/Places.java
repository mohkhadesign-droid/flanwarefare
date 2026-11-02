package net.grandexpedition.kingdomspawn.deploy;

import net.grandexpedition.kingdomspawn.WorldUtil;
import net.grandexpedition.kingdomspawn.config.KsConfig;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;

/** Configured places (hub, holding area): coordinates, or the dimension's world spawn if unset. */
public final class Places {
    private Places() {
    }

    public static ServerWorld world(ServerPlayerEntity player, KsConfig.Place place) {
        return WorldUtil.world(player.getServer(), place.dimension);
    }

    /** The exact position of a place (world spawn surface if no coordinates are set). */
    public static Vector3d position(ServerWorld world, KsConfig.Place place) {
        if (place.x != null && place.y != null && place.z != null) {
            return new Vector3d(place.x, place.y, place.z);
        }
        BlockPos spawn = world.getSpawnPoint();
        BlockPos top = WorldUtil.surface(world, spawn.getX(), spawn.getZ());
        return new Vector3d(top.getX() + 0.5D, top.getY(), top.getZ() + 0.5D);
    }

    public static boolean teleport(ServerPlayerEntity player, KsConfig.Place place) {
        ServerWorld world = world(player, place);
        if (world == null) {
            return false;
        }
        Vector3d p = position(world, place);
        world.getChunk(new BlockPos(p.x, p.y, p.z));
        if (player.isPassenger()) {
            player.stopRiding();
        }
        player.fallDistance = 0.0F;
        player.teleport(world, p.x, p.y, p.z, place.yaw, place.pitch);
        return true;
    }

    public static void set(KsConfig.Place place, ServerPlayerEntity player) {
        place.dimension = WorldUtil.dimensionId(player.world);
        place.x = Math.floor(player.getPosX() * 10) / 10;
        place.y = Math.floor(player.getPosY() * 10) / 10;
        place.z = Math.floor(player.getPosZ() * 10) / 10;
        place.yaw = player.rotationYaw;
        place.pitch = player.rotationPitch;
    }
}
