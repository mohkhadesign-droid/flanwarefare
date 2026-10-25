# Flan Warfare & Conquest

Server-side Forge 1.16.5 add-on for **Flan 1.16.5-1.7.2** that adds scheduled raid windows,
mandatory claim flags, contested territory capture with a visible capture zone, live raid
alerts, permanent conquest with limits and recapture immunity, and season leaderboards with a
war history.

- Players do **not** install anything. The mod registers no blocks, items or network channels.
  Banners, particles, boss bars, titles and sounds are all vanilla.
- Flan stays in charge of claims, owners, boundaries, trust and claim blocks. This add-on never
  runs a second claim system; it decides permissions dynamically and uses Flan's own operations.
- Built and verified against the exact Flan JAR on your server (see `docs/TECHNICAL.md`).

> **Status:** builds on GitHub Actions against Forge 1.16.5 (compile, reobfuscation and 127
> automated logic checks). The in-game behaviour has **not** been tested on a server yet.
> Follow `docs/TESTING.md` on a copy of the server before using it on the live season.

---

## Installation

1. Download the JAR from the latest workflow run: **Actions → Build → flanwarfare-jar**.
2. Put `flanwarfare-1.0.0.jar` in the **server's** `mods/` folder, next to Flan 1.16.5-1.7.2.
   Do not install it on clients. The mod refuses to load with any other Flan version.
3. Start the server once. The config appears at `world/serverconfig/flanwarfare-server.toml`.
4. Edit it, then save. Forge reloads it automatically; `/war admin reload` re-validates it.

Data is stored in `world/data/flanwarfare/war_data.json` (atomic writes, previous version kept as
`war_data.json.bak`). Include it in backups together with `world/data/claims/`.

### Building

Every push runs `.github/workflows/build.yml`, which runs `./gradlew build` and uploads the JAR.
To build locally instead: JDK 8, then `./gradlew build`. The JAR is `build/libs/flanwarfare-1.0.0.jar`.

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
2 blocks around the pole's base. Everyone within 48 blocks sees it: owner, defenders, raiders and
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
| `/war admin stats repair confirm` | Apply the safe fixes the check reports (duplicates, owner re-sync, deleted claims, invalid immunity). Audit-logged. |

`<claim>` is the Flan claim UUID; press Tab to complete it. Operators who switch on Flan's own
admin mode can still manage inactive claims through Flan.

---

## Configuration

`world/serverconfig/flanwarfare-server.toml`. Every value is documented in the file. The main
values are:

| Key | Default | Meaning |
|---|---|---|
| `raid.mode` | `CUSTOM` | `DISABLED`, `ALWAYS` or `CUSTOM` |
| `raid.start_time` / `end_time` | `15:00` / `06:00` | In-game time. 06:00 = tick 0, 15:00 = 9000, 00:00 = 18000. Windows may cross midnight. |
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
| `capture.radius_blocks` | `2.0` | **The** capture radius: detection, contesting, aura and distance readout |
| `capture.vertical_tolerance_blocks` | `3.0` | How far above/below the pole base still counts |
| `flagpole.enabled` / `height_blocks` / `pole_block` | `true` / `4` / `minecraft:dark_oak_fence` | New and moved flags only |
| `flagpole.animated_capture` / `animation_midpoint_percent` / `reverse_animation_on_decay` | `true` / `50` / `true` | |
| `capture.duration_seconds` / `decay_seconds` | `90` / `60` | Uncontested capture time / full-bar decay |
| `capture.contest_by_rival_attackers` | `true` | |
| `capture.defenders.enabled` / `owner_can_contest` / `team_members_defend` | `true` | |
| `capture.defenders.trusted_defenders` | `MANAGERS` | `MANAGERS`, `ALL_MEMBERS` or `NONE` |
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
- **Scoreboard:** Flan's scoreboard criterion for used claim blocks does not subtract conquered
  land. Flan's own block checks and `/flan` messages do.
