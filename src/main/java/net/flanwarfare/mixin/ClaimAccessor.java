package net.flanwarfare.mixin;

import io.github.flemmli97.flan.api.permission.ClaimPermission;
import io.github.flemmli97.flan.claim.Claim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/**
 * Read access to Flan's private permission maps so conquest can wipe the previous owner's
 * trust data. Why an accessor is needed: Flan 1.7.2 exposes no method that clears members or
 * permission groups without a player who passes an EDITPERMS check at their own position
 * ({@code removePermGroup}), and {@code setPlayerGroup} needs each member UUID, which is only
 * stored in the private {@code playersGroups} map. The maps are only cleared, never replaced.
 */
@Mixin(value = Claim.class, remap = false)
public interface ClaimAccessor {

    /** Member UUID -> permission group name. */
    @Accessor(value = "playersGroups", remap = false)
    Map<UUID, String> flanwarfare$getPlayersGroups();

    /** Permission group name -> overrides. */
    @Accessor(value = "permissions", remap = false)
    Map<String, Map<ClaimPermission, Boolean>> flanwarfare$getPermissions();

    /** Claim-wide overrides for everyone. */
    @Accessor(value = "globalPerm", remap = false)
    Map<ClaimPermission, Boolean> flanwarfare$getGlobalPerm();
}
