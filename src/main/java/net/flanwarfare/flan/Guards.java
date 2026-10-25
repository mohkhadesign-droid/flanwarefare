package net.flanwarfare.flan;

import io.github.flemmli97.flan.api.permission.ClaimPermission;
import io.github.flemmli97.flan.api.permission.PermissionRegistry;
import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.FlanWarfare;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.data.ClaimState;
import net.flanwarfare.protection.ExplosionRules;
import net.flanwarfare.util.Msg;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.FakePlayer;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Decisions behind the Mixin guards. Every method is defensive: when the add-on is not ready
 * (startup, safe mode) nothing is blocked, so Flan keeps its ordinary behaviour.
 */
public final class Guards {
    private static final ThreadLocal<int[]> BYPASS = ThreadLocal.withInitial(() -> new int[1]);

    private Guards() {
    }

    /** Runs Flan operations on behalf of the add-on itself (conquest, cleanup, admin repair). */
    public static <T> T bypass(Supplier<T> action) {
        int[] depth = BYPASS.get();
        depth[0]++;
        try {
            return action.get();
        } finally {
            depth[0]--;
        }
    }

    public static boolean bypassed() {
        return BYPASS.get()[0] > 0;
    }

    private static ClaimRecord recordOf(WarCore core, Claim claim) {
        Claim parent = FlanBridge.top(claim);
        if (parent == null || parent.isAdminClaim() || core.isExcluded(parent)) {
            return null;
        }
        return core.lifecycle().recordFor(parent);
    }

    /** Claims without a placed flag cannot be managed (members, home, name). */
    public static boolean blockManagement(Claim claim) {
        WarCore core = WarCore.ready();
        if (core == null || bypassed() || !WarConfig.REQUIRE_FLAG_FOR_MANAGEMENT.get()) {
            return false;
        }
        ClaimRecord record = recordOf(core, claim);
        return record != null && record.state != ClaimState.ACTIVE;
    }

    /** Opens explosion/fire/piston/fluid effects in active claims during raids, if configured. */
    public static boolean allowWorldEffect(Claim claim, ClaimPermission permission) {
        WarCore core = WarCore.ready();
        if (core == null || !core.raid().isActive()) {
            return false;
        }
        boolean wanted;
        if (permission == PermissionRegistry.EXPLOSIONS) {
            wanted = ExplosionRules.allowed(ExplosionRules.current());
        } else if (permission == PermissionRegistry.FIRESPREAD) {
            wanted = WarConfig.ALLOW_FIRE_SPREAD.get();
        } else if (permission == PermissionRegistry.PISTONBORDER) {
            wanted = WarConfig.ALLOW_PISTONS.get();
        } else if (permission == PermissionRegistry.WATERBORDER) {
            wanted = WarConfig.ALLOW_FLUID_FLOW.get();
        } else {
            wanted = false;
        }
        if (!wanted) {
            return false;
        }
        ClaimRecord record = recordOf(core, claim);
        // Claims without a flag are unprotected, so raids open them too.
        return record != null && (record.isWarEnabled() || record.state == ClaimState.PENDING_FLAG)
                && !core.isDimensionDisabled(record.dimension);
    }

    public static boolean blockResize(Claim claim, BlockPos from, BlockPos to, ServerPlayerEntity player) {
        WarCore core = WarCore.ready();
        if (core == null || bypassed()) {
            return false;
        }
        ClaimRecord record = recordOf(core, claim);
        if (record == null) {
            return false;
        }
        String reason = null;
        if (record.state != ClaimState.ACTIVE && WarConfig.REQUIRE_FLAG_FOR_MANAGEMENT.get()) {
            reason = "This claim is inactive. Place its flag with /flag place before resizing.";
        } else if (record.conquered && !WarConfig.ALLOW_CONQUERED_RESIZE.get()) {
            reason = "Conquered territory cannot be resized.";
        } else if (record.hasFlag && !flagInsideAfterResize(claim, from, to, record.flagPos())) {
            reason = "That resize would leave the claim's flag outside the claim. Move the flag first (/flag move).";
        }
        if (reason == null) {
            return false;
        }
        Msg.send(player, Msg.error(reason));
        return true;
    }

    /**
     * Mirrors ClaimStorage.resizeClaim in Flan 1.7.2: the corner opposite {@code from} is kept
     * and the new claim spans from it to {@code to}. Height only ever extends downwards, so only
     * the horizontal bounds can exclude the flag.
     */
    static boolean flagInsideAfterResize(Claim claim, BlockPos from, BlockPos to, BlockPos flag) {
        int[] d = claim.getDimensions();
        int oppositeX = d[0] == from.getX() ? d[1] : d[0];
        int oppositeZ = d[2] == from.getZ() ? d[3] : d[2];
        int minX = Math.min(oppositeX, to.getX());
        int maxX = Math.max(oppositeX, to.getX());
        int minZ = Math.min(oppositeZ, to.getZ());
        int maxZ = Math.max(oppositeZ, to.getZ());
        return flag.getX() >= minX && flag.getX() <= maxX && flag.getZ() >= minZ && flag.getZ() <= maxZ;
    }

    public static boolean blockTransfer(Claim claim, ServerPlayerEntity player) {
        WarCore core = WarCore.ready();
        if (core == null || bypassed()) {
            return false;
        }
        ClaimRecord record = recordOf(core, claim);
        if (record == null) {
            return false;
        }
        String reason = null;
        if (record.state != ClaimState.ACTIVE && WarConfig.REQUIRE_FLAG_FOR_MANAGEMENT.get()) {
            reason = "This claim is inactive and cannot be transferred.";
        } else if (record.conquered) {
            reason = "Conquered territory cannot be transferred.";
        }
        if (reason == null) {
            return false;
        }
        if (player != null && !(player instanceof FakePlayer)) {
            Msg.send(player, Msg.error(reason));
        }
        FlanWarfare.LOG.debug("Blocked ownership transfer of claim {}: {}", claim.getClaimID(), reason);
        return true;
    }

    /** Claim-slot count for Flan's maxClaims check: conquered claims do not use a slot. */
    public static int countSlotClaims(Set<?> ownedClaims) {
        WarCore core = WarCore.ready();
        if (core == null) {
            return ownedClaims.size();
        }
        int count = 0;
        for (Object entry : ownedClaims) {
            if (entry instanceof Claim && core.data().isConquered(((Claim) entry).getClaimID())) {
                continue;
            }
            count++;
        }
        return count;
    }
}
