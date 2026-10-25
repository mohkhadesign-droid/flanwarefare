package net.flanwarfare.flan;

import com.mojang.authlib.GameProfile;
import io.github.flemmli97.flan.api.permission.ClaimPermission;
import io.github.flemmli97.flan.api.permission.PermissionRegistry;
import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.claim.ClaimStorage;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.mixin.ClaimAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import java.util.Map;
import java.util.UUID;

/** Thin, read-mostly helpers around Flan's verified public API. */
public final class FlanBridge {
    private FlanBridge() {
    }

    public static String dimensionId(World world) {
        return world.getDimensionKey().getLocation().toString();
    }

    public static ServerWorld world(MinecraftServer server, String dimensionId) {
        for (ServerWorld world : server.getWorlds()) {
            if (dimensionId(world).equals(dimensionId)) {
                return world;
            }
        }
        return null;
    }

    /** Flan subclaims are one level deep; returns the parent claim (or the claim itself). */
    public static Claim top(Claim claim) {
        if (claim == null) {
            return null;
        }
        Claim parent = claim.parentClaim();
        return parent != null ? parent : claim;
    }

    /** Parent claim at a position, or null in the wilderness. */
    public static Claim parentAt(ServerWorld world, BlockPos pos) {
        return top(ClaimStorage.get(world).getClaimAt(pos));
    }

    /** Looks up the live Flan claim for a record; null if it no longer exists. */
    public static Claim find(MinecraftServer server, ClaimRecord record) {
        ServerWorld world = world(server, record.dimension);
        if (world == null) {
            return null;
        }
        Claim claim = ClaimStorage.get(world).getFromUUID(record.claimId);
        if (claim == null || claim.isRemoved()) {
            return null;
        }
        return claim;
    }

    public static Map<UUID, String> members(Claim claim) {
        return ((ClaimAccessor) (Object) claim).flanwarfare$getPlayersGroups();
    }

    public static boolean isMember(Claim claim, UUID player) {
        return members(claim).containsKey(player);
    }

    /** True if the player's Flan group on this claim grants claim management. */
    public static boolean isManager(Claim claim, UUID player) {
        String group = members(claim).get(player);
        if (group == null) {
            return false;
        }
        return claim.groupHasPerm(group, PermissionRegistry.EDITCLAIM) == 1
                || claim.groupHasPerm(group, PermissionRegistry.EDITPERMS) == 1;
    }

    public static boolean isManagementPermission(ClaimPermission permission) {
        return permission == PermissionRegistry.EDITCLAIM
                || permission == PermissionRegistry.EDITPERMS
                || permission == PermissionRegistry.EDITPOTIONS;
    }

    /** Readable label: the claim's Flan name, or its centre coordinates. */
    public static String label(Claim claim) {
        String name = claim.getClaimName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        int[] d = claim.getDimensions();
        return "claim at " + ((d[0] + d[1]) / 2) + ", " + ((d[2] + d[3]) / 2);
    }

    public static String playerName(MinecraftServer server, UUID uuid) {
        if (uuid == null) {
            return "nobody";
        }
        GameProfile profile = server.getPlayerProfileCache().getProfileByUUID(uuid);
        if (profile != null && profile.getName() != null) {
            return profile.getName();
        }
        return uuid.toString().substring(0, 8);
    }
}
