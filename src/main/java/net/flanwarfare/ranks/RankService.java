package net.flanwarfare.ranks;

import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.data.RankDef;
import net.flanwarfare.data.RankRegistry;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.RankColors;
import net.flanwarfare.logic.RankRules;
import net.flanwarfare.util.Msg;
import net.minecraft.entity.player.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Kingdom ranks: data rules and authority. Cosmetic only: nothing here touches Flan
 * permissions, ownership or operator status.
 *
 * <p><b>Scope.</b> Ranks belong to a kingdom's <i>main claim</i> (a Flan parent claim chosen with
 * {@code /war rank setmain}). The claim's current Flan owner manages its registry, read live, so
 * a conquest hands management of that claim's ranks to the conqueror, and only that claim's.
 * A player designates one main claim; when they stand in another registry claim they own (for
 * example a conquered main claim), commands act on that one.
 *
 * <p><b>Holders.</b> With {@code require_membership}, only the claim owner and Flan members of the
 * main claim or its subclaims can hold its ranks (never players who merely have public access).
 * Assignments of players who stop being members are kept but suspended (not displayed) until
 * they are members again. A player holds at most one rank server-wide; moving a rank across
 * kingdoms requires an administrator.
 */
public final class RankService {
    private final WarCore core;

    public RankService(WarCore core) {
        this.core = core;
    }

    public static boolean enabled() {
        return WarConfig.RANKS_ENABLED.get();
    }

    private Map<UUID, RankRegistry> registries() {
        return core.data().rankRegistries();
    }

    public boolean isAdmin(ServerPlayerEntity player) {
        return player.hasPermissionLevel(Msg.adminLevel());
    }

    public Claim claimOf(RankRegistry registry) {
        ClaimRecord record = core.data().claim(registry.claimId);
        if (record == null) {
            return null;
        }
        Claim claim = FlanBridge.find(core.server(), record);
        return claim == null || claim.isAdminClaim() ? null : claim;
    }

    /** Readable kingdom name: the main claim's label. */
    public String kingdomName(RankRegistry registry) {
        Claim claim = claimOf(registry);
        return claim == null ? "a former kingdom (" + registry.claimId.toString().substring(0, 8) + ")" : FlanBridge.label(claim);
    }

    public boolean isManager(ServerPlayerEntity player, RankRegistry registry) {
        if (isAdmin(player)) {
            return true;
        }
        Claim claim = claimOf(registry);
        return claim != null && player.getUniqueID().equals(claim.getOwner());
    }

    // ------------------------------------------------------------------ registry selection

    /** /war rank setmain: the parent claim the player stands in becomes their kingdom's main claim. */
    public String setMain(ServerPlayerEntity player) {
        Claim claim = FlanBridge.parentAt(player.getServerWorld(), player.getPosition());
        if (claim == null || claim.isAdminClaim() || core.isExcluded(claim)) {
            return "Stand inside one of your own claims.";
        }
        if (!claim.getOwner().equals(player.getUniqueID())) {
            return "Only the owner of this claim can make it a main claim.";
        }
        ClaimRecord record = core.lifecycle().recordFor(claim);
        RankRegistry registry = registries().get(claim.getClaimID());
        if (registry == null) {
            registry = new RankRegistry();
            registry.claimId = claim.getClaimID();
            registry.dimension = record.dimension;
            registry.createdAt = System.currentTimeMillis();
            registries().put(registry.claimId, registry);
        }
        core.data().mainClaims().put(player.getUniqueID(), claim.getClaimID());
        core.data().markDirty();
        core.audit("{} set main claim {} for kingdom ranks", player.getGameProfile().getName(), claim.getClaimID());
        return null;
    }

    /**
     * The registry a player's /war rank command acts on: a registry claim they own and stand in,
     * otherwise their designated main claim if they still own it. Null if none.
     */
    public RankRegistry contextRegistry(ServerPlayerEntity player) {
        Claim here = FlanBridge.parentAt(player.getServerWorld(), player.getPosition());
        if (here != null) {
            RankRegistry registry = registries().get(here.getClaimID());
            if (registry != null && (player.getUniqueID().equals(here.getOwner()) || isAdmin(player))) {
                return registry;
            }
        }
        UUID main = core.data().mainClaims().get(player.getUniqueID());
        RankRegistry registry = main == null ? null : registries().get(main);
        if (registry != null) {
            Claim claim = claimOf(registry);
            if (claim != null && player.getUniqueID().equals(claim.getOwner())) {
                return registry;
            }
        }
        return null;
    }

    /** Finds a registry by main-claim UUID or (unique, case-insensitive) kingdom name. */
    public RankRegistry findRegistry(String idOrName) {
        try {
            RankRegistry byId = registries().get(UUID.fromString(idOrName));
            if (byId != null) {
                return byId;
            }
        } catch (IllegalArgumentException notUuid) {
            // fall through to name lookup
            idOrName = idOrName.trim();
        }
        RankRegistry found = null;
        for (RankRegistry registry : registries().values()) {
            if (kingdomName(registry).equalsIgnoreCase(idOrName)) {
                if (found != null) {
                    return null; // ambiguous: caller must use the UUID
                }
                found = registry;
            }
        }
        return found;
    }

    public List<RankRegistry> allRegistries() {
        return new ArrayList<>(registries().values());
    }

    public RankDef findRank(RankRegistry registry, String nameOrId) {
        String key = nameOrId.trim();
        RankDef byId = registry.ranks.get(key);
        if (byId != null) {
            return byId;
        }
        for (RankDef rank : registry.ranks.values()) {
            if (rank.name.equalsIgnoreCase(key)) {
                return rank;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ rank definitions

    public String create(RankRegistry registry, String name, String actor) {
        String problem = RankRules.checkName(name, WarConfig.RANKS_MAX_NAME_LENGTH.get());
        if (problem != null) {
            return problem;
        }
        if (registry.ranks.size() >= WarConfig.RANKS_MAX_PER_KINGDOM.get()) {
            return "This kingdom already has the maximum of " + WarConfig.RANKS_MAX_PER_KINGDOM.get() + " ranks.";
        }
        if (findRank(registry, name) != null) {
            return "A rank with that name already exists.";
        }
        RankDef rank = new RankDef();
        rank.id = newId(registry);
        rank.name = name.trim();
        rank.colors.add(0xFFFFFF);
        rank.createdAt = System.currentTimeMillis();
        registry.ranks.put(rank.id, rank);
        changed(registry, "created rank '" + rank.name + "' (" + rank.id + ")", actor);
        return null;
    }

    private static String newId(RankRegistry registry) {
        String id;
        do {
            id = "r" + Long.toString(Math.abs(UUID.randomUUID().getLeastSignificantBits()) % 0x7FFFFFFFL, 36);
        } while (registry.ranks.containsKey(id));
        return id;
    }

    public String delete(RankRegistry registry, RankDef rank, String actor) {
        registry.ranks.remove(rank.id);
        registry.assignments.values().removeIf(id -> id.equals(rank.id));
        changed(registry, "deleted rank '" + rank.name + "'", actor);
        return null;
    }

    public String rename(RankRegistry registry, RankDef rank, String newName, String actor) {
        String problem = RankRules.checkName(newName, WarConfig.RANKS_MAX_NAME_LENGTH.get());
        if (problem != null) {
            return problem;
        }
        RankDef clash = findRank(registry, newName);
        if (clash != null && clash != rank) {
            return "A rank with that name already exists.";
        }
        String old = rank.name;
        rank.name = newName.trim();
        changed(registry, "renamed rank '" + old + "' to '" + rank.name + "'", actor);
        return null;
    }

    public String setColor(RankRegistry registry, RankDef rank, String color, String actor) {
        int rgb;
        try {
            rgb = RankColors.parse(color, WarConfig.RANKS_ALLOW_HEX.get());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        rank.colors.clear();
        rank.colors.add(rgb);
        changed(registry, "set colour of '" + rank.name + "' to " + RankColors.format(rgb), actor);
        return null;
    }

    public String setGradient(RankRegistry registry, RankDef rank, List<String> colors, String actor) {
        if (!WarConfig.RANKS_ALLOW_GRADIENTS.get()) {
            return "Gradients are disabled on this server.";
        }
        if (colors.size() < 2) {
            return "A gradient needs at least two colours.";
        }
        if (colors.size() > WarConfig.RANKS_MAX_GRADIENT_COLORS.get()) {
            return "At most " + WarConfig.RANKS_MAX_GRADIENT_COLORS.get() + " gradient colours.";
        }
        List<Integer> parsed = new ArrayList<>();
        for (String c : colors) {
            try {
                parsed.add(RankColors.parse(c, WarConfig.RANKS_ALLOW_HEX.get()));
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }
        rank.colors.clear();
        rank.colors.addAll(parsed);
        changed(registry, "set gradient of '" + rank.name + "' to " + colors, actor);
        return null;
    }

    /** Back to a single colour: the gradient's first colour. */
    public String clearGradient(RankRegistry registry, RankDef rank, String actor) {
        if (rank.colors.size() > 1) {
            Integer first = rank.colors.get(0);
            rank.colors.clear();
            rank.colors.add(first);
        }
        changed(registry, "cleared gradient of '" + rank.name + "'", actor);
        return null;
    }

    public String setEffect(RankRegistry registry, RankDef rank, String effectName, String actor) {
        String key = effectName.trim().toUpperCase(Locale.ROOT);
        if (key.equals("STATIC") || key.equals("GRADIENT")) {
            key = "NONE"; // static colours/gradients are plain colours without an effect
        }
        RankColors.Effect effect;
        try {
            effect = RankColors.Effect.valueOf(key);
        } catch (IllegalArgumentException e) {
            return "Unknown effect. Use: none, static, bold, glow, rainbow, pulse, wave"
                    + (WarConfig.RANKS_ALLOW_OBFUSCATED.get() ? ", obfuscated" : "") + ".";
        }
        if (!effectAllowed(effect)) {
            return "The " + effect.name().toLowerCase(Locale.ROOT) + " effect is disabled on this server.";
        }
        rank.effect = effect.name();
        changed(registry, "set effect of '" + rank.name + "' to " + effect, actor);
        return null;
    }

    public static boolean effectAllowed(RankColors.Effect effect) {
        switch (effect) {
            case BOLD:
                return WarConfig.RANKS_ALLOW_BOLD.get();
            case GLOW:
                return WarConfig.RANKS_ALLOW_GLOW.get();
            case RAINBOW:
                return WarConfig.RANKS_ALLOW_RAINBOW.get();
            case PULSE:
                return WarConfig.RANKS_ALLOW_PULSE.get();
            case WAVE:
                return WarConfig.RANKS_ALLOW_WAVE.get();
            case OBFUSCATED:
                return WarConfig.RANKS_ALLOW_OBFUSCATED.get();
            default:
                return true;
        }
    }

    public String setDecoration(RankRegistry registry, RankDef rank, boolean prefix, String text, String actor) {
        String problem = RankRules.checkDecoration(text, WarConfig.RANKS_MAX_DECORATION_LENGTH.get(),
                WarConfig.RANKS_ALLOWED_SYMBOLS.get());
        if (problem != null) {
            return problem;
        }
        if (prefix) {
            rank.prefix = text;
        } else {
            rank.suffix = text;
        }
        changed(registry, "set " + (prefix ? "prefix" : "suffix") + " of '" + rank.name + "' to '" + text + "'", actor);
        return null;
    }

    public String clearDecorations(RankRegistry registry, RankDef rank, String actor) {
        rank.prefix = "";
        rank.suffix = "";
        changed(registry, "cleared decorations of '" + rank.name + "'", actor);
        return null;
    }

    public String setPosition(RankRegistry registry, RankDef rank, String position, String actor) {
        if (position.equalsIgnoreCase("default")) {
            rank.position = null;
        } else {
            RankRules.Position p = RankRules.parsePosition(position);
            if (p == null) {
                return "Use left, above, below or default.";
            }
            String problem = positionAllowed(p);
            if (problem != null) {
                return problem;
            }
            rank.position = p.name();
        }
        changed(registry, "set position of '" + rank.name + "' to " + (rank.position == null ? "default" : rank.position), actor);
        return null;
    }

    private static String positionAllowed(RankRules.Position p) {
        if (p == RankRules.Position.ABOVE && !WarConfig.RANK_ALLOW_ABOVE.get()) {
            return "ABOVE placement is disabled on this server.";
        }
        if (p == RankRules.Position.BELOW && !WarConfig.RANK_ALLOW_BELOW.get()) {
            return "BELOW placement is disabled on this server.";
        }
        return null;
    }

    // ------------------------------------------------------------------ assignments

    /** Registry in which a player currently holds a rank, or null. */
    public RankRegistry registryOf(UUID player) {
        for (RankRegistry registry : registries().values()) {
            if (registry.assignments.containsKey(player)) {
                return registry;
            }
        }
        return null;
    }

    /** True if the player may hold this registry's ranks right now (live Flan membership). */
    public boolean eligible(RankRegistry registry, UUID player) {
        Claim claim = claimOf(registry);
        if (claim == null) {
            return false;
        }
        if (!WarConfig.RANKS_REQUIRE_MEMBERSHIP.get()) {
            return true;
        }
        return player.equals(claim.getOwner()) || FlanBridge.isMemberAnywhere(claim, player);
    }

    public String assign(RankRegistry registry, UUID player, String playerName, RankDef rank, boolean admin, String actor) {
        if (!eligible(registry, player)) {
            return playerName + " is not a member of " + kingdomName(registry)
                    + ". Add them through Flan's permission groups first (public access does not count).";
        }
        RankRegistry other = registryOf(player);
        if (other != null && other != registry) {
            if (!admin) {
                return playerName + " already holds a rank in " + kingdomName(other) + ". Only an administrator can move it.";
            }
            other.assignments.remove(player);
            other.positionOverrides.remove(player);
            changed(other, "rank of " + playerName + " moved to " + kingdomName(registry), actor);
        }
        registry.assignments.put(player, rank.id);
        changed(registry, "assigned '" + rank.name + "' to " + playerName, actor);
        return null;
    }

    public String unassign(UUID player, String playerName, String actor) {
        RankRegistry registry = registryOf(player);
        if (registry == null) {
            return playerName + " has no rank.";
        }
        registry.assignments.remove(player);
        registry.positionOverrides.remove(player);
        changed(registry, "removed the rank of " + playerName, actor);
        return null;
    }

    public String setPlayerPosition(UUID player, String playerName, String position, String actor) {
        RankRegistry registry = registryOf(player);
        if (registry == null) {
            return playerName + " has no rank.";
        }
        if (position.equalsIgnoreCase("reset")) {
            registry.positionOverrides.remove(player);
        } else {
            if (!WarConfig.RANK_ALLOW_OVERRIDES.get()) {
                return "Per-player positions are disabled on this server.";
            }
            RankRules.Position p = RankRules.parsePosition(position);
            if (p == null) {
                return "Use left, above, below or reset.";
            }
            String problem = positionAllowed(p);
            if (problem != null) {
                return problem;
            }
            registry.positionOverrides.put(player, p.name());
        }
        changed(registry, "display position of " + playerName + " set to " + position.toLowerCase(Locale.ROOT), actor);
        return null;
    }

    // ------------------------------------------------------------------ display lookup

    /** A rank as currently displayed for a player. */
    public static final class Active {
        public final RankRegistry registry;
        public final RankDef rank;
        public final RankRules.Position position;

        Active(RankRegistry registry, RankDef rank, RankRules.Position position) {
            this.registry = registry;
            this.rank = rank;
            this.position = position;
        }
    }

    /** The rank to display for a player now, or null (no rank, suspended, or ranks disabled). */
    public Active active(UUID player) {
        if (!enabled()) {
            return null;
        }
        RankRegistry registry = registryOf(player);
        if (registry == null) {
            return null;
        }
        RankDef rank = registry.ranks.get(registry.assignments.get(player));
        if (rank == null || !eligible(registry, player)) {
            return null;
        }
        RankRules.Position override = parse(registry.positionOverrides.get(player));
        RankRules.Position position = RankRules.resolve(override, parse(rank.position), WarConfig.RANK_DEFAULT_POSITION.get(),
                WarConfig.RANK_ALLOW_OVERRIDES.get(), WarConfig.RANK_ALLOW_ABOVE.get(), WarConfig.RANK_ALLOW_BELOW.get());
        return new Active(registry, rank, position);
    }

    private static RankRules.Position parse(String name) {
        return name == null ? null : RankRules.parsePosition(name);
    }

    /** Players assigned to a rank, with whether they are currently suspended. */
    public List<UUID> holders(RankRegistry registry, RankDef rank) {
        List<UUID> list = new ArrayList<>();
        for (Map.Entry<UUID, String> e : registry.assignments.entrySet()) {
            if (e.getValue().equals(rank.id)) {
                list.add(e.getKey());
            }
        }
        return list;
    }

    /** A main claim was deleted in Flan: its registry goes too (ranks of a vanished kingdom). */
    public void claimDeleted(UUID claimId) {
        RankRegistry registry = registries().remove(claimId);
        if (registry != null) {
            core.data().mainClaims().values().removeIf(id -> id.equals(claimId));
            core.audit("Rank registry of deleted claim {} removed ({} ranks, {} assignments)", claimId,
                    registry.ranks.size(), registry.assignments.size());
            core.rankDisplay().refreshAll();
        }
    }

    /** Drops registries whose claim no longer has a record (for example deleted while offline). */
    public void pruneOrphans() {
        for (UUID id : new ArrayList<>(registries().keySet())) {
            if (core.data().claim(id) == null) {
                claimDeleted(id);
            }
        }
    }

    /**
     * A conquest (or rollback) changed the owner of a claim. Management follows the live Flan
     * owner automatically; this only tells the people involved. Assignments are kept, and stay
     * visible for holders who are still Flan members of the claim.
     */
    public void ownerChanged(UUID claimId, UUID oldOwner, UUID newOwner) {
        RankRegistry registry = registries().get(claimId);
        if (registry == null) {
            return;
        }
        String kingdom = kingdomName(registry);
        Msg.send(core.server().getPlayerList().getPlayerByUUID(newOwner), Msg.info("You now manage the kingdom ranks of "
                + kingdom + ". Stand inside it and use /war rank list."));
        Msg.send(core.server().getPlayerList().getPlayerByUUID(oldOwner), Msg.warn("You no longer manage the kingdom ranks of "
                + kingdom + "."));
        core.audit("Rank management of {} passed from {} to {}", kingdom, oldOwner, newOwner);
        core.rankDisplay().refreshAll();
    }

    private void changed(RankRegistry registry, String what, String actor) {
        core.data().markDirty();
        core.audit("Ranks [{}] {}: {}", kingdomName(registry), actor, what);
        core.rankDisplay().refreshAll();
    }
}
