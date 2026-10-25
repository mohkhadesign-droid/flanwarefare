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
 * <p>Kingdoms for statistics are FTB Teams parties ({@link #kingdomOf}).
 *
 * <p>FTB Teams is optional and accessed reflectively: it is not needed to build or run the
 * add-on, and its API cannot be verified at compile time here. Resolution happens once at
 * startup; failures are logged and reported by {@code /war admin status}, and the add-on then
 * falls back to individual sides rather than guessing.
 */
public final class SideResolver {
    private static final String[] API_CLASSES = {
            "dev.ftb.mods.ftbteams.FTBTeamsAPI",
            "com.feed_the_beast.mods.ftbteams.FTBTeamsAPI"
    };

    /** A kingdom as recorded in statistics: stable id plus display name at the time. */
    public static final class Kingdom {
        public final String id;
        public final String name;

        Kingdom(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private Method teamIdMethod;
    private Method teamObjectMethod;
    private String status = "not initialised";
    private String kingdomStatus = "";

    public void init() {
        teamIdMethod = null;
        teamObjectMethod = null;
        kingdomStatus = "";
        if (!ModList.get().isLoaded("ftbteams")) {
            status = "FTB Teams not installed: every player is their own side, no kingdom statistics";
            return;
        }
        Throwable last = null;
        for (String className : API_CLASSES) {
            try {
                Class<?> api = Class.forName(className);
                try {
                    teamObjectMethod = api.getMethod("getPlayerTeam", UUID.class);
                } catch (NoSuchMethodException absent) {
                    teamObjectMethod = null;
                }
                try {
                    teamIdMethod = api.getMethod("getPlayerTeamID", UUID.class);
                } catch (NoSuchMethodException absent) {
                    teamIdMethod = null;
                }
                if (teamObjectMethod != null || teamIdMethod != null) {
                    status = "FTB Teams integration active (" + className + ")";
                    return;
                }
            } catch (ClassNotFoundException | LinkageError e) {
                last = e;
            }
        }
        status = "FTB Teams present but its API was not recognised (" + last
                + "): every player is their own side, no kingdom statistics";
        FlanWarfare.LOG.warn(status);
    }

    public String status() {
        if (!WarConfig.USE_FTB_TEAMS.get()) {
            return "FTB Teams disabled in config: every player is their own side";
        }
        return status + kingdomStatus;
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

    private Object teamObject(UUID player) {
        if (teamObjectMethod == null) {
            return null;
        }
        try {
            Object team = teamObjectMethod.invoke(null, player);
            if (team instanceof java.util.Optional) {
                team = ((java.util.Optional<?>) team).orElse(null);
            }
            return team;
        } catch (ReflectiveOperationException | RuntimeException e) {
            FlanWarfare.LOG.debug("FTB Teams lookup failed for {}: {}", player, e.toString());
            return null;
        }
    }

    private UUID teamOf(UUID player) {
        try {
            if (teamIdMethod != null) {
                Object id = teamIdMethod.invoke(null, player);
                if (id instanceof UUID) {
                    return (UUID) id;
                }
            }
            Object team = teamObject(player);
            Object id = team == null ? null : call(team, "getId");
            return id instanceof UUID ? (UUID) id : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            FlanWarfare.LOG.debug("FTB Teams lookup failed for {}: {}", player, e.toString());
        }
        return null;
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    /**
     * The player's kingdom: their FTB Teams party (or any team, if parties cannot be told apart
     * or kingdoms_are_parties_only is false). Null when unknown, so statistics stay accurate per
     * player and kingdom attribution is marked unavailable instead of guessed.
     */
    public Kingdom kingdomOf(UUID player) {
        if (!WarConfig.USE_FTB_TEAMS.get() || !WarConfig.LB_KINGDOMS.get()) {
            return null;
        }
        Object team = teamObject(player);
        UUID id = teamOf(player);
        if (id == null) {
            return null;
        }
        if (team != null && WarConfig.LB_KINGDOMS_PARTIES_ONLY.get()) {
            Boolean party = isParty(team);
            if (party != null && !party) {
                return null;
            }
            if (party == null && kingdomStatus.isEmpty()) {
                kingdomStatus = "; team type not detectable, so every team counts as a kingdom";
            }
        }
        return new Kingdom(id.toString(), team == null ? id.toString().substring(0, 8) : teamName(team, id));
    }

    private static Boolean isParty(Object team) {
        try {
            Object party = call(team, "isPartyTeam");
            if (party instanceof Boolean) {
                return (Boolean) party;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            FlanWarfare.LOG.debug("FTB Teams isPartyTeam unavailable, trying getType: {}", e.toString());
        }
        try {
            Object type = call(team, "getType");
            return type != null && type.toString().toUpperCase(java.util.Locale.ROOT).contains("PARTY");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String teamName(Object team, UUID id) {
        for (String method : new String[]{"getDisplayName", "getName", "getStringName"}) {
            try {
                Object name = call(team, method);
                if (name instanceof net.minecraft.util.text.ITextComponent) {
                    name = ((net.minecraft.util.text.ITextComponent) name).getString();
                }
                if (name instanceof String && !((String) name).isEmpty()) {
                    return (String) name;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                FlanWarfare.LOG.debug("FTB Teams name accessor {} unavailable: {}", method, e.toString());
            }
        }
        return id.toString().substring(0, 8);
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
