package net.flanwarfare.ranks;

import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.logic.RankColors;
import net.flanwarfare.logic.RankRules;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.item.ArmorStandEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shows ranks with vanilla means only (no client mod):
 * <ul>
 *   <li><b>LEFT</b>: a per-player scoreboard team whose prefix is the tag. The nametag reads
 *   "Tag | Name"; the tab list shows it too. If another plugin or mod already put the player in
 *   a scoreboard team, that team is left alone and BELOW is used instead (or chat only).</li>
 *   <li><b>BELOW</b>: an invisible marker armor stand riding the player. Its name floats just
 *   under the player's name and moves smoothly with them (passengers are positioned by the
 *   client every frame).</li>
 *   <li><b>ABOVE</b>: two radius-0 area-effect clouds and a marker armor stand stacked on the
 *   player, which lifts the label above the player's name; also a passenger chain, so smooth.</li>
 * </ul>
 * Label entities have no hitbox (they cannot block attacks or clicks), are tagged
 * {@link #ENTITY_TAG}, are removed on logout, death, dimension change and server stop, and any
 * tagged entity this session did not create is refused when it would join a world (leftovers
 * after a crash). Labels hide while the player sneaks, is invisible, spectating, dead, or
 * swimming (vanilla drops passengers under water).
 */
public final class RankDisplay {
    public static final String ENTITY_TAG = "flanwarfare_rank_label";
    private static final String TEAM_PREFIX = "fwr";
    private static final int FRAMES_PER_CYCLE = 12;

    private final WarCore core;
    private final Map<UUID, State> states = new HashMap<>();
    private final Set<UUID> ownEntities = new HashSet<>();
    private boolean dirty = true;
    private long frame;

    private static final class State {
        RankRules.Position mode;
        String note = "";
        final List<Entity> chain = new ArrayList<>();
        ServerPlayerEntity vehicle;
        String lastJson;
        boolean hidden;
        boolean animated;
        boolean waterHold;
    }

    public RankDisplay(WarCore core) {
        this.core = core;
    }

    public void refreshAll() {
        dirty = true;
    }

    /** Animation phase 0..1 of the current frame (also used for the single chat frame). */
    public double phase() {
        return (double) (frame % FRAMES_PER_CYCLE) / FRAMES_PER_CYCLE;
    }

    public static String teamName(UUID player) {
        return TEAM_PREFIX + player.toString().replace("-", "").substring(0, 13);
    }

    private static boolean isOurTeam(String name) {
        return name.length() == 16 && name.startsWith(TEAM_PREFIX) && name.substring(3).matches("[0-9a-f]{13}");
    }

    /** Removes teams left in scoreboard.dat by an earlier run (they are rebuilt as needed). */
    public void start() {
        ServerScoreboard board = core.server().getScoreboard();
        for (ScorePlayerTeam team : new ArrayList<>(board.getTeams())) {
            if (isOurTeam(team.getName())) {
                board.removeTeam(team);
            }
        }
        dirty = true;
    }

    public void stop() {
        for (ServerPlayerEntity player : new ArrayList<>(core.server().getPlayerList().getPlayers())) {
            clear(player);
        }
        start(); // removes every team of ours, also for players no longer online
        states.clear();
    }

    public void tick(long ticks) {
        int interval = Math.max(2, WarConfig.RANKS_ANIMATION_TICKS.get());
        boolean animate = ticks % interval == 0;
        if (animate) {
            frame++;
        }
        boolean full = dirty || ticks % 40 == 0;
        if (!full && !animate && ticks % 5 != 0) {
            return;
        }
        dirty = false;
        for (ServerPlayerEntity player : core.server().getPlayerList().getPlayers()) {
            State state = states.get(player.getUniqueID());
            if (full || state == null || animate && state.animated || ticks % 5 == 0) {
                apply(player, full || animate && state != null && state.animated);
            }
        }
    }

    /** One-line description for /war rank info. */
    public String describe(UUID player) {
        State state = states.get(player);
        if (state == null || state.mode == null) {
            return state == null || state.note.isEmpty() ? "not shown" : "not shown (" + state.note + ")";
        }
        return state.mode.name() + (state.note.isEmpty() ? "" : " (" + state.note + ")");
    }

    // ------------------------------------------------------------------ per player

    private void apply(ServerPlayerEntity player, boolean render) {
        State state = states.computeIfAbsent(player.getUniqueID(), k -> new State());
        RankService.Active active = core.ranks().active(player.getUniqueID());
        RankRules.Position desired = active == null || !WarConfig.RANK_TAGS_ENABLED.get() ? null : active.position;
        String note = "";
        if (desired == RankRules.Position.LEFT && inForeignTeam(player)) {
            if (WarConfig.RANK_ALLOW_BELOW.get()) {
                desired = RankRules.Position.BELOW;
                note = "another scoreboard team is in use, shown below the name instead";
            } else {
                desired = null;
                note = "another scoreboard team is in use";
            }
        }
        state.note = note;
        if (desired != state.mode) {
            clear(player);
            state.mode = desired;
            render = true;
        }
        if (desired == null) {
            return;
        }
        state.animated = RankColors.animated(RankRenderer.effect(active.rank));
        if (desired == RankRules.Position.LEFT) {
            applyLeft(player, state, active, render);
        } else {
            applyLabel(player, state, active, desired, render);
        }
    }

    private boolean inForeignTeam(ServerPlayerEntity player) {
        ScorePlayerTeam team = core.server().getScoreboard().getPlayersTeam(player.getScoreboardName());
        return team != null && !team.getName().equals(teamName(player.getUniqueID()));
    }

    private void applyLeft(ServerPlayerEntity player, State state, RankService.Active active, boolean render) {
        ServerScoreboard board = core.server().getScoreboard();
        String name = teamName(player.getUniqueID());
        ScorePlayerTeam team = board.getTeam(name);
        if (team == null) {
            team = board.createTeam(name);
            state.lastJson = null;
        }
        if (board.getPlayersTeam(player.getScoreboardName()) != team) {
            board.addPlayerToTeam(player.getScoreboardName(), team);
        }
        if (render || state.lastJson == null) {
            ITextComponent prefix = RankRenderer.leftPrefix(active.rank, phase());
            String json = ITextComponent.Serializer.toJson(prefix);
            if (!json.equals(state.lastJson)) {
                team.setPrefix(prefix);
                state.lastJson = json;
            }
        }
    }

    private void applyLabel(ServerPlayerEntity player, State state, RankService.Active active,
                            RankRules.Position position, boolean render) {
        if (!player.isAlive() || player.isSpectator()) {
            removeChain(state);
            return;
        }
        if (state.waterHold && !player.isInWater()) {
            state.waterHold = false;
        }
        if (!chainValid(player, state)) {
            boolean broken = !state.chain.isEmpty() && state.vehicle == player;
            removeChain(state);
            if (broken && player.isInWater()) {
                state.waterHold = true; // vanilla drops passengers under water; retry once out of it
            }
            if (state.waterHold) {
                return;
            }
            spawnChain(player, state, position);
            render = true;
        }
        if (state.chain.isEmpty()) {
            return;
        }
        Entity label = state.chain.get(state.chain.size() - 1);
        if (render || state.lastJson == null) {
            ITextComponent tag = RankRenderer.tag(active.rank, phase());
            String json = ITextComponent.Serializer.toJson(tag);
            if (!json.equals(state.lastJson)) {
                label.setCustomName(tag);
                state.lastJson = json;
            }
        }
        boolean hidden = player.isSneaking() || player.isInvisible();
        if (hidden != state.hidden || label.isCustomNameVisible() == hidden) {
            label.setCustomNameVisible(!hidden);
            state.hidden = hidden;
        }
    }

    private boolean chainValid(ServerPlayerEntity player, State state) {
        if (state.chain.isEmpty() || state.vehicle != player) {
            return false;
        }
        Entity below = player;
        for (Entity e : state.chain) {
            if (!e.isAlive() || e.world != player.world || e.getRidingEntity() != below) {
                return false;
            }
            below = e;
        }
        return true;
    }

    private void spawnChain(ServerPlayerEntity player, State state, RankRules.Position position) {
        ServerWorld world = player.getServerWorld();
        List<Entity> chain = new ArrayList<>();
        if (position == RankRules.Position.ABOVE) {
            chain.add(cloud(world, player));
            chain.add(cloud(world, player));
        }
        chain.add(marker(world, player));
        Entity below = player;
        for (Entity e : chain) {
            if (e == null) {
                for (Entity created : chain) {
                    discard(created);
                }
                return;
            }
        }
        for (Entity e : chain) {
            ownEntities.add(e.getUniqueID());
            if (!world.addEntity(e)) {
                ownEntities.remove(e.getUniqueID());
                for (Entity created : chain) {
                    discard(created);
                }
                return;
            }
            e.startRiding(below, true);
            below = e;
        }
        state.chain.addAll(chain);
        state.vehicle = player;
        state.lastJson = null;
        state.hidden = false;
    }

    private static ArmorStandEntity marker(ServerWorld world, ServerPlayerEntity player) {
        ArmorStandEntity stand = EntityType.ARMOR_STAND.create(world);
        if (stand == null) {
            return null;
        }
        stand.setPosition(player.getPosX(), player.getPosY() + 1.85, player.getPosZ());
        CompoundNBT nbt = stand.writeWithoutTypeId(new CompoundNBT());
        nbt.putBoolean("Marker", true);
        nbt.putBoolean("Invisible", true);
        nbt.putBoolean("NoGravity", true);
        nbt.putBoolean("Invulnerable", true);
        nbt.putBoolean("Silent", true);
        nbt.putBoolean("NoBasePlate", true);
        nbt.putInt("DisabledSlots", 0x1F1F1F);
        stand.read(nbt);
        stand.addTag(ENTITY_TAG);
        stand.setCustomNameVisible(true);
        return stand;
    }

    private static AreaEffectCloudEntity cloud(ServerWorld world, ServerPlayerEntity player) {
        AreaEffectCloudEntity cloud = EntityType.AREA_EFFECT_CLOUD.create(world);
        if (cloud == null) {
            return null;
        }
        cloud.setPosition(player.getPosX(), player.getPosY() + 1.35, player.getPosZ());
        cloud.setRadius(0.0F);
        cloud.setWaitTime(0);
        cloud.setDuration(Integer.MAX_VALUE);
        cloud.setInvulnerable(true);
        cloud.setSilent(true);
        cloud.addTag(ENTITY_TAG);
        return cloud;
    }

    private void discard(Entity e) {
        if (e != null) {
            ownEntities.remove(e.getUniqueID());
            e.stopRiding();
            e.remove();
        }
    }

    private void removeChain(State state) {
        for (int i = state.chain.size() - 1; i >= 0; i--) {
            discard(state.chain.get(i));
        }
        state.chain.clear();
        state.vehicle = null;
        state.lastJson = null;
    }

    /** Removes everything shown for this player (labels and team). */
    public void clear(ServerPlayerEntity player) {
        State state = states.get(player.getUniqueID());
        if (state != null) {
            removeChain(state);
            state.mode = null;
            state.lastJson = null;
        }
        ServerScoreboard board = core.server().getScoreboard();
        ScorePlayerTeam team = board.getTeam(teamName(player.getUniqueID()));
        if (team != null) {
            board.removeTeam(team);
        }
    }

    // ------------------------------------------------------------------ events

    public void onLogout(ServerPlayerEntity player) {
        clear(player);
        states.remove(player.getUniqueID());
    }

    /** Death, respawn or dimension change: labels are rebuilt on the next refresh. */
    public void onPlayerChanged(ServerPlayerEntity player) {
        State state = states.get(player.getUniqueID());
        if (state != null) {
            removeChain(state);
        }
        dirty = true;
    }

    /** True if a label entity from an earlier session (or a copy) tries to join a world. */
    public boolean isStray(Entity entity) {
        return entity.getTags().contains(ENTITY_TAG) && !ownEntities.contains(entity.getUniqueID());
    }
}
