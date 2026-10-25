package net.flanwarfare.flan;

import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.claim.ClaimStorage;
import net.flanwarfare.core.WarCore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.server.ServerWorld;

import java.util.Collection;
import java.util.UUID;

/**
 * Claim-block exemption for conquered land.
 *
 * <p>Verified Flan 1.7.2 behaviour: used blocks = sum of {@code getPlane()} over the player's
 * current non-admin claims, recomputed on every query. Consequences:
 * <ul>
 *   <li>The previous owner's allowance is restored automatically the moment the claim is
 *   transferred away. The add-on therefore never adds claim blocks to anyone.</li>
 *   <li>The conqueror would be charged; this method returns the exact amount to subtract,
 *   computed from the same live claims, so the result can never drop below what the player's
 *   ordinary claims use.</li>
 * </ul>
 */
public final class Accounting {
    private Accounting() {
    }

    public static int conqueredBlocks(MinecraftServer server, UUID player) {
        WarCore core = WarCore.ready();
        if (core == null || server == null || player == null) {
            return 0;
        }
        int total = 0;
        for (ServerWorld world : server.getWorlds()) {
            Collection<Claim> owned = ClaimStorage.get(world).allClaimsFromPlayer(player);
            if (owned == null) {
                continue;
            }
            for (Claim claim : owned) {
                if (!claim.isAdminClaim() && core.data().isConquered(claim.getClaimID())) {
                    total += claim.getPlane();
                }
            }
        }
        return total;
    }

    /** Number of conquered claims a player currently owns (for the optional limit). */
    public static int conqueredCount(MinecraftServer server, UUID player) {
        WarCore core = WarCore.ready();
        if (core == null) {
            return 0;
        }
        int count = 0;
        for (ServerWorld world : server.getWorlds()) {
            Collection<Claim> owned = ClaimStorage.get(world).allClaimsFromPlayer(player);
            if (owned == null) {
                continue;
            }
            for (Claim claim : owned) {
                if (!claim.isAdminClaim() && core.data().isConquered(claim.getClaimID())) {
                    count++;
                }
            }
        }
        return count;
    }
}
