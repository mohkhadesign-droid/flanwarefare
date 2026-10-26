# Flan Warfare & Conquest

Server-side Forge 1.16.5 add-on for **Flan 1.16.5-1.7.2** that adds scheduled raid windows,
mandatory claim flags, contested territory capture with a visible capture zone, live raid
alerts, permanent conquest with limits and recapture immunity, season leaderboards with a
war history, and custom kingdom ranks shown in chat and on nametags.

- Players do **not** install anything. The mod registers no blocks, items or network channels.
  Banners, particles, boss bars, titles and sounds are all vanilla.
- Flan stays in charge of claims, owners, boundaries, trust and claim blocks. This add-on never
  runs a second claim system; it decides permissions dynamically and uses Flan's own operations.
- Built and verified against the exact Flan JAR on your server (see `docs/TECHNICAL.md`).

> **Status:** builds on GitHub Actions against Forge 1.16.5 (compile, reobfuscation and 174
> automated logic checks). The in-game behaviour has **not** been tested on a server yet.
> Follow `docs/TESTING.md` on a copy of the server before using it on the live season.

---

## Installation

1. Download the JAR from the latest workflow run: **Actions → Build → flanwarfare-jar**.
2. Put the JAR (named like `flanwarfare-1.1.0-b7.jar`, where `b7` is the build number) in the
   **server's** `mods/` folder, next to Flan 1.16.5-1.7.2. Do not install it on clients. The mod
   refuses to load with any other Flan version.
3. Start the server once. The config appears at `world/serverconfig/flanwarfare-server.toml`.
4. Edit it, then save. Forge reloads it automatically; `/war admin reload` re-validates it.

### Updating

1. Stop the server.
2. **Delete the old flanwarfare JAR** from `mods/`, and keep only the new one. Two copies can't
   load together.
3. Start the server.
4. Check `/war status` or `/war admin status`. Both show the installed version, such as
   `1.1.0-b7`, plus the active capture radius and time. The server log prints the same line at
   startup.

Settings still at an old default are moved to the new default automatically, once, and listed
in the server log as "Config updated to the new default". Settings you changed yourself are
never touched.

**The one ambiguous case:** a value you set by hand to exactly the old default, such as
`radius_blocks = 2.0`, can't be told apart from an untouched default, so it is migrated. If you
want it, set it back once; it won't be changed again. Claims, flags, designs, conquests, immunity
and statistics are never touched by migration. Existing single-banner flags become flagpoles when moved (`/flag move`).

Data is stored in `world/data/flanwarfare/war_data.json` (atomic writes, previous version kept as
`war_data.json.bak`). Include it in backups together with `world/data/claims/`.

### Building

Every push runs `.github/workflows/build.yml`, which runs `./gradlew build` and uploads the JAR.
To build locally instead: JDK 8, then `./gradlew build`. The JAR is `build/libs/flanwarfare-1.1.0.jar`.

Flan itself is **not** in this repository (it is "All Rights Reserved"). The build compiles
against signature-only stubs in `src/flanstubs`, which were checked member by member against
the real Flan 1.7.2 JAR and are never packaged.

---

## How it plays

**New claims start inactive.** Right after creating a claim with Flan, it protects nothing and
cannot be managed (no trust, permissions, subclaims, resizing, renaming, home or transfer).
The owner stands inside it and runs `/flag place`. That builds a **flagpole** where they stand
and activates the claim. The pole is a dark oak fence column 5 blocks tall, with the owner's
banner hanging near the top, about 4 blocks up. The owner is moved one step aside. Reminders run for 10 minutes; after that the claim simply stays
inactive. Claims that never get a flag expire after 24 hours (listed for admins, or
automatically abandoned if `auto_abandon_expired_claims = true`).

### Night raids

Raid windows follow the Overworld clock (default 15:00-06:00 in-game). During a raid, anyone
who is not the owner or a Flan member of an ordinary claim can genuinely raid it:

- enter and stay in the claim, even if its owner turned off Flan's `CANSTAY`. Outside raids,
  `CANSTAY` works as normal, and entering never counts as an attack;
- break and place blocks, including placing TNT;
- open and loot chests, barrels, furnaces and modded inventories;
- use doors, trapdoors, gates, buttons, levers, pressure plates, crafting stations and machines;
- throw ender pearls and shoot projectiles;
- hurt animals and break item frames and armour stands;
- fight players anywhere in the claim, with melee, bows, crossbows and modded weapons that credit the
  attacker. Defenders can fight back too. PvP returns to normal when the raid ends, and the
  server-wide `pvp=false` setting is never overridden.

Explosions also damage raidable claims: TNT, creepers, other vanilla explosions and modded
explosions each have their own switch. They are filtered **block by block**. In an explosion
that overlaps an admin claim, only the raidable blocks are destroyed. Registered flags and the
block under them always survive.

The rules are checked at the moment something happens. When the raid ends, protection is back
instantly, and TNT lit during the raid that explodes afterwards does no claim damage. Nothing is
ever written to the claims themselves.

Admin claims (your spawn), excluded claims, disabled dimensions and other protection mods
(YAWP, WorldProtector) are never opened.

### Capture zone, capturing and defending

Every capturable flag shows a **ring of particles** on the ground: that is the capture zone,
3 blocks around the pole's base. Everyone within 48 blocks sees it: owner, defenders, raiders and
bystanders. Stand at ground level by the pole; you don't climb to the banner. Players more than
3 blocks above or below the base, on a roof or in a tunnel, don't count. The ring is drawn with the same radius the server uses to detect
players, from one shared setting. Its colour shows the state:

- grey: idle;
- red: being captured;
- yellow: contested.

A burst of particles marks a conquest. Players near the zone also get an action-bar readout,
e.g. `Capture Zone - 1.4 / 2.0 blocks from flag - INSIDE`.

During a raid, an enemy standing inside the zone fills a boss bar over **90 seconds** of
uncontested time. Time spent contested never counts. Admins can change this for every flag with
`/war admin capturetime <seconds>`. A capture already running keeps its percentage, so at 50%,
switching from 90 s to 120 s leaves about 60 s to go.

**The flag moves with the capture:**

- The owner's banner slides down the pole as progress climbs to 50%.
- At 50% it switches to the attacker's banner design. Ownership doesn't change.
- The attacker's banner climbs back up as progress reaches 100%.

A defender in the zone freezes it in place. If the capture drains away, the whole thing plays
backwards and the owner's banner ends up on top again.

The banner moves one block at a time. Minecraft 1.16.5 can only place banners at whole-block
heights without a client mod, so a 4-block pole gives 4 positions down and 4 back up.

- **Defending is just standing there.** As soon as the owner, or another defender, is inside the
  zone, progress pauses and the bar says **CONTESTED**. It stays paused until every defender has
  left, died, logged out or changed dimension. There's no need to fight the raider.
- Two rival attacking sides in the zone also pause it.
- If the attackers leave, progress drains over 60 seconds. When the raid ends, unfinished
  captures drain to zero even if attackers stay, and the next raid always starts fresh.

### Live raid alerts

When someone actually attacks your claim, you get a big centre-screen title, a warning sound and
a chat line naming the claim and its coordinates. A raid window opening on its own never
triggers this. Attacking means breaking, placing, looting, using your blocks, or an explosion
destroying its blocks.

- `YOUR BASE IS UNDER RAID!` appears for ordinary raiding. After the first title, a raider breaking
  50 blocks only updates your action bar. A new title can come after 45 seconds.
- `YOUR FLAG IS BEING CAPTURED!` appears immediately when an enemy is at your flag, even inside the
  cooldown. Then you get chat updates when the capture is contested, fails, or is cancelled.
- `YOUR CLAIM WAS CONQUERED!` appears if you lose it.

These alerts go to:

- the owner;
- the claim's defender members (Flan managers);
- the owner's FTB Teams party.

Each of those can be switched off. Anyone can mute their own alerts with `/war alerts off`.

If you were offline, you get a short summary at your next login. Admin claims never produce
alerts. An admin's *ordinary* claims alert normally.

### Conquest

At 100% the individual capturing player becomes the Flan owner of the claim. Boundaries,
buildings and items stay. All old members, groups, overrides, subclaims, name, titles and
potion effects are removed, and the home moves to the flag. The flag stays where it is and takes
the conqueror's banner design. Conquered land costs no claim blocks and no claim slot, and it
cannot be resized or transferred.

**Limit: 3 conquered claims per player** at once, all dimensions combined. A player at the limit
sees "Conquest Limit Reached!" and cannot capture, but a teammate standing with them can, and
the claim goes to that teammate. Losing, deleting or abandoning a conquered claim frees a slot.
`/war claims` shows your conquered claims, free slots and immunity. Lowering the limit never
removes anyone's claims.

**Recapture immunity: 24 real-world hours** after a conquest, nobody can capture that claim:

- The ring turns blue, and attackers stepping in see "This territory is immune to conquest.
  Recapture available in: 17h 42m".
- The owner gets no capture alert.
- The claim can still be raided normally: break, loot, TNT and PvP all work, and raid alerts
  still go out.

The timer runs on real time, so it keeps counting while the server is offline, and `/time` or
sleeping has no effect. It survives restarts and flag moves.

### Season leaderboard and war history

The mod keeps season statistics from actual conquests and defenses:

- **Successful conquests** count permanently, even if the claim is later lost.
- **Successful defenses** count only when all of these happen:
  - the capture reached at least 10%;
  - a defender stood in the zone while an attacker was there;
  - the attackers left or died;
  - the bar drained to zero without being captured.

  A raid ending with attackers still at the flag doesn't count, and one claim can earn at most
  one defense per 30 minutes. Every defender who contested gets personal credit, but their
  kingdom gets only one defense.
- **Territory held** (conquered and total) comes from current Flan ownership.
- **Territories lost** counts claims taken from you.

Kingdoms are FTB Teams parties. Each event remembers the kingdom at the time, so switching teams
later doesn't rewrite history. Use `/war leaderboard`, `/war stats` and `/war log` to view them
(commands below).

Seasons are started, ended and archived by admins. Archived seasons stay viewable with
`/war seasons`. Resetting or ending a season never touches claims, owners, slots, immunity or
flags.

### Managing a conquered claim, and member notices

The conqueror is the normal Flan owner. They use Flan's own menu and commands to trust players,
set groups and permissions, and create subclaims. They can invite anyone, including the previous
owner. The only things they can't do are resize the claim or transfer it.

Players hear about it whenever an owner adds them to a claim, removes them, or changes their
permissions through any Flan menu or command. This includes Claim-Menu → Edit Permissiongroups,
assigning players to groups, and toggling a group's permissions such as `CANSTAY`. The messages
say what actually changed for them, e.g. "You may now enter this claim." or "No longer allowed:
OPENCONTAINER". A change that leaves their effective permissions the same says nothing.

In Flan 1.7.2 a player belongs to exactly one group per claim, so moving someone to another group
is reported as a permission change, never as a removal. The notices are:

- "You have been added to Ali's claim: "Northern Fortress"…", with an action-bar line;
- "…removed from…";
- "Your permissions in … have been updated."

Only changes Flan actually saved are announced. Several clicks in a row become one message after
about 3 seconds, and a change that undoes itself says nothing.

Members of a claim that gets conquered are told their access ended. Offline players get their
notices at the next login.

### Kingdom ranks, chat tags and nametags

Each kingdom owner can design ranks such as **Royal Guard**, **High Commander** or **Scout** and
give them to their members. Ranks are purely cosmetic: they never grant Flan permissions,
ownership or operator rights. Flan's groups stay the only source of access.

1. Stand in your main claim and run `/war rank setmain`.
2. `/war rank create Royal Guard` (names may contain spaces).
3. Style it: `/war rank color "Royal Guard" gold`, `/war rank gradient "Royal Guard" #FFD700 #FF4500`,
   `/war rank effect "Royal Guard" wave`, `/war rank prefix "Royal Guard" ⚔`.
4. `/war rank assign Steve "Royal Guard"`. Steve must be a Flan member of the claim (or one of its
   subclaims). Public access doesn't count.
5. `/war rank preview "Royal Guard"` shows the tag, the nametag and a chat line.

Names with spaces go in quotes in later commands, or use the short id shown by `/war rank list`.

- **Colours:** the 16 Minecraft colour names or any `#RRGGBB`. Gradients use 2 to 5 colours.
- **Effects:** `none`/`static`, `bold`, `glow` (brighter and bold), `rainbow`, `pulse`, `wave`,
  and `obfuscated` (off by default because it's unreadable). Animated effects move on nametags;
  a chat line shows a single frame because chat can't change after it's sent.
- **Decorations:** a prefix and suffix of up to 4 characters, such as ⚔ ★ ♛ ✦. Formatting codes
  and invisible characters are refused, and admins can restrict the allowed symbols.
- **Where the tag appears:**
  - In **chat**, as `[Royal Guard] Steve: hello`. The format is configurable.
  - On the **nametag**:
    - `LEFT` shows "Royal Guard | Steve" and also appears in the tab list. This is the default.
    - `BELOW` shows the rank on its own line just under the name.
    - `ABOVE` shows it on its own line above the name.

    A rank has a default position. Each holder can change their own with
    `/war rank playerposition <you> left|above|below|reset`.
- **Membership is checked live.** If someone is removed from the claim in Flan, their rank is
  kept but hidden ("suspended") until they're a member again. A player holds one rank at a time.
  Only an admin can move a rank between kingdoms.
- **Conquest:** the current Flan owner of a main claim manages its ranks. If the claim is
  conquered, the conqueror takes over its rank list. They manage it while standing in it, and
  the previous owner loses control. If the claim is deleted, its ranks go with it.

The labels are vanilla features (scoreboard team prefixes and invisible marker entities riding
the player), so players still install nothing.

### Sides and defenders

- **Attacking side:** your FTB Teams team. Party members cooperate; players without a party
  are their own side. Without FTB Teams, every player is their own side.
- **Defenders of a claim:**
  - the owner;
  - players in the owner's FTB party;
  - Flan members whose group grants `EDITCLAIM` or `EDITPERMS`. This is configurable: managers,
    all members, or none.

  A member trusted for less (say, one chest) is neutral: they can neither defend nor capture
  that claim.
- **Players the owner trusted in Flan** keep exactly the permissions Flan gives them, raid or no
  raid. They never set off raid alerts, and they can't capture that claim while they're trusted.
  That covers members of the claim or of any of its subclaims, from any kingdom. The same goes
  for anything Flan already allows everyone in that claim. Remove someone in Flan and they're
  treated like any outsider straight away.
- **Who gets the claim:** the first player of the capturing side to reach the flag, while they
  stay. If they leave, it goes to the longest continuously present teammate, with ties broken by
  UUID. Never random.

---

## Player commands

| Command | What it does |
|---|---|
| `/flag place` | Place the flag of the claim you stand in and activate it. |
| `/flag move` | Move your flag to where you stand, inside the same claim. Owner only, never during raids, 5-minute cooldown. |
| `/flag info` | State (including live state such as RAID_ACTIVITY or CAPTURE_CONTESTED), flag position, conquest status, raid status. |
| `/flag abandon` / `/flag abandon confirm` | Delete one of your claims that never had a flag. |
| `/flag design set` | Save the banner in your main hand as your design. The banner is not consumed. |
| `/flag design apply` | Show your saved design on the flag of the claim you stand in. |
| `/flag design reset` | Go back to the default banner. |
| `/war status` | Whether a raid is active and when the next change happens. |
| `/war capture` | Captures on your claims or within 64 blocks. |
| `/war alerts on` / `off` | Unmute or mute live raid alerts for yourself. |
| `/war claims` | Your conquered claims, free conquest slots, and remaining immunity. |
| `/war leaderboard` `[players\|kingdoms\|defenses\|territory]` `[page]` | Season rankings (players by conquests, then defenses, then held). |
| `/war stats [player]` | Season statistics for you or another player. |
| `/war log [page]` | Recent war history (conquests, defenses, losses, reversals). |
| `/war log player <player>` / `claim` / `kingdom <kingdom>` `[page]` | History filtered by player, the claim you stand in, or kingdom. |
| `/war seasons [<id> [log [page]]]` | Archived seasons: final rankings and their war log. |
| `/war rank setmain` | Make the claim you stand in (yours) your kingdom's main claim for ranks. |
| `/war rank list` / `members [rank]` / `info [player]` / `preview <rank>` | Show ranks, holders (suspended ones marked), one player's rank and display, or a preview. |
| `/war rank create <name>` / `delete <rank>` / `rename <rank> <new name>` | Manage rank definitions (kingdom owner). |
| `/war rank color <rank> <color>` | Single colour: a name such as `gold` or `#RRGGBB`. |
| `/war rank gradient <rank> <c1> <c2> [...]` / `gradient <rank> clear` | Multi-colour gradient. |
| `/war rank effect <rank> <effect>` | `none`, `static`, `bold`, `glow`, `rainbow`, `pulse`, `wave`, `obfuscated`. |
| `/war rank position <rank> left\|above\|below\|default` | Default nametag placement for the rank. |
| `/war rank prefix <rank> <text>` / `suffix <rank> <text>` / `decoration <rank> clear` | Decorations around the name. |
| `/war rank assign <player> <rank>` / `remove <player>` | Give or take a rank (holder must be a Flan member of the kingdom). |
| `/war rank playerposition <player> left\|above\|below\|reset` | Personal placement (yourself, or members of your kingdom). |

**Flag placement rules:**

- Stand in open air, inside your claim, on a solid block. It can't be a falling block, a
  container or a machine.
- You need a clear 3x3 space, two blocks high, around you, fully inside the claim.
- No lava or fire may be adjacent.
- Other flags must be at least 4 blocks away.

After placement nobody can put blocks into that space, or break the flag or the block under it.
Attackers can always reach the flag by breaking through whatever surrounds the space.

## Administrator commands (permission level 2 by default)

| Command | What it does |
|---|---|
| `/war admin status` | Diagnostics: safe mode, raid state, claim counts, claims needing recovery, expired claims, interrupted conquests, team integration, config problems. |
| `/war admin version` | Version, build number, Minecraft, Forge and Flan versions. |
| `/war admin reload` | Re-validate the config, rebuild caches and re-detect FTB Teams. |
| `/war admin mode disabled\|always\|custom` | Change the raid mode (saved to the config). |
| `/war admin setwindow 15:00 06:00` | Change the custom raid window (saved). |
| `/war admin capturetime` | Show the global capture time. |
| `/war admin capturetime <seconds>` | Set the capture time for every flag (saved in the config; running captures keep their %). |
| `/war admin capturetime reset` | Back to 90 seconds. |
| `/war admin flag restore <claim>` | Rebuild a missing flag at its registered position. |
| `/war admin flag relocate <claim>` | Put the flag where you stand. This also activates a pending claim. |
| `/war admin claim inspect [<claim>]` | State, live state, owners, flag block, accounting and the conquest journal. Without an argument it uses the claim you stand in. |
| `/war admin claim repair <claim>` | Re-sync with Flan, retry interrupted conquests and restore the flag. |
| `/war admin capture cancel <claim>` | Stop a capture in progress (the owner is told). |
| `/war admin conquest rollback <claim>` | Give the claim back to its previous owner and restore its previous immunity. The conquest stays in the war log marked "reversed" and no longer counts in statistics. Members and subclaims are not restored. |
| `/war admin claims <player>` | Another player's conquered claims and slots. |
| `/war admin immunity inspect\|clear [<claim>]` | Show or remove recapture immunity (default: the claim you stand in). |
| `/war admin immunity set <duration> [<claim>]` | Set immunity, e.g. `24h`, `90m`, `1d12h`, `0`. |
| `/war admin season info` | Current season, recording state, archives. |
| `/war admin season start <name>` / `end` / `archive` | Season lifecycle (start archives a finished season automatically). |
| `/war admin season reset confirm` | Clear the current season's statistics after writing a backup archive. |
| `/war admin stats check` | Read-only report: season, players, kingdoms, conquests, defenses, conquered claims, log entries, integrity warnings. Changes nothing. |
| `/war admin stats check <player>` | Read-only player report: UUID, kingdom, conquests, defenses, losses, ordinary/conquered claims, slots, immunity, inconsistencies. |
| `/war admin rank list` | Every kingdom with ranks: main claim UUID, owner, counts. |
| `/war admin rank inspect <kingdom>` | Ranks, holders, suspensions and personal positions. |
| `/war admin rank create <kingdom> <name>` / `reset <kingdom>` | Create a rank / remove all ranks and assignments of a kingdom. |
| `/war admin rank assign <kingdom> <player> <rank>` / `remove <player>` | Assign across kingdoms (moves an existing rank). |
| `/war admin rank position <player> left\|above\|below\|reset` | Set anyone's personal placement. |
| `/war admin stats repair confirm` | Apply the safe fixes the check reports (duplicates, owner re-sync, deleted claims, invalid immunity). Audit-logged. |

`<claim>` is the Flan claim UUID; press Tab to complete it. `<kingdom>` is a main-claim UUID or
its Flan claim name (in quotes if it has spaces). Admins can also use every `/war rank` command
while standing in any kingdom's main claim. Operators who switch on Flan's own
admin mode can still manage inactive claims through Flan.

---

## Configuration

`world/serverconfig/flanwarfare-server.toml`. Every value is documented in the file. The main
values are:

| Key | Default | Meaning |
|---|---|---|
| `raid.mode` | `CUSTOM` | `DISABLED`, `ALWAYS` or `CUSTOM` |
| `raid.start_time` / `end_time` | `15:00` / `06:00` | In-game time. 06:00 = tick 0, 15:00 = 9000, 00:00 = 18000. Windows may cross midnight. |
| `raid.permissions.allow_entering` | `true` | Flan `CANSTAY` for outsiders during raids (needed to raid claims that deny entry) |
| `raid.permissions.allow_breaking` / `allow_placing` / `allow_containers` | `true` | Flan `BREAK`, `PLACE`, `OPENCONTAINER` (opening and looting are one Flan permission) |
| `raid.permissions.allow_doors_and_trapdoors` / `allow_redstone_interactions` / `allow_block_interactions` | `true` | Doors, gates; buttons, levers, plates, redstone; crafting stations and machines (`INTERACTBLOCK`) |
| `raid.permissions.allow_projectiles_and_pearls` / `allow_entity_damage` / `allow_pvp_during_raids` | `true` | |
| `raid.permissions.allow_buckets` | `false` | Fluid placing |
| `raid.permissions.extra_permissions` | `[]` | Any other Flan permission IDs, e.g. `["BED"]` |
| `raid.world_effects.allow_tnt_explosions` / `creeper` / `other` / `modded` | `true` | Block damage per explosion source |
| `raid.world_effects.allow_explosion_entity_damage` | `true` | `false` protects entities in raidable claims from explosions during raids |
| `raid.world_effects.allow_fire_spread` / `allow_pistons` / `allow_fluid_griefing` / `allow_fake_players` | `false` | |
| `raid.warning_seconds` | `[60, 10]` | Advance warnings before a raid |
| `raid.disabled_dimensions` / `excluded_claims` | `[]` | Left entirely to Flan |
| `flags.placement_grace_seconds` | `600` | Reminder period (does not activate the claim) |
| `flags.inactive_claim_expiration_seconds` | `86400` | Lifetime of claims without a flag |
| `flags.auto_abandon_expired_claims` | `false` | Delete expired claims through Flan |
| `flags.movement_cooldown_seconds` | `300` | Outside raids |
| `capture.radius_blocks` | `3.0` | **The** capture radius: detection, contesting, aura and distance readout |
| `capture.vertical_tolerance_blocks` | `3.0` | How far above/below the pole base still counts |
| `flagpole.enabled` / `height_blocks` / `pole_block` | `true` / `4` / `minecraft:dark_oak_fence` | New and moved flags only |
| `flagpole.animated_capture` / `animation_midpoint_percent` / `reverse_animation_on_decay` | `true` / `50` / `true` | |
| `capture.duration_seconds` / `decay_seconds` | `90` / `60` | Uncontested capture time / full-bar decay |
| `capture.contest_by_rival_attackers` | `true` | |
| `capture.defenders.enabled` / `owner_can_contest` / `team_members_defend` | `true` | |
| `capture.defenders.trusted_defenders` | `MANAGERS` | `MANAGERS`, `ALL_MEMBERS` or `NONE` |
| `capture.defenders.trusted_members_cannot_capture` | `true` | Flan members of a claim can't capture it |
| `member_notifications.enabled` / `notify_on_added` / `notify_on_removed` / `notify_on_permission_change` / `queue_offline_notifications` | `true` | |
| `flags.min_flag_distance` | `7` | Keeps 3-block capture zones apart |
| `capture_aura.enabled` / `particle_interval_ticks` / `visibility_distance_blocks` | `true` / `10` / `48` | Seen by every nearby player |
| `capture_aura.show_immunity_aura` | `true` | |
| `capture_aura.show_idle_aura` / `show_active_aura` / `show_contested_aura` / `show_completion_effect` | `true` | |
| `capture_aura.idle_color` / `active_color` / `contested_color` | grey / red / yellow | `#RRGGBB` |
| `capture_aura.show_outside_raids` | `false` | Idle rings outside raids too |
| `raid_notifications.enabled` / `notify_owner` / `notify_trusted_defenders` / `notify_team_members` | `true` | |
| `raid_notifications.show_center_screen_title` / `play_warning_sound` / `show_chat_message` / `show_claim_coordinates` | `true` | |
| `raid_notifications.ordinary_raid_cooldown_seconds` | `45` | |
| `raid_notifications.capture_start_high_priority` / `offline_raid_summary` | `true` | |
| `conquest.allow_conquered_claim_resize` | `false` | |
| `conquest.max_conquered_claims_per_player` | `3` | `-1` = unlimited |
| `conquest.recapture_immunity_hours` | `24` | Real-world hours, `0` = off; affects future conquests |
| `capture_aura.immune_color` | blue | Ring colour during immunity |
| `leaderboard.enabled` / `track_successful_defenses` / `track_kingdom_stats` / `record_war_history` | `true` | |
| `leaderboard.kingdoms_are_parties_only` | `true` | Solo FTB players have no kingdom |
| `leaderboard.defense_minimum_capture_percent` | `10` | |
| `leaderboard.defense_cooldown_minutes` | `30` | Anti-farming |
| `leaderboard.default_page_size` | `10` | |
| `leaderboard.season_name` | `The Grand Expedition - Season 1` | First season only |
| `leaderboard.time_zone` | server zone | e.g. `America/New_York` for log timestamps |
| `conquest.use_ftb_teams` | `true` | |
| `general.prevent_sleep_time_skip_during_raids` | `true` | |
| `kingdom_ranks.enabled` / `require_membership` | `true` | Ranks on/off; holders must be Flan members of the kingdom |
| `kingdom_ranks.max_ranks_per_kingdom` / `max_rank_name_length` / `max_decoration_length` | `20` / `24` / `4` | |
| `kingdom_ranks.allowed_decoration_symbols` | `""` | If set, the only symbols decorations may use |
| `kingdom_ranks.allow_hex_colors` / `allow_gradients` / `max_gradient_colors` | `true` / `true` / `5` | |
| `kingdom_ranks.allow_bold` / `allow_glow` / `allow_rainbow` / `allow_pulse` / `allow_wave` | `true` | |
| `kingdom_ranks.allow_obfuscated` | `false` | |
| `kingdom_ranks.animation_update_interval_ticks` | `20` | How often animated tags update |
| `rank_chat.enabled` / `format` | `true` / `[{rank}] {player}: {message}` | |
| `rank_nametags.enabled` / `default_position` / `separator` | `true` / `LEFT` / ` \| ` | |
| `rank_nametags.allow_above_position` / `allow_below_position` / `allow_player_overrides` | `true` | Disabled positions fall back to LEFT |

Fixed rules (deliberately not configurable, for safety):

- Additional attackers never speed up a capture.
- Progress never increases outside raids and never carries into the next raid window.
- Capture progress is not saved, so a restart cancels running captures.
- Admin claims are always excluded from raids, capture, auras and alerts.

---

## Known limitations

- **Not yet tested in game.** The build compiles and the logic tests pass. Follow
  `docs/TESTING.md` on a copy of the server before the season.
- **Modded explosions** are covered when the mod uses Forge's explosion event. Those with a
  non-vanilla source entity count as "modded"; those without a source entity count as "other".
  Mods that delete blocks directly (some bombs, mining lasers) bypass Flan and this add-on alike.
- **Modded machines** count as containers or generic block interactions in Flan, so they follow
  `allow_containers` / `allow_block_interactions`. There is no per-machine control.
- **Modded machines** that set blocks without firing Forge events can still remove a flag. The
  add-on notices within 5 seconds and rebuilds it, and deletes fresh banner drops next to it. If
  it can't rebuild the flag 3 times in a row, the claim goes to `RECOVERY_REQUIRED`. The claim
  stays raidable and capturable, so destroying a flag never makes a claim immune.
- **Flag animation is stepped, not smooth.** 1.16.5 has no display entities, and banners only
  sit at whole-block positions. A smooth version would need either armour-stand tricks (fragile,
  and their positioning can't be checked without a client) or a client mod. Taller poles
  (`height_blocks`) give more steps.
- **Wooden poles can burn** if fire reaches them (fire isn't a Forge break event). The
  restore check rebuilds the pole within 5 seconds.
- **Flags placed before this update** stay single banners until moved (`/flag move`) or rebuilt
  with `/war admin flag relocate`.
- **Particles** follow the player's own particle setting. With particles set to Minimal the ring
  may be faint, but the action-bar distance readout still shows the zone.
- **Sleep mods** that change time directly instead of through Forge's sleep event can skip raid
  time.
- **FTB Teams** is accessed by reflection. `/war admin status` tells you whether it was detected.
  If not, every player is their own side and team alerts are off.
- **Other protection mods** (YAWP, WorldProtector) still apply. Raid access only stops Flan from
  objecting; it cannot override them.
- **Conquest crash safety:** Flan writes one file per owner, so a crash in the few milliseconds
  between Flan's two file writes could lose the claim from Flan. The add-on detects this at
  startup and lists it for admins, keeping Flan's full JSON snapshot of the claim in the
  journal. It cannot recreate the claim by itself.
- **Recaptured conquered land stays free.** A claim is free for whoever owns it once it has been
  conquered, including its original owner if they retake it.
- **Statistics start when the mod is installed.** Conquests made while statistics were disabled
  are reported by `/war admin stats check` but never invented afterwards.
- **Kingdom names** come from FTB Teams via reflection. If the team type or name can't be
  read, `/war admin status` says so and teams are shown by a short id.
- **Rank nametags** use vanilla tricks:
  - `LEFT` is a scoreboard team prefix. A player who is already in another plugin's or
    datapack's scoreboard team keeps that team, and their rank shows `BELOW` instead (or in chat
    only, if `BELOW` is disabled).
  - `BELOW` and `ABOVE` are invisible marker entities riding the player, so they move smoothly.
    They sit at fixed heights, so the gap to the player's name can't be fine-tuned.
  - Labels hide while the player sneaks, is invisible, spectates or is dead. They also hide
    while swimming, because vanilla drops passengers under water, and come back on leaving the
    water.
  - With `LEFT`, vanilla death messages and other mods that print the player's display name
    show the rank too.
- **Chat tags** replace the chat line through Forge's chat event. Another chat-formatting mod on
  the server may override them, or be overridden; set `rank_chat.enabled = false` to keep the
  other mod's format.
- **Scoreboard:** Flan's scoreboard criterion for used claim blocks does not subtract conquered
  land. Flan's own block checks and `/flan` messages do.
