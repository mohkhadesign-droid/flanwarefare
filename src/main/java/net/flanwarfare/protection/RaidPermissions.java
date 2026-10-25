package net.flanwarfare.protection;

import io.github.flemmli97.flan.api.forge.PermissionCheckEvent;
import io.github.flemmli97.flan.api.permission.ClaimPermission;
import io.github.flemmli97.flan.api.permission.PermissionRegistry;
import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.player.PlayerClaimData;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.data.ClaimState;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.raid.RaidScheduler;
import net.flanwarfare.util.Msg;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ActionResultType;
import net.minecraftforge.common.util.FakePlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Effective-permission evaluation through Flan's {@link PermissionCheckEvent}.
 *
 * <p>Verified in Flan 1.7.2: {@code Claim.canInteract} posts the event before any of its own
 * checks, including the admin-claim check. SUCCESS allows, FAIL denies, PASS lets Flan decide.
 * Nothing is ever written to a claim, so protection returns the instant a raid ends.
 *
 * <p>Decision order:
 * <ol>
 *   <li>Wilderness, admin claims, excluded claims, disabled dimensions: PASS (Flan decides).</li>
 *   <li>Management permissions (EDITCLAIM, EDITPERMS, EDITPOTIONS) on a claim without a placed
 *   flag: FAIL for everyone, the owner included, unless an operator uses Flan's admin mode.</li>
 *   <li>Any other permission on a PENDING_FLAG claim: SUCCESS (the claim protects nothing).</li>
 *   <li>Raid active, ACTIVE/RECOVERY claim, player is neither owner nor a Flan member, and the
 *   permission is one of the configured raid permissions: SUCCESS.</li>
 *   <li>Otherwise PASS.</li>
 * </ol>
 * Other protection mods (YAWP, WorldProtector) cancel the underlying Forge events themselves;
 * a SUCCESS here only stops Flan from objecting and cannot override them. Flags have their own
 * protection in {@link FlagProtection}.
 */
public final class RaidPermissions {
    private final Map<UUID, Long> lastNotice = new HashMap<>();

    public void onPermissionCheck(PermissionCheckEvent event) {
        WarCore core = WarCore.ready();
        ServerPlayerEntity player = event.player;
        if (core == null || player == null || event.pos == null) {
            return;
        }
        Claim claim = FlanBridge.parentAt(player.getServerWorld(), event.pos);
        if (claim == null || claim.isAdminClaim() || core.isExcluded(claim)) {
            return;
        }
        ClaimRecord record = core.lifecycle().recordFor(claim);
        ClaimPermission permission = event.permission;

        if (FlanBridge.isManagementPermission(permission)) {
            if (record.state != ClaimState.ACTIVE && WarConfig.REQUIRE_FLAG_FOR_MANAGEMENT.get() && !adminMode(player)) {
                event.setResult(ActionResultType.FAIL);
                notice(player, record.state == ClaimState.PENDING_FLAG
                        ? "This claim is inactive until its flag is placed: stand inside and run /flag place."
                        : "This claim is locked pending administrator recovery of its flag.");
            }
            return;
        }

        if (record.state == ClaimState.PENDING_FLAG && WarConfig.REQUIRE_FLAG_FOR_PROTECTION.get()
                && permission != PermissionRegistry.HURTPLAYER) {
            // Lift restrictions only. Perk permissions (where "allowed" grants a benefit rather
            // than lifting a restriction) keep Flan's normal answer. PvP is not a building
            // protection: it follows the raid rules below like any other claim.
            if (!isPerk(permission)) {
                event.setResult(ActionResultType.SUCCESS);
            }
            return;
        }

        if (!core.raid().isActive()) {
            return;
        }
        // PvP (Flan checks HURTPLAYER for the attacker at the victim's position, for melee,
        // projectiles and any damage credited to a player) is opened during raids for everyone
        // in a raidable claim, including claims without a flag, so members can fight back too.
        // Fake players only if fake players are allowed. Server pvp=false still applies.
        if (permission == PermissionRegistry.HURTPLAYER) {
            if ((record.isWarEnabled() || record.state == ClaimState.PENDING_FLAG)
                    && core.raid().isRaidPermission(permission)
                    && (!(player instanceof FakePlayer) || WarConfig.ALLOW_FAKE_PLAYERS.get())) {
                event.setResult(ActionResultType.SUCCESS);
            }
            return;
        }
        if (!record.isWarEnabled()) {
            return;
        }
        // Players the owner authorized through Flan (owner, claim members, members of the
        // subclaim at this spot) keep exactly the permissions Flan gives them: never widened,
        // never restricted, never reported as raiders. Same for actions Flan already allows
        // everyone in this claim.
        UUID id = player.getUniqueID();
        boolean authorized = !(player instanceof FakePlayer) && FlanBridge.isAuthorizedAt(claim, event.pos, id);
        if (authorized || FlanBridge.allowedForEveryone(claim, event.pos, permission)) {
            return;
        }
        if (core.raid().isRaidPermission(permission)) {
            event.setResult(ActionResultType.SUCCESS);
            if (!(player instanceof FakePlayer) && RaidScheduler.isHostileAction(permission)) {
                core.alerts().hostileAction(claim, record, describe(permission));
            }
        }
    }

    /** Short wording for the alert chat line. */
    private static String describe(ClaimPermission permission) {
        if (permission == PermissionRegistry.BREAK) {
            return "breaking blocks";
        }
        if (permission == PermissionRegistry.PLACE) {
            return "placing blocks";
        }
        if (permission == PermissionRegistry.OPENCONTAINER) {
            return "opening containers";
        }
        return "using your blocks";
    }

    /** FLIGHT (allow flying), NOHUNGER (disable hunger), LOCKITEMS (lock death drops). */
    private static boolean isPerk(ClaimPermission permission) {
        return permission == PermissionRegistry.FLIGHT
                || permission == PermissionRegistry.NOHUNGER
                || permission == PermissionRegistry.LOCKITEMS;
    }

    private static boolean adminMode(ServerPlayerEntity player) {
        if (player instanceof FakePlayer) {
            return false;
        }
        return player.hasPermissionLevel(2) && PlayerClaimData.get(player).isAdminIgnoreClaim();
    }

    private void notice(ServerPlayerEntity player, String text) {
        if (player instanceof FakePlayer) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueID());
        if (last == null || now - last > 3000) {
            lastNotice.put(player.getUniqueID(), now);
            Msg.send(player, Msg.error(text));
        }
    }
}
