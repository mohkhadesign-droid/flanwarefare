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
 *   <li><b>Defenders</b> of a claim: its owner; players on the owner's side; and, if enabled,
 *   Flan members whose group grants EDITCLAIM or EDITPERMS. Players trusted only for lesser
 *   permissions (e.g. one chest) are not defenders.</li>
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

    public boolean isDefender(ServerPlayerEntity player, Claim claim) {
        UUID id = player.getUniqueID();
        UUID owner = claim.getOwner();
        if (id.equals(owner)) {
            return true;
        }
        if (sideOf(id).equals(sideOf(owner))) {
            return true;
        }
        return WarConfig.DEFENDERS_INCLUDE_MANAGERS.get() && FlanBridge.isManager(claim, id);
    }
}
