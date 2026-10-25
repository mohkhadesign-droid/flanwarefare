package net.flanwarfare.team;

import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.FlanWarfare;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.flan.FlanBridge;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Attacking sides and defenders (confirmed policy).
 *
 * <ul>
 *   <li><b>Side</b> of a player: their FTB Teams team when FTB Teams is available and enabled
 *   (a party groups its members; a solo player's own team is unique to them). Without FTB Teams
 *   every player is their own side.</li>
 *   <li><b>Defenders</b> of a claim (each configurable): its owner; players on the owner's FTB
 *   team; Flan members per {@code trusted_defenders} (default: those whose group grants EDITCLAIM
 *   or EDITPERMS). Other members, e.g. trusted for one chest, are neutral: they can neither
 *   defend nor capture that claim.</li>
 * </ul>
 *
 * <p>FTB Teams is optional and accessed reflectively: it is not needed to build or run the
 * add-on, and its API cannot be verified at compile time here. Resolution happens once at
 * startup; failures are logged and reported by {@code /war admin status}, and the add-on then
 * falls back to individual sides rather than guessing.
 */
public final class SideResolver {
    private Method teamIdMethod;
    private Method teamObjectMethod;
    private Method teamGetId;
    private String status = "not initialised";

    public void init() {
        teamIdMethod = null;
        teamObjectMethod = null;
        teamGetId = null;
        if (!ModList.get().isLoaded("ftbteams")) {
            status = "FTB Teams not installed: every player is their own side";
            return;
        }
        try {
            Class<?> api = Class.forName("dev.ftb.mods.ftbteams.FTBTeamsAPI");
            try {
                teamIdMethod = api.getMethod("getPlayerTeamID", UUID.class);
            } catch (NoSuchMethodException notPresent) {
                teamObjectMethod = api.getMethod("getPlayerTeam", UUID.class);
                teamGetId = teamObjectMethod.getReturnType().getMethod("getId");
            }
            status = "FTB Teams integration active (" + (teamIdMethod != null ? "getPlayerTeamID" : "getPlayerTeam().getId()") + ")";
        } catch (ReflectiveOperationException | LinkageError e) {
            teamIdMethod = null;
            teamObjectMethod = null;
            status = "FTB Teams present but its API was not recognised (" + e + "): every player is their own side";
            FlanWarfare.LOG.warn(status);
        }
    }

    public String status() {
        if (!WarConfig.USE_FTB_TEAMS.get()) {
            return "FTB Teams disabled in config: every player is their own side";
        }
        return status;
    }

    /** Stable side identifier for a player. */
    public String sideOf(UUID player) {
        if (WarConfig.USE_FTB_TEAMS.get()) {
            UUID team = teamOf(player);
            if (team != null) {
                return "team:" + team;
            }
        }
        return "player:" + player;
    }

    private UUID teamOf(UUID player) {
        try {
            if (teamIdMethod != null) {
                Object id = teamIdMethod.invoke(null, player);
                return id instanceof UUID ? (UUID) id : null;
            }
            if (teamObjectMethod != null) {
                Object team = teamObjectMethod.invoke(null, player);
                if (team == null) {
                    return null;
                }
                Object id = teamGetId.invoke(team);
                return id instanceof UUID ? (UUID) id : null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            FlanWarfare.LOG.debug("FTB Teams lookup failed for {}: {}", player, e.toString());
        }
        return null;
    }

    /** How a player relates to a claim during capture. */
    public enum Role {
        /** Pauses the capture by standing in the zone. */
        DEFENDER,
        /** Can capture the claim. */
        ATTACKER,
        /** Neither: e.g. the owner with owner_can_contest=false, or a member trusted for one chest. */
        NEUTRAL
    }

    /** True if both players are on the same FTB Teams team (never true without FTB Teams). */
    public boolean sameTeam(UUID a, UUID b) {
        if (a.equals(b) || !WarConfig.USE_FTB_TEAMS.get()) {
            return false;
        }
        UUID teamA = teamOf(a);
        return teamA != null && teamA.equals(teamOf(b));
    }

    /** Whether a claim member counts as a defender under capture.defenders.trusted_defenders. */
    public static boolean isDefenderMember(Claim claim, UUID player) {
        switch (WarConfig.DEFENDER_MEMBERS.get()) {
            case ALL_MEMBERS:
                return FlanBridge.isMember(claim, player);
            case MANAGERS:
                return FlanBridge.isManager(claim, player);
            default:
                return false;
        }
    }

    public Role roleOf(ServerPlayerEntity player, Claim claim) {
        UUID id = player.getUniqueID();
        UUID owner = claim.getOwner();
        if (id.equals(owner)) {
            return WarConfig.OWNER_CAN_CONTEST.get() ? Role.DEFENDER : Role.NEUTRAL;
        }
        if (sameTeam(id, owner)) {
            return WarConfig.TEAM_MEMBERS_DEFEND.get() ? Role.DEFENDER : Role.NEUTRAL;
        }
        if (FlanBridge.isMember(claim, id)) {
            return isDefenderMember(claim, id) ? Role.DEFENDER : Role.NEUTRAL;
        }
        return Role.ATTACKER;
    }
}
