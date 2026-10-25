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

    /** Member of the parent claim or of any of its subclaims (live Flan data). */
    public static boolean isMemberAnywhere(Claim parent, UUID player) {
        if (isMember(parent, player)) {
            return true;
        }
        for (Claim sub : parent.getAllSubclaims()) {
            if (isMember(sub, player)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Authorized by the owner at this position: the owner, a member of the parent claim, or a
     * member of the subclaim containing the position. Read live from Flan every time, so adding
     * or removing someone takes effect immediately.
     */
    public static boolean isAuthorizedAt(Claim parent, BlockPos pos, UUID player) {
        if (player.equals(parent.getOwner()) || isMember(parent, player)) {
            return true;
        }
        Claim sub = parent.getSubClaim(pos);
        return sub != null && isMember(sub, player);
    }

    /**
     * Effective permissions of a player in one claim object (parent or subclaim), computed the
     * way Flan 1.7.2's Claim.canInteract does for players: the owner may do everything; a member
     * uses their group's value where the group sets one, otherwise the claim-wide value; anyone
     * else uses the claim-wide value. Flan's "global" permissions (not group-editable) are left
     * out. In Flan 1.7.2 a player has exactly one group per claim.
     */
    public static java.util.Set<String> effectivePermissions(Claim claim, UUID player) {
        java.util.Set<String> allowed = new java.util.TreeSet<>();
        java.util.Collection<ClaimPermission> global = PermissionRegistry.globalPerms();
        String group = members(claim).get(player);
        boolean owner = player.equals(claim.getOwner());
        for (ClaimPermission permission : PermissionRegistry.getPerms()) {
            if (global.contains(permission)) {
                continue;
            }
            boolean ok;
            if (owner) {
                ok = true;
            } else {
                int value = group == null ? -1 : claim.groupHasPerm(group, permission);
                ok = value == -1 ? claimWide(claim, permission) : value == 1;
            }
            if (ok) {
                allowed.add(permission.id);
            }
        }
        return allowed;
    }

    /** Claim-wide value, a subclaim falling back to its parent when unset (Claim.hasPerm). */
    private static boolean claimWide(Claim claim, ClaimPermission permission) {
        Claim parent = claim.parentClaim();
        int own = claim.permEnabled(permission);
        if (parent != null && own == -1) {
            return parent.permEnabled(permission) == 1;
        }
        return own == 1;
    }

    /**
     * True if Flan already allows this permission for everyone at the position (the claim-wide
     * setting, falling back to the parent for unset subclaim values), mirroring Claim.hasPerm in
     * Flan 1.7.2. Such actions are never treated as hostile and never overridden.
     */
    public static boolean allowedForEveryone(Claim parent, BlockPos pos, ClaimPermission permission) {
        Claim sub = parent.getSubClaim(pos);
        if (sub != null) {
            int own = sub.permEnabled(permission);
            return own == -1 ? parent.permEnabled(permission) == 1 : own == 1;
        }
        return parent.permEnabled(permission) == 1;
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
