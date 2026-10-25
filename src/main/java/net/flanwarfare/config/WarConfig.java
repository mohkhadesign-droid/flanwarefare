package net.flanwarfare.config;

import net.flanwarfare.FlanWarfare;
import net.flanwarfare.logic.ClockMath;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Server configuration, stored per world in {@code <world>/serverconfig/flanwarfare-server.toml}.
 * Forge reloads the file automatically when it changes on disk; {@code /war admin reload} also
 * re-validates it. Every value is read live, so changes apply without a restart.
 *
 * <p>There is exactly one capture radius ({@code capture.radius_blocks}); the capture-zone aura
 * and the action-bar distance read the same value as the server-side capture detection.
 */
public final class WarConfig {

    public enum RaidMode { DISABLED, ALWAYS, CUSTOM }

    /** Which Flan members of a claim count as its defenders. */
    public enum DefenderMembers { MANAGERS, ALL_MEMBERS, NONE }

    public static final ForgeConfigSpec SPEC;

    /** Default capture duration, used by /war admin capturetime reset. */
    public static final int DEFAULT_CAPTURE_SECONDS = 90;

    // raid
    public static final ForgeConfigSpec.EnumValue<RaidMode> RAID_MODE;
    public static final ForgeConfigSpec.ConfigValue<String> RAID_START;
    public static final ForgeConfigSpec.ConfigValue<String> RAID_END;
    public static final ForgeConfigSpec.BooleanValue ANNOUNCE_TRANSITIONS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> WARNING_SECONDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DISABLED_DIMENSIONS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_CLAIMS;

    // raid.permissions
    public static final ForgeConfigSpec.BooleanValue ALLOW_BREAKING;
    public static final ForgeConfigSpec.BooleanValue ALLOW_PLACING;
    public static final ForgeConfigSpec.BooleanValue ALLOW_CONTAINERS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_DOORS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_REDSTONE;
    public static final ForgeConfigSpec.BooleanValue ALLOW_BLOCK_INTERACTIONS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_PROJECTILES_AND_PEARLS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_ENTITY_DAMAGE;
    public static final ForgeConfigSpec.BooleanValue ALLOW_PVP;
    public static final ForgeConfigSpec.BooleanValue ALLOW_BUCKETS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXTRA_RAID_PERMISSIONS;

    // raid.world_effects (security sensitive)
    public static final ForgeConfigSpec.BooleanValue ALLOW_TNT_EXPLOSIONS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_CREEPER_EXPLOSIONS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_OTHER_EXPLOSIONS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_MODDED_EXPLOSIONS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_EXPLOSION_ENTITY_DAMAGE;
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
    public static final ForgeConfigSpec.DoubleValue VERTICAL_TOLERANCE;
    public static final ForgeConfigSpec.IntValue DECAY_SECONDS;
    public static final ForgeConfigSpec.BooleanValue CONTEST_BY_RIVALS;
    public static final ForgeConfigSpec.BooleanValue CREATIVE_CAN_CAPTURE;
    public static final ForgeConfigSpec.IntValue BOSSBAR_DISTANCE;
    public static final ForgeConfigSpec.IntValue CHECK_INTERVAL_TICKS;

    // capture.defenders
    public static final ForgeConfigSpec.BooleanValue CONTEST_BY_DEFENDERS;
    public static final ForgeConfigSpec.BooleanValue OWNER_CAN_CONTEST;
    public static final ForgeConfigSpec.EnumValue<DefenderMembers> DEFENDER_MEMBERS;
    public static final ForgeConfigSpec.BooleanValue TEAM_MEMBERS_DEFEND;
    public static final ForgeConfigSpec.BooleanValue MEMBERS_CANNOT_CAPTURE;

    // member_notifications
    public static final ForgeConfigSpec.BooleanValue MEMBER_NOTICES;
    public static final ForgeConfigSpec.BooleanValue MEMBER_NOTICE_ADDED;
    public static final ForgeConfigSpec.BooleanValue MEMBER_NOTICE_REMOVED;
    public static final ForgeConfigSpec.BooleanValue MEMBER_NOTICE_CHANGED;
    public static final ForgeConfigSpec.BooleanValue MEMBER_NOTICE_OFFLINE;

    // capture_aura
    public static final ForgeConfigSpec.BooleanValue AURA_ENABLED;
    public static final ForgeConfigSpec.IntValue AURA_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue AURA_DISTANCE;
    public static final ForgeConfigSpec.IntValue AURA_POINTS;
    public static final ForgeConfigSpec.DoubleValue AURA_PARTICLE_SIZE;
    public static final ForgeConfigSpec.BooleanValue AURA_SHOW_IDLE;
    public static final ForgeConfigSpec.BooleanValue AURA_SHOW_ACTIVE;
    public static final ForgeConfigSpec.BooleanValue AURA_SHOW_CONTESTED;
    public static final ForgeConfigSpec.BooleanValue AURA_SHOW_IMMUNE;
    public static final ForgeConfigSpec.BooleanValue AURA_SHOW_COMPLETION;
    public static final ForgeConfigSpec.BooleanValue AURA_OUTSIDE_RAIDS;
    public static final ForgeConfigSpec.BooleanValue AURA_ACTIONBAR;
    public static final ForgeConfigSpec.ConfigValue<String> AURA_IDLE_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> AURA_ACTIVE_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> AURA_CONTESTED_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> AURA_IMMUNE_COLOR;

    // flagpole
    public static final ForgeConfigSpec.BooleanValue POLE_ENABLED;
    public static final ForgeConfigSpec.IntValue POLE_HEIGHT;
    public static final ForgeConfigSpec.ConfigValue<String> POLE_BLOCK;
    public static final ForgeConfigSpec.BooleanValue POLE_ANIMATED;
    public static final ForgeConfigSpec.IntValue POLE_MIDPOINT;
    public static final ForgeConfigSpec.BooleanValue POLE_REVERSE_ON_DECAY;

    // raid_notifications
    public static final ForgeConfigSpec.BooleanValue ALERTS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue ALERT_OWNER;
    public static final ForgeConfigSpec.BooleanValue ALERT_DEFENDER_MEMBERS;
    public static final ForgeConfigSpec.BooleanValue ALERT_TEAM_MEMBERS;
    public static final ForgeConfigSpec.BooleanValue ALERT_TITLE;
    public static final ForgeConfigSpec.BooleanValue ALERT_SOUND;
    public static final ForgeConfigSpec.BooleanValue ALERT_CHAT;
    public static final ForgeConfigSpec.BooleanValue ALERT_COORDINATES;
    public static final ForgeConfigSpec.IntValue ALERT_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.BooleanValue ALERT_CAPTURE_HIGH_PRIORITY;
    public static final ForgeConfigSpec.BooleanValue ALERT_OFFLINE_SUMMARY;
    public static final ForgeConfigSpec.ConfigValue<String> ALERT_RAID_SOUND;
    public static final ForgeConfigSpec.ConfigValue<String> ALERT_CAPTURE_SOUND;

    // conquest (security sensitive)
    public static final ForgeConfigSpec.BooleanValue ALLOW_CONQUERED_RESIZE;
    public static final ForgeConfigSpec.IntValue MAX_CONQUERED_PER_PLAYER;
    public static final ForgeConfigSpec.BooleanValue USE_FTB_TEAMS;
    public static final ForgeConfigSpec.DoubleValue RECAPTURE_IMMUNITY_HOURS;

    // leaderboard
    public static final ForgeConfigSpec.BooleanValue LB_ENABLED;
    public static final ForgeConfigSpec.BooleanValue LB_TRACK_DEFENSES;
    public static final ForgeConfigSpec.BooleanValue LB_KINGDOMS;
    public static final ForgeConfigSpec.BooleanValue LB_KINGDOMS_PARTIES_ONLY;
    public static final ForgeConfigSpec.BooleanValue LB_WAR_HISTORY;
    public static final ForgeConfigSpec.IntValue DEFENSE_MIN_PERCENT;
    public static final ForgeConfigSpec.IntValue DEFENSE_COOLDOWN_MINUTES;
    public static final ForgeConfigSpec.IntValue PAGE_SIZE;
    public static final ForgeConfigSpec.ConfigValue<String> SEASON_NAME;
    public static final ForgeConfigSpec.ConfigValue<String> TIME_ZONE;

    // general
    public static final ForgeConfigSpec.BooleanValue PREVENT_SLEEP_SKIP;
    public static final ForgeConfigSpec.BooleanValue AUDIT_LOGGING;
    public static final ForgeConfigSpec.IntValue ADMIN_PERMISSION_LEVEL;
    public static final ForgeConfigSpec.IntValue CONFIG_VERSION;

    /** Version of the defaults below; bumped whenever a default changes. */
    public static final int CURRENT_CONFIG_VERSION = 2;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Raid schedule. Raid rules never apply to Flan admin claims, excluded claims or disabled dimensions.",
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
        ANNOUNCE_TRANSITIONS = b.comment("Broadcast raid start/end and advance warnings.")
                .define("announce_transitions", true);
        WARNING_SECONDS = b.comment("Advance warnings before a raid starts, in real seconds at normal day speed.")
                .defineList("warning_seconds", Arrays.asList(60, 10), o -> o instanceof Integer && (Integer) o > 0);
        DISABLED_DIMENSIONS = b.comment("Dimensions where raiding, capture and flag rules never apply, e.g. [\"minecraft:the_end\"].")
                .defineList("disabled_dimensions", Collections.<String>emptyList(), o -> o instanceof String);
        EXCLUDED_CLAIMS = b.comment("Flan claim UUIDs that are never raidable or capturable and need no flag.")
                .defineList("excluded_claims", Collections.<String>emptyList(), o -> o instanceof String);

        b.comment("What players who are neither the owner nor a Flan member may do in ordinary player claims during raids.",
                "Each option maps to Flan 1.7.2 permission categories (listed per option). Flan decides container",
                "and machine access through the same categories, so there is no separate machine option: most modded",
                "machines are OPENCONTAINER (inventories) or INTERACTBLOCK (everything else).")
                .push("permissions");
        ALLOW_BREAKING = b.comment("Flan BREAK.").define("allow_breaking", true);
        ALLOW_PLACING = b.comment("Flan PLACE (includes placing TNT and temporary structures).").define("allow_placing", true);
        ALLOW_CONTAINERS = b.comment("Flan OPENCONTAINER: opening AND looting chests, barrels, furnaces, modded inventories and machine GUIs.",
                        "Flan has a single permission for opening and taking items.")
                .define("allow_containers", true);
        ALLOW_DOORS = b.comment("Flan DOOR, TRAPDOOR, FENCEGATE.").define("allow_doors_and_trapdoors", true);
        ALLOW_REDSTONE = b.comment("Flan BUTTONLEVER, PRESSUREPLATE, REDSTONE (repeaters/comparators), TARGETBLOCK.")
                .define("allow_redstone_interactions", true);
        ALLOW_BLOCK_INTERACTIONS = b.comment("Flan INTERACTBLOCK (generic fallback: crafting stations, most modded machines),",
                        "ANVIL, ENCHANTMENTTABLE, BEACON, LECTERNTAKE, NOTEBLOCK, JUKEBOX.")
                .define("allow_block_interactions", true);
        ALLOW_PROJECTILES_AND_PEARLS = b.comment("Flan PROJECTILES, ENDERPEARL, CHORUSFRUIT, TRAMPLE (getting in and shooting at things).")
                .define("allow_projectiles_and_pearls", true);
        ALLOW_ENTITY_DAMAGE = b.comment("Flan HURTANIMAL, BREAKNONLIVING (item frames, minecarts, boats), ARMORSTAND, ITEMFRAMEROTATE.")
                .define("allow_entity_damage", true);
        ALLOW_PVP = b.comment("Flan HURTPLAYER for everyone in raidable claims during raids, so raiders and defenders",
                        "can fight anywhere in the claim (melee, projectiles, and modded weapons that credit the attacker).",
                        "This never overrides the server-wide pvp=false setting or other mods' protections.")
                .define("allow_pvp_during_raids", true);
        ALLOW_BUCKETS = b.comment("Flan BUCKET (placing/taking fluids).").define("allow_buckets", false);
        EXTRA_RAID_PERMISSIONS = b.comment("Any other Flan permission IDs to open during raids, e.g. [\"BED\", \"BOAT\"].",
                "Management permissions (EDITCLAIM, EDITPERMS, EDITPOTIONS) are always ignored here.")
                .defineList("extra_permissions", Collections.<String>emptyList(), o -> o instanceof String);
        b.pop();

        b.comment("SECURITY SENSITIVE. World effects during raids, decided per affected block inside ordinary player claims.",
                "Explosions are filtered block by block, never cancelled as a whole: blocks in admin claims, wilderness",
                "rules, disabled dimensions and registered flags (plus the block under them) are kept regardless.",
                "Rules are evaluated when the explosion happens, so TNT lit during a raid that explodes after it ends",
                "does no claim damage.")
                .push("world_effects");
        ALLOW_TNT_EXPLOSIONS = b.comment("Primed TNT and TNT minecarts.").define("allow_tnt_explosions", true);
        ALLOW_CREEPER_EXPLOSIONS = b.define("allow_creeper_explosions", true);
        ALLOW_OTHER_EXPLOSIONS = b.comment("Other vanilla explosions (beds, respawn anchors, end crystals, fireballs, wither).")
                .define("allow_other_explosions", true);
        ALLOW_MODDED_EXPLOSIONS = b.comment("Explosions caused by a non-vanilla entity (modded bombs, missiles...).",
                        "Modded explosions without a source entity count as 'other'. Mods that destroy blocks without",
                        "Forge's explosion event are not covered.")
                .define("allow_modded_explosions", true);
        ALLOW_EXPLOSION_ENTITY_DAMAGE = b.comment("Explosions may hurt entities inside raidable claims during raids.",
                        "Flan 1.7.2 normally shields animals, villagers and other passive entities in claims from explosions;",
                        "true lifts that during raids. false keeps it and also shields players and monsters in raidable claims.")
                .define("allow_explosion_entity_damage", true);
        ALLOW_FIRE_SPREAD = b.comment("Flan FIRESPREAD.").define("allow_fire_spread", false);
        ALLOW_PISTONS = b.comment("Flan PISTONBORDER: pistons pushing across the claim border.").define("allow_pistons", false);
        ALLOW_FLUID_FLOW = b.comment("Flan WATERBORDER: fluids flowing across the claim border.").define("allow_fluid_griefing", false);
        ALLOW_FAKE_PLAYERS = b.comment("Flan FAKEPLAYER: fake players / modded machines acting for non-members.")
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
        MIN_FLAG_DISTANCE = b.comment("Minimum distance between two registered flags, in blocks (7 keeps 3-block capture zones apart).")
                .defineInRange("min_flag_distance", 7, 2, 64);
        DEFAULT_BANNER_COLOR = b.comment("Banner colour used when a player has no saved design.")
                .define("default_banner_color", "white");
        RESTORE_SUPPORT_BLOCK = b.comment("Block placed under a flag during restoration if its support vanished.")
                .define("restore_support_block", "minecraft:cobblestone");
        RESTORE_CHECK_SECONDS = b.comment("How often loaded flags are checked and restored.")
                .defineInRange("restore_check_seconds", 5, 1, 300);
        PROTECT_FLAGS = b.define("protect_flags", true);
        b.pop();

        b.comment("Territory capture.").push("capture");
        CAPTURE_SECONDS = b.comment("Seconds of uncontested capture time needed, for every flag. Contested time never counts.",
                        "Also changed in game with /war admin capturetime <seconds> (saved here). Running captures keep their percentage.")
                .defineInRange("duration_seconds", DEFAULT_CAPTURE_SECONDS, 5, 3600);
        CAPTURE_RADIUS = b.comment("THE capture radius: horizontal distance from the flag's centre, in blocks.",
                        "Used for capture detection, defender contesting, the particle aura and the action-bar distance.",
                        "Players also need to be between 1 block below and radius+1 blocks above the flag's base.")
                .defineInRange("radius_blocks", 3.0, 1.0, 16.0);
        VERTICAL_TOLERANCE = b.comment("Players must stand within this many blocks above or below the flagpole's base",
                        "(stops capturing from tunnels underneath or roofs above). Used by the aura readout too.")
                .defineInRange("vertical_tolerance_blocks", 3.0, 1.0, 16.0);
        DECAY_SECONDS = b.comment("Time for a full bar to decay to zero.").defineInRange("decay_seconds", 60, 1, 3600);
        CONTEST_BY_RIVALS = b.comment("Two different attacking sides inside the zone pause the capture.")
                .define("contest_by_rival_attackers", true);
        CREATIVE_CAN_CAPTURE = b.comment("Whether creative-mode players count as attackers or defenders.")
                .define("creative_players_count", false);
        BOSSBAR_DISTANCE = b.comment("Players within this distance of a capture see its boss bar.")
                .defineInRange("bossbar_view_distance", 24, 4, 128);
        CHECK_INTERVAL_TICKS = b.comment("How often capture zones are evaluated, in ticks (10 = twice per second).")
                .defineInRange("check_interval_ticks", 10, 2, 40);

        b.comment("Defenders pause a capture simply by standing inside the zone; they never need to fight.",
                "The capture stays paused until every eligible defender has left (or died, logged out, left the dimension).")
                .push("defenders");
        CONTEST_BY_DEFENDERS = b.define("enabled", true);
        OWNER_CAN_CONTEST = b.comment("The claim owner counts as a defender.").define("owner_can_contest", true);
        DEFENDER_MEMBERS = b.comment("Which Flan members of the claim are defenders:",
                        "MANAGERS = members whose group grants EDITCLAIM or EDITPERMS (someone trusted for one chest is not a defender),",
                        "ALL_MEMBERS = every trusted member, NONE = no members.")
                .defineEnum("trusted_defenders", DefenderMembers.MANAGERS);
        TEAM_MEMBERS_DEFEND = b.comment("Players in the owner's FTB Teams party are defenders (needs conquest.use_ftb_teams).")
                .define("team_members_defend", true);
        MEMBERS_CANNOT_CAPTURE = b.comment("Players authorized in the claim through Flan (members of the claim or any of its",
                        "subclaims) can never capture it while authorized. Removing them makes them eligible again at once.")
                .define("trusted_members_cannot_capture", true);
        b.pop();
        b.pop();

        b.comment("Visible capture zone: a particle ring around the flagpole's base, sent to EVERY player within",
                "visibility_distance_blocks (owner, defenders, raiders and neutral players alike). Client particle settings",
                "can still reduce particles; the action-bar readout is the fallback.").push("capture_aura");
        AURA_ENABLED = b.define("enabled", true);
        AURA_INTERVAL_TICKS = b.defineInRange("particle_interval_ticks", 10, 2, 100);
        AURA_DISTANCE = b.comment("Players within this distance of a flag see its ring.")
                .defineInRange("visibility_distance_blocks", 48, 4, 128);
        AURA_POINTS = b.comment("Particles per ring.").defineInRange("ring_points", 24, 8, 96);
        AURA_PARTICLE_SIZE = b.defineInRange("particle_size", 1.0, 0.25, 4.0);
        AURA_SHOW_IDLE = b.comment("Ring while nobody is capturing.").define("show_idle_aura", true);
        AURA_SHOW_ACTIVE = b.comment("Ring while the flag is being captured or decaying.").define("show_active_aura", true);
        AURA_SHOW_CONTESTED = b.comment("Ring while the capture is contested.").define("show_contested_aura", true);
        AURA_SHOW_IMMUNE = b.comment("Ring while the claim has recapture immunity.").define("show_immunity_aura", true);
        AURA_SHOW_COMPLETION = b.comment("Burst of particles when a claim is conquered.").define("show_completion_effect", true);
        AURA_OUTSIDE_RAIDS = b.comment("Also show idle rings outside raid windows (flags cannot be captured then).")
                .define("show_outside_raids", false);
        AURA_ACTIONBAR = b.comment("Action-bar hint near a capturable flag, e.g. 'Capture zone - 1.4 / 2.0 blocks'.")
                .define("show_distance_actionbar", true);
        AURA_IDLE_COLOR = b.comment("Colours as #RRGGBB.").define("idle_color", "#C8C8C8", WarConfig::isColor);
        AURA_ACTIVE_COLOR = b.define("active_color", "#FF2020", WarConfig::isColor);
        AURA_CONTESTED_COLOR = b.define("contested_color", "#FFD000", WarConfig::isColor);
        AURA_IMMUNE_COLOR = b.comment("Ring colour while the claim has recapture immunity.")
                .define("immune_color", "#3C8CFF", WarConfig::isColor);
        b.pop();

        b.comment("Elevated flagpoles. New and moved flags are built as a vertical pole with the banner hanging near the top.",
                "Existing flags keep their shape until moved or restored by an admin. Changing height affects new/moved flags only.")
                .push("flagpole");
        POLE_ENABLED = b.define("enabled", true);
        POLE_HEIGHT = b.comment("Banner height above the base; the pole is height+1 blocks tall (base included).",
                        "The banner can hang at this many positions, so taller poles give smoother animation.")
                .defineInRange("height_blocks", 4, 2, 12);
        POLE_BLOCK = b.comment("Block used for the pole. Must be a solid-material block banners can hang on (fences, walls, iron bars).")
                .define("pole_block", "minecraft:dark_oak_fence");
        POLE_ANIMATED = b.comment("Lower the owner's banner and raise the attacker's banner with capture progress (stepped, one block per step).")
                .define("animated_capture", true);
        POLE_MIDPOINT = b.comment("Progress at which the lowered banner switches to the attacker's design.")
                .defineInRange("animation_midpoint_percent", 50, 10, 90);
        POLE_REVERSE_ON_DECAY = b.comment("Run the animation backwards while progress decays (otherwise the banner waits, then resets at 0%).")
                .define("reverse_animation_on_decay", true);
        b.pop();

        b.comment("Tell players when a claim owner adds them to, removes them from, or changes their permissions in a",
                "Flan claim, through any Flan command or menu. Only committed changes are announced, and rapid edits",
                "(several clicks in the permission menu) are combined into one message after a few seconds.")
                .push("member_notifications");
        MEMBER_NOTICES = b.define("enabled", true);
        MEMBER_NOTICE_ADDED = b.define("notify_on_added", true);
        MEMBER_NOTICE_REMOVED = b.define("notify_on_removed", true);
        MEMBER_NOTICE_CHANGED = b.define("notify_on_permission_change", true);
        MEMBER_NOTICE_OFFLINE = b.comment("Deliver notices to offline players at their next login.")
                .define("queue_offline_notifications", true);
        b.pop();

        b.comment("Live alerts when a specific claim is attacked. These are separate from the raid-window announcements.",
                "Flan admin claims never produce alerts. Players can mute their own alerts with /war alerts off.")
                .push("raid_notifications");
        ALERTS_ENABLED = b.define("enabled", true);
        ALERT_OWNER = b.define("notify_owner", true);
        ALERT_DEFENDER_MEMBERS = b.comment("Flan members of the claim who are defenders (see capture.defenders.trusted_defenders).")
                .define("notify_trusted_defenders", true);
        ALERT_TEAM_MEMBERS = b.comment("Members of the owner's FTB Teams party.").define("notify_team_members", true);
        ALERT_TITLE = b.define("show_center_screen_title", true);
        ALERT_SOUND = b.define("play_warning_sound", true);
        ALERT_CHAT = b.define("show_chat_message", true);
        ALERT_COORDINATES = b.define("show_claim_coordinates", true);
        ALERT_COOLDOWN_SECONDS = b.comment("Minimum time between ordinary 'under attack' titles for the same claim.",
                        "Capture start, capture end and conquest are never suppressed by it.")
                .defineInRange("ordinary_raid_cooldown_seconds", 45, 5, 3600);
        ALERT_CAPTURE_HIGH_PRIORITY = b.comment("Capture start always shows a title, even within the cooldown.")
                .define("capture_start_high_priority", true);
        ALERT_OFFLINE_SUMMARY = b.comment("Remember attacks on offline owners' claims (bounded) and summarise them at login.")
                .define("offline_raid_summary", true);
        ALERT_RAID_SOUND = b.define("raid_alert_sound", "minecraft:block.bell.use");
        ALERT_CAPTURE_SOUND = b.define("capture_alert_sound", "minecraft:event.raid.horn");
        b.pop();

        b.comment("SECURITY SENSITIVE. Conquest rules.").push("conquest");
        ALLOW_CONQUERED_RESIZE = b.comment("Allow resizing conquered claims (they would remain free). Default: prohibited.")
                .define("allow_conquered_claim_resize", false);
        MAX_CONQUERED_PER_PLAYER = b.comment("Maximum conquered claims one player may own at once, all dimensions combined. -1 = unlimited.",
                        "Players at the limit cannot capture; a teammate can. Lowering it never removes claims.")
                .defineInRange("max_conquered_claims_per_player", 3, -1, 10000);
        RECAPTURE_IMMUNITY_HOURS = b.comment("Real-world hours after a conquest during which the claim cannot be captured again.",
                        "It can still be raided. Counts while the server is offline; Minecraft time has no effect.",
                        "Changing it affects future conquests only. 0 = no immunity.")
                .defineInRange("recapture_immunity_hours", 24.0, 0.0, 8760.0);
        USE_FTB_TEAMS = b.comment("Use FTB Teams parties as attacking sides. Without FTB Teams (or when false) every player is their own side.")
                .define("use_ftb_teams", true);
        b.pop();

        b.comment("Season statistics, leaderboards and war history. Statistics never change gameplay ownership.")
                .push("leaderboard");
        LB_ENABLED = b.comment("Record season events (conquests, defenses, losses) and enable the statistics commands.")
                .define("enabled", true);
        LB_TRACK_DEFENSES = b.define("track_successful_defenses", true);
        LB_KINGDOMS = b.comment("Kingdom rankings, based on FTB Teams.").define("track_kingdom_stats", true);
        LB_KINGDOMS_PARTIES_ONLY = b.comment("Only FTB Teams parties count as kingdoms; solo players have no kingdom.",
                        "If the team type cannot be detected, every team counts.")
                .define("kingdoms_are_parties_only", true);
        LB_WAR_HISTORY = b.comment("Enable /war log.").define("record_war_history", true);
        DEFENSE_MIN_PERCENT = b.comment("A repelled capture counts as a defense only if it reached this percentage.")
                .defineInRange("defense_minimum_capture_percent", 10, 1, 100);
        DEFENSE_COOLDOWN_MINUTES = b.comment("Anti-farming: at most one credited defense per claim within this many real minutes.")
                .defineInRange("defense_cooldown_minutes", 30, 0, 1440);
        PAGE_SIZE = b.defineInRange("default_page_size", 10, 3, 30);
        SEASON_NAME = b.comment("Name of the first season, created automatically on first start.",
                        "Later seasons are named with /war admin season start <name>.")
                .define("season_name", "The Grand Expedition - Season 1");
        TIME_ZONE = b.comment("Time zone for timestamps in /war log, e.g. \"America/New_York\". Empty = the server's zone.")
                .define("time_zone", "", WarConfig::isZone);
        b.pop();

        b.push("general");
        PREVENT_SLEEP_SKIP = b.comment("Sleeping cannot skip time into or through an active raid window.")
                .define("prevent_sleep_time_skip_during_raids", true);
        AUDIT_LOGGING = b.comment("Log conquests, activations, relocations and admin actions to the server log.")
                .define("enable_audit_logging", true);
        ADMIN_PERMISSION_LEVEL = b.defineInRange("admin_permission_level", 2, 1, 4);
        CONFIG_VERSION = b.comment("Managed by the mod; do not edit. Used to move values still at an old default to the new default.")
                .defineInRange("config_version", 0, 0, 1000);
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

    private static boolean isZone(Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String zone = ((String) value).trim();
        if (zone.isEmpty()) {
            return true;
        }
        try {
            java.time.ZoneId.of(zone);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static java.time.ZoneId zone() {
        String zone = TIME_ZONE.get().trim();
        return zone.isEmpty() ? java.time.ZoneId.systemDefault() : java.time.ZoneId.of(zone);
    }

    /** Recapture immunity length in milliseconds (0 = disabled). */
    public static long immunityMillis() {
        return (long) (RECAPTURE_IMMUNITY_HOURS.get() * 3_600_000.0);
    }

    private static boolean isColor(Object value) {
        return value instanceof String && ((String) value).matches("#[0-9A-Fa-f]{6}");
    }

    /** Parses "#RRGGBB" into {r, g, b} in 0..1. */
    public static float[] rgb(String hex) {
        int v = Integer.parseInt(hex.substring(1), 16);
        return new float[]{((v >> 16) & 0xFF) / 255f, ((v >> 8) & 0xFF) / 255f, (v & 0xFF) / 255f};
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

    /**
     * Forge keeps the values of an existing config file, so a file written by an older build
     * keeps old defaults (e.g. a 2-block radius) after updating. This moves every value that is
     * still exactly at an OLD default to the NEW default, once, and leaves values an admin
     * changed alone. Returns what was changed.
     */
    public static List<String> migrateDefaults() {
        List<String> changed = new ArrayList<>();
        int version = CONFIG_VERSION.get();
        if (version >= CURRENT_CONFIG_VERSION) {
            return changed;
        }
        migrate(changed, CAPTURE_SECONDS, 180, DEFAULT_CAPTURE_SECONDS, "capture.duration_seconds");
        migrate(changed, CAPTURE_RADIUS, 2.0, 3.0, "capture.radius_blocks");
        migrate(changed, MIN_FLAG_DISTANCE, 4, 7, "flags.min_flag_distance");
        migrate(changed, MAX_CONQUERED_PER_PLAYER, -1, 3, "conquest.max_conquered_claims_per_player");
        migrate(changed, AURA_DISTANCE, 24, 48, "capture_aura.visibility_distance_blocks");
        CONFIG_VERSION.set(CURRENT_CONFIG_VERSION);
        CONFIG_VERSION.save();
        return changed;
    }

    private static <T> void migrate(List<String> changed, ForgeConfigSpec.ConfigValue<T> value, T oldDefault, T newDefault,
                                    String name) {
        if (oldDefault.equals(value.get())) {
            value.set(newDefault);
            value.save();
            changed.add(name + ": " + oldDefault + " -> " + newDefault);
        }
    }

    /** Validates cross-field constraints; returns a list of problems (empty when valid). */
    public static List<String> validate() {
        List<String> problems = new ArrayList<>();
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
