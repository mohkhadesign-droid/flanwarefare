package net.flanwarfare.raid;

import io.github.flemmli97.flan.api.permission.ClaimPermission;
import io.github.flemmli97.flan.api.permission.PermissionRegistry;
import net.flanwarfare.FlanWarfare;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.config.WarConfig.RaidMode;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.ClockMath;
import net.flanwarfare.util.Msg;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Raid windows, always derived from the Overworld's current day time. Nothing is counted
 * independently, so {@code /time set}, {@code /time add}, sleeping, restarts and mods that move
 * time are all reflected on the next tick.
 */
public final class RaidScheduler {
    private final WarCore core;
    private boolean lastActive;
    private long lastUntilStart = Long.MAX_VALUE;
    private Set<ClaimPermission> raidPermissions = Collections.emptySet();
    private long permissionsBuiltAt = Long.MIN_VALUE;

    public RaidScheduler(WarCore core) {
        this.core = core;
    }

    private ServerWorld overworld() {
        return core.server().getWorld(World.OVERWORLD);
    }

    /** Current absolute Overworld day time. */
    public long dayTime() {
        ServerWorld world = overworld();
        return world == null ? 0L : world.getDayTime();
    }

    public boolean isActive() {
        RaidMode mode = WarConfig.RAID_MODE.get();
        if (mode == RaidMode.ALWAYS) {
            return true;
        }
        if (mode == RaidMode.DISABLED) {
            return false;
        }
        return ClockMath.inWindowAbsolute(dayTime(), WarConfig.raidStartTick(), WarConfig.raidEndTick());
    }

    /**
     * Identifier of the current raid window; capture progress from a different window is never
     * reused. ALWAYS mode has a single window for the lifetime of the server process (capture
     * sessions are not persisted across restarts anyway).
     */
    public long windowId() {
        RaidMode mode = WarConfig.RAID_MODE.get();
        if (mode == RaidMode.ALWAYS) {
            return 0L;
        }
        if (mode == RaidMode.CUSTOM && isActive()) {
            return ClockMath.windowStart(dayTime(), WarConfig.raidStartTick(), WarConfig.raidEndTick());
        }
        return Long.MIN_VALUE;
    }

    /** Ticks until the next transition, or -1 if none is scheduled (DISABLED/ALWAYS). */
    public long ticksUntilChange() {
        if (WarConfig.RAID_MODE.get() != RaidMode.CUSTOM) {
            return -1;
        }
        long t = ClockMath.ticksUntilChange(dayTime(), WarConfig.raidStartTick(), WarConfig.raidEndTick());
        return t == Long.MAX_VALUE ? -1 : t;
    }

    public void initialise() {
        lastActive = isActive();
        lastUntilStart = lastActive ? Long.MAX_VALUE : ticksUntilChange();
        FlanWarfare.LOG.info("Raid state at startup: {}", statusLine());
    }

    public void tick() {
        boolean active = isActive();
        if (active != lastActive) {
            lastActive = active;
            core.audit("Raid window {} at Overworld time {}", active ? "opened" : "closed",
                    ClockMath.formatClock(dayTime()));
            if (WarConfig.ANNOUNCE_TRANSITIONS.get()) {
                Msg.broadcast(core.server(), active
                        ? Msg.error("RAIDING HAS BEGUN. Player claims can be raided and their flags captured.")
                        : Msg.ok("Raiding has ended. Claim protection is restored."));
            }
        }
        if (!active && WarConfig.RAID_MODE.get() == RaidMode.CUSTOM && WarConfig.ANNOUNCE_TRANSITIONS.get()) {
            long until = ticksUntilChange();
            if (until >= 0) {
                List<? extends Integer> warnings = WarConfig.WARNING_SECONDS.get();
                for (Integer seconds : warnings) {
                    long threshold = seconds * 20L;
                    if (lastUntilStart > threshold && until <= threshold && until > 0) {
                        Msg.broadcast(core.server(), Msg.warn("Raiding begins in about " + Msg.duration(until * 50L) + "."));
                        break;
                    }
                }
            }
            lastUntilStart = until;
        } else {
            lastUntilStart = Long.MAX_VALUE;
        }
    }

    /** Flan permissions opened to non-members during raids. Management is never included. */
    public boolean isRaidPermission(ClaimPermission permission) {
        if (core.ticks() - permissionsBuiltAt >= 100 || permissionsBuiltAt == Long.MIN_VALUE) {
            raidPermissions = buildPermissions();
            permissionsBuiltAt = core.ticks();
        }
        return raidPermissions.contains(permission);
    }

    private Set<ClaimPermission> buildPermissions() {
        Set<ClaimPermission> set = new HashSet<>();
        if (WarConfig.ALLOW_ENTERING.get()) {
            set.add(PermissionRegistry.CANSTAY);
        }
        if (WarConfig.ALLOW_BREAKING.get()) {
            set.add(PermissionRegistry.BREAK);
        }
        if (WarConfig.ALLOW_PLACING.get()) {
            set.add(PermissionRegistry.PLACE);
        }
        if (WarConfig.ALLOW_CONTAINERS.get()) {
            set.add(PermissionRegistry.OPENCONTAINER);
        }
        if (WarConfig.ALLOW_DOORS.get()) {
            set.add(PermissionRegistry.DOOR);
            set.add(PermissionRegistry.TRAPDOOR);
            set.add(PermissionRegistry.FENCEGATE);
        }
        if (WarConfig.ALLOW_REDSTONE.get()) {
            set.add(PermissionRegistry.BUTTONLEVER);
            set.add(PermissionRegistry.PRESSUREPLATE);
            set.add(PermissionRegistry.REDSTONE);
            set.add(PermissionRegistry.TARGETBLOCK);
        }
        if (WarConfig.ALLOW_BLOCK_INTERACTIONS.get()) {
            set.add(PermissionRegistry.INTERACTBLOCK);
            set.add(PermissionRegistry.ANVIL);
            set.add(PermissionRegistry.ENCHANTMENTTABLE);
            set.add(PermissionRegistry.BEACON);
            set.add(PermissionRegistry.LECTERNTAKE);
            set.add(PermissionRegistry.NOTEBLOCK);
            set.add(PermissionRegistry.JUKEBOX);
        }
        if (WarConfig.ALLOW_PROJECTILES_AND_PEARLS.get()) {
            set.add(PermissionRegistry.PROJECTILES);
            set.add(PermissionRegistry.ENDERPEARL);
            set.add(PermissionRegistry.CHORUSFRUIT);
            set.add(PermissionRegistry.TRAMPLE);
        }
        if (WarConfig.ALLOW_ENTITY_DAMAGE.get()) {
            set.add(PermissionRegistry.HURTANIMAL);
            set.add(PermissionRegistry.BREAKNONLIVING);
            set.add(PermissionRegistry.ARMORSTAND);
            set.add(PermissionRegistry.ITEMFRAMEROTATE);
        }
        if (WarConfig.ALLOW_PVP.get()) {
            set.add(PermissionRegistry.HURTPLAYER);
        }
        if (WarConfig.ALLOW_BUCKETS.get()) {
            set.add(PermissionRegistry.BUCKET);
        }
        if (WarConfig.ALLOW_FAKE_PLAYERS.get()) {
            set.add(PermissionRegistry.FAKEPLAYER);
        }
        for (String id : WarConfig.EXTRA_RAID_PERMISSIONS.get()) {
            ClaimPermission permission = lookup(id);
            if (permission == null) {
                FlanWarfare.LOG.warn("raid.extra_permissions: unknown Flan permission '{}' ignored", id);
            } else if (!FlanBridge.isManagementPermission(permission)) {
                set.add(permission);
            }
        }
        set.remove(null);
        return set;
    }

    /**
     * Whether using this permission counts as a hostile raid action for live alerts. Movement
     * (pearls, chorus fruit, trampling, pressure plates), fighting and fake players do not:
     * walking through a claim or a fight is not "your base is being raided".
     */
    public static boolean isHostileAction(ClaimPermission permission) {
        return permission != PermissionRegistry.HURTPLAYER
                && permission != PermissionRegistry.CANSTAY
                && permission != PermissionRegistry.ENDERPEARL
                && permission != PermissionRegistry.CHORUSFRUIT
                && permission != PermissionRegistry.TRAMPLE
                && permission != PermissionRegistry.PRESSUREPLATE
                && permission != PermissionRegistry.PROJECTILES
                && permission != PermissionRegistry.FAKEPLAYER;
    }

    private static ClaimPermission lookup(String id) {
        try {
            return PermissionRegistry.get(id.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException unknown) {
            // Flan 1.7.2 throws a NullPointerException for unregistered IDs.
            return null;
        }
    }

    public String statusLine() {
        RaidMode mode = WarConfig.RAID_MODE.get();
        String clock = ClockMath.formatClock(dayTime());
        if (mode == RaidMode.DISABLED) {
            return "Raiding is disabled. (Overworld time " + clock + ")";
        }
        if (mode == RaidMode.ALWAYS) {
            return "Raiding is permanently active. (Overworld time " + clock + ")";
        }
        String window = WarConfig.RAID_START.get() + "-" + WarConfig.RAID_END.get();
        long until = ticksUntilChange();
        String next = until < 0 ? "never" : "in about " + Msg.duration(until * 50L);
        return (isActive() ? "Raid ACTIVE, ends " : "No raid, next begins ") + next
                + ". Window " + window + ", Overworld time " + clock + ".";
    }

    /** Clamps a night skip; see {@link ClockMath#clampSleepTarget}. */
    public long clampSleep(long now, long target) {
        RaidMode mode = WarConfig.RAID_MODE.get();
        if (mode == RaidMode.DISABLED) {
            return target;
        }
        if (mode == RaidMode.ALWAYS) {
            return now;
        }
        return ClockMath.clampSleepTarget(now, target, WarConfig.raidStartTick(), WarConfig.raidEndTick());
    }
}
