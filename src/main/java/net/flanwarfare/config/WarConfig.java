package net.flanwarfare.config;

import net.flanwarfare.FlanWarfare;
import net.flanwarfare.logic.ClockMath;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Server configuration, stored per world in {@code <world>/serverconfig/flanwarfare-server.toml}.
 * Forge reloads the file automatically when it changes on disk; {@code /war admin reload} also
 * re-validates it. Every value is read live, so changes apply without a restart.
 */
public final class WarConfig {

    public enum RaidMode { DISABLED, ALWAYS, CUSTOM }

    public static final ForgeConfigSpec SPEC;

    // raid
    public static final ForgeConfigSpec.EnumValue<RaidMode> RAID_MODE;
    public static final ForgeConfigSpec.ConfigValue<String> RAID_START;
    public static final ForgeConfigSpec.ConfigValue<String> RAID_END;
    public static final ForgeConfigSpec.BooleanValue ALLOW_BREAKING;
    public static final ForgeConfigSpec.BooleanValue ALLOW_PLACING;
    public static final ForgeConfigSpec.BooleanValue ALLOW_CONTAINERS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_INTERACTIONS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_BUCKETS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXTRA_RAID_PERMISSIONS;
    public static final ForgeConfigSpec.BooleanValue ANNOUNCE_TRANSITIONS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> WARNING_SECONDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DISABLED_DIMENSIONS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_CLAIMS;

    // raid.world_effects (security sensitive)
    public static final ForgeConfigSpec.BooleanValue ALLOW_EXPLOSIONS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_FIRE_SPREAD;
    public static final ForgeConfigSpec.BooleanValue ALLOW_PISTONS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_FLUID_FLOW;
    public static final ForgeConfigSpec.BooleanValue ALLOW_FAKE_PLAYERS;

    // flags
    public static final ForgeConfigSpec.BooleanValue REQUIRE_FLAG_FOR_PROTECTION;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_FLAG_FOR_MANAGEMENT;
    public static final ForgeConfigSpec.IntValue PLACEMENT_GRACE_SECONDS;
    public static final ForgeConfigSpec.IntValue REMINDER_INTERVAL_SECONDS;
    public static final ForgeConfigSpec.IntValue INACTIVE_EXPIRATION_SECONDS;
    public static final ForgeConfigSpec.IntValue EXPIRATION_WARNING_SECONDS;
    public static final ForgeConfigSpec.BooleanValue AUTO_ABANDON_EXPIRED;
    public static final ForgeConfigSpec.IntValue MOVEMENT_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue MIN_FLAG_DISTANCE;
    public static final ForgeConfigSpec.ConfigValue<String> DEFAULT_BANNER_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> RESTORE_SUPPORT_BLOCK;
    public static final ForgeConfigSpec.IntValue RESTORE_CHECK_SECONDS;
    public static final ForgeConfigSpec.BooleanValue PROTECT_FLAGS;

    // capture
    public static final ForgeConfigSpec.IntValue CAPTURE_SECONDS;
    public static final ForgeConfigSpec.DoubleValue CAPTURE_RADIUS;
    public static final ForgeConfigSpec.IntValue DECAY_SECONDS;
    public static final ForgeConfigSpec.BooleanValue CONTEST_BY_DEFENDERS;
    public static final ForgeConfigSpec.BooleanValue CONTEST_BY_RIVALS;
    public static final ForgeConfigSpec.BooleanValue CREATIVE_CAN_CAPTURE;
    public static final ForgeConfigSpec.IntValue BOSSBAR_DISTANCE;
    public static final ForgeConfigSpec.IntValue CHECK_INTERVAL_TICKS;

    // conquest (security sensitive)
    public static final ForgeConfigSpec.BooleanValue ALLOW_CONQUERED_RESIZE;
    public static final ForgeConfigSpec.IntValue MAX_CONQUERED_PER_PLAYER;
    public static final ForgeConfigSpec.BooleanValue USE_FTB_TEAMS;
    public static final ForgeConfigSpec.BooleanValue DEFENDERS_INCLUDE_MANAGERS;

    // general
    public static final ForgeConfigSpec.BooleanValue PREVENT_SLEEP_SKIP;
    public static final ForgeConfigSpec.BooleanValue AUDIT_LOGGING;
    public static final ForgeConfigSpec.IntValue ADMIN_PERMISSION_LEVEL;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Raid schedule and what attackers may do inside ordinary player claims during raids.",
                "Clock: Minecraft day = 24000 ticks, tick 0 = 06:00. 1 in-game hour = 1000 ticks.",
                "Examples: 06:00=0, 12:00=6000, 15:00=9000, 18:00=12000, 00:00=18000, 05:00=23000.",
                "The Overworld's day time is the authoritative clock for every enabled dimension.")
                .push("raid");
        RAID_MODE = b.comment("DISABLED: never. ALWAYS: raidable continuously. CUSTOM: only between start_time and end_time.")
                .defineEnum("mode", RaidMode.CUSTOM);
        RAID_START = b.comment("Raid window start, in-game HH:MM (24h). Windows may cross midnight.")
                .define("start_time", "15:00", WarConfig::isClock);
        RAID_END = b.comment("Raid window end (exclusive), in-game HH:MM. Must differ from start_time.")
                .define("end_time", "06:00", WarConfig::isClock);
        ALLOW_BREAKING = b.comment("Attackers may break blocks (Flan BREAK).").define("allow_breaking", true);
        ALLOW_PLACING = b.comment("Attackers may place blocks (Flan PLACE).").define("allow_placing", true);
        ALLOW_CONTAINERS = b.comment("Attackers may open and loot containers (Flan OPENCONTAINER).")
                .define("allow_containers", true);
        ALLOW_INTERACTIONS = b.comment("Attackers may use doors, trapdoors, gates, buttons, levers, pressure plates, generic blocks.")
                .define("allow_interactions", false);
        ALLOW_BUCKETS = b.comment("Attackers may use buckets (Flan BUCKET).").define("allow_buckets", false);
        EXTRA_RAID_PERMISSIONS = b.comment("Any other Flan permission IDs to open during raids, e.g. [\"ANVIL\", \"BED\"].",
                "Management permissions (EDITCLAIM, EDITPERMS, EDITPOTIONS) are always ignored here.")
                .defineList("extra_permissions", Collections.<String>emptyList(), o -> o instanceof String);
        ANNOUNCE_TRANSITIONS = b.comment("Broadcast raid start/end and advance warnings.")
                .define("announce_transitions", true);
        WARNING_SECONDS = b.comment("Advance warnings before a raid starts, in real seconds at normal day speed.")
                .defineList("warning_seconds", Arrays.asList(60, 10), o -> o instanceof Integer && (Integer) o > 0);
        DISABLED_DIMENSIONS = b.comment("Dimensions where raiding, capture and flag rules never apply, e.g. [\"minecraft:the_end\"].",
                "Claims there keep ordinary Flan behaviour.")
                .defineList("disabled_dimensions", Collections.<String>emptyList(), o -> o instanceof String);
        EXCLUDED_CLAIMS = b.comment("Flan claim UUIDs that are never raidable or capturable and need no flag.")
                .defineList("excluded_claims", Collections.<String>emptyList(), o -> o instanceof String);

        b.comment("SECURITY SENSITIVE. World effects without a player during raids. All off by default.",
                "Registered flags and their support block stay protected from explosions regardless.")
                .push("world_effects");
        ALLOW_EXPLOSIONS = b.define("allow_explosions", false);
        ALLOW_FIRE_SPREAD = b.define("allow_fire_spread", false);
        ALLOW_PISTONS = b.comment("Pistons pushing across the claim border.").define("allow_pistons", false);
        ALLOW_FLUID_FLOW = b.comment("Fluids flowing across the claim border.").define("allow_fluid_flow", false);
        ALLOW_FAKE_PLAYERS = b.comment("Fake players / modded machines acting for non-members (Flan FAKEPLAYER).")
                .define("allow_fake_players", false);
        b.pop();
        b.pop();

        b.comment("Claim flags and mandatory activation.").push("flags");
        REQUIRE_FLAG_FOR_PROTECTION = b.comment("Claims without a placed flag give no protection against players.")
                .define("require_flag_for_protection", true);
        REQUIRE_FLAG_FOR_MANAGEMENT = b.comment("Claims without a placed flag cannot be managed (trust, permissions, resize, subclaims, home, name, transfer).")
                .define("require_flag_for_management", true);
        PLACEMENT_GRACE_SECONDS = b.comment("Reminder period after a claim is created. Expiry of the grace period does NOT activate the claim.")
                .defineInRange("placement_grace_seconds", 600, 0, 86400 * 7);
        REMINDER_INTERVAL_SECONDS = b.defineInRange("reminder_interval_seconds", 120, 10, 86400);
        INACTIVE_EXPIRATION_SECONDS = b.comment("Maximum lifetime of a claim that never received a flag. 0 = never expires.")
                .defineInRange("inactive_claim_expiration_seconds", 86400, 0, 86400 * 60);
        EXPIRATION_WARNING_SECONDS = b.comment("Warn the owner this long before an inactive claim expires.")
                .defineInRange("expiration_warning_seconds", 3600, 0, 86400 * 7);
        AUTO_ABANDON_EXPIRED = b.comment("SECURITY SENSITIVE. If true, expired inactive claims are deleted through Flan's own deletion.",
                "If false, they are listed for admins in /war admin status and remain inactive.")
                .define("auto_abandon_expired_claims", false);
        MOVEMENT_COOLDOWN_SECONDS = b.comment("Cooldown between /flag move uses. Moving is always forbidden during raids.")
                .defineInRange("movement_cooldown_seconds", 300, 0, 86400 * 7);
        MIN_FLAG_DISTANCE = b.comment("Minimum distance between two registered flags, in blocks.")
                .defineInRange("min_flag_distance", 4, 2, 64);
        DEFAULT_BANNER_COLOR = b.comment("Banner colour used when a player has no saved design.")
                .define("default_banner_color", "white");
        RESTORE_SUPPORT_BLOCK = b.comment("Block placed under a flag during restoration if its support vanished.")
                .define("restore_support_block", "minecraft:cobblestone");
        RESTORE_CHECK_SECONDS = b.comment("How often loaded flags are checked and restored.")
                .defineInRange("restore_check_seconds", 5, 1, 300);
        PROTECT_FLAGS = b.define("protect_flags", true);
        b.pop();

        b.comment("Territory capture.").push("capture");
        CAPTURE_SECONDS = b.defineInRange("duration_seconds", 180, 5, 3600);
        CAPTURE_RADIUS = b.comment("Horizontal radius around the flag, in blocks.").defineInRange("radius_blocks", 2.0, 1.0, 16.0);
        DECAY_SECONDS = b.comment("Time for a full bar to decay to zero.").defineInRange("decay_seconds", 60, 1, 3600);
        CONTEST_BY_DEFENDERS = b.define("contest_by_defenders", true);
        CONTEST_BY_RIVALS = b.define("contest_by_rival_attackers", true);
        CREATIVE_CAN_CAPTURE = b.comment("Whether creative-mode players count as attackers or defenders.")
                .define("creative_players_count", false);
        BOSSBAR_DISTANCE = b.comment("Players within this distance of a capture see its boss bar.")
                .defineInRange("bossbar_view_distance", 24, 4, 128);
        CHECK_INTERVAL_TICKS = b.comment("How often capture areas are evaluated, in ticks.")
                .defineInRange("check_interval_ticks", 10, 2, 40);
        b.pop();

        b.comment("SECURITY SENSITIVE. Conquest rules.").push("conquest");
        ALLOW_CONQUERED_RESIZE = b.comment("Allow resizing conquered claims (they would remain free). Default: prohibited.")
                .define("allow_conquered_claim_resize", false);
        MAX_CONQUERED_PER_PLAYER = b.comment("Maximum conquered claims one player may own. -1 = unlimited.",
                "A capture that would exceed it is not completed.")
                .defineInRange("max_conquered_claims_per_player", -1, -1, 10000);
        USE_FTB_TEAMS = b.comment("Use FTB Teams parties as attacking sides and as defenders of their members' claims.",
                "Without FTB Teams (or when false) every player is their own side.")
                .define("use_ftb_teams", true);
        DEFENDERS_INCLUDE_MANAGERS = b.comment("Flan members whose group grants EDITCLAIM or EDITPERMS defend the claim.",
                "Members with lesser trust (e.g. container access only) never count as defenders.")
                .define("defenders_include_flan_managers", true);
        b.pop();

        b.push("general");
        PREVENT_SLEEP_SKIP = b.comment("Sleeping cannot skip time into or through an active raid window.")
                .define("prevent_sleep_time_skip_during_raids", true);
        AUDIT_LOGGING = b.comment("Log conquests, activations, relocations and admin actions to the server log.")
                .define("enable_audit_logging", true);
        ADMIN_PERMISSION_LEVEL = b.defineInRange("admin_permission_level", 2, 1, 4);
        b.pop();

        SPEC = b.build();
    }

    private WarConfig() {
    }

    private static boolean isClock(Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        try {
            ClockMath.parseClock((String) value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static volatile int cachedStart = -1;
    private static volatile int cachedEnd = -1;
    private static volatile String cachedStartText;
    private static volatile String cachedEndText;

    public static int raidStartTick() {
        refreshClock();
        return cachedStart;
    }

    public static int raidEndTick() {
        refreshClock();
        return cachedEnd;
    }

    private static void refreshClock() {
        String start = RAID_START.get();
        String end = RAID_END.get();
        if (!start.equals(cachedStartText) || !end.equals(cachedEndText)) {
            cachedStart = ClockMath.parseClock(start);
            cachedEnd = ClockMath.parseClock(end);
            cachedStartText = start;
            cachedEndText = end;
        }
    }

    /** Validates cross-field constraints; returns a list of problems (empty when valid). */
    public static List<String> validate() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (RAID_MODE.get() == RaidMode.CUSTOM && raidStartTick() == raidEndTick()) {
            problems.add("raid.start_time equals raid.end_time: the CUSTOM window is empty, so raiding never happens");
        }
        return problems;
    }

    public static void onConfigEvent(ModConfig.ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            cachedStartText = null;
            for (String problem : validate()) {
                FlanWarfare.LOG.warn("Config problem: {}", problem);
            }
        }
    }
}
