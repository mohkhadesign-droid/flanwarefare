# Flan Warfare & Conquest

Server-side Forge 1.16.5 add-on for **Flan 1.16.5-1.7.2** that adds scheduled raid windows,
mandatory claim flags, contested territory capture and permanent conquest.

- Players do **not** install anything. The mod registers no blocks, items or network channels.
- Flan stays in charge of claims, owners, boundaries, trust and claim blocks. This add-on never
  runs a second claim system; it decides permissions dynamically and uses Flan's own operations.
- Built and verified against the exact Flan JAR on your server (see `docs/TECHNICAL.md`).

> **Status:** code complete. The pure logic (clock maths, capture rules, capturer selection) is
> tested. The Minecraft-facing parts have **not** been compiled or run yet. See
> `docs/TESTING.md` before using it on the live season server.

---

## Installation

1. Build the JAR (GitHub Actions does this automatically, see below) or download it from the
   latest workflow run: **Actions → Build → flanwarfare-jar**.
2. Put `flanwarfare-1.0.0.jar` in the server's `mods/` folder **next to Flan 1.16.5-1.7.2**.
   The mod refuses to load with any other Flan version.
3. Start the server once. The config appears at `world/serverconfig/flanwarfare-server.toml`.
4. Edit it, then save. Forge reloads it automatically; `/war admin reload` re-validates it.

Data is stored in `world/data/flanwarfare/war_data.json` (atomic writes, previous version kept as
`war_data.json.bak`). Include it in backups together with `world/data/claims/`.

### Building with GitHub Actions

Push this folder to a GitHub repository. The workflow in `.github/workflows/build.yml` runs
`./gradlew build` on every push and uploads the JAR as an artifact. No local Java is needed.

To build locally instead: JDK 8, then `./gradlew build`. The JAR is `build/libs/flanwarfare-1.0.0.jar`.

Flan itself is **not** in this repository (it is "All Rights Reserved"). The build compiles
against signature-only stubs in `src/flanstubs`, which were checked member by member against
the real Flan 1.7.2 JAR and are never packaged.

---

## How it plays

**New claims start inactive.** Right after creating a claim with Flan, it protects nothing and
cannot be managed (no trust, permissions, subclaims, resizing, renaming, home or transfer).
The owner stands inside it and runs `/flag place`, which puts a standing banner at their feet
and activates the claim. Reminders run for 10 minutes; after that the claim simply stays
inactive. Claims that never get a flag expire after 24 hours (listed for admins, or
automatically abandoned if `auto_abandon_expired_claims = true`).

**Raid windows** follow the Overworld clock (default 15:00-06:00 in-game). During a raid,
anyone who is not the owner or a Flan member of a claim may break, place and loot there. When
the raid ends, protection returns instantly. Nothing is written to claims.

**Capturing.** During a raid, stand within 2 blocks of an enemy flag. A boss bar fills over 180
seconds. A defender in range pauses it. Two rival sides in range pause it. If you leave, it
drains over 60 seconds. When the raid ends, unfinished captures drain to zero, even with
attackers still standing there, and the next raid always starts fresh.

**Conquest.** At 100% the individual capturing player becomes the Flan owner of the claim.
Boundaries, buildings and items stay. All old members, groups, overrides, subclaims, name,
titles and potion effects are removed, and the home moves to the flag. The flag stays where it
is and takes the conqueror's banner design. Conquered land costs no claim blocks and no claim
slot, and it cannot be resized or transferred.

### Sides and defenders (confirmed policy)

- **Attacking side:** your FTB Teams team. Party members cooperate; players without a party
  are their own side. Without FTB Teams, every player is their own side.
- **Defenders of a claim:** the owner, players on the owner's FTB team, and Flan members whose
  group grants `EDITCLAIM` or `EDITPERMS`. Someone trusted only for a chest is *not* a defender.
- **Who gets the claim:** the first player of the capturing side to reach the flag, while they
  stay. If they leave, it goes to the longest continuously present teammate, with ties broken by
  UUID. Never random.

---

## Player commands

| Command | What it does |
|---|---|
| `/flag place` | Place the flag of the claim you stand in and activate it. |
| `/flag move` | Move your flag to where you stand, inside the same claim. Owner only, never during raids, 5-minute cooldown. |
| `/flag info` | State, flag position, conquest status, raid status. |
| `/flag abandon` / `/flag abandon confirm` | Delete one of your claims that never had a flag. |
| `/flag design set` | Save the banner in your main hand as your design. The banner is not consumed. |
| `/flag design apply` | Show your saved design on the flag of the claim you stand in. |
| `/flag design reset` | Go back to the default banner. |
| `/war status` | Whether a raid is active and when the next change happens. |
| `/war capture` | Captures on your claims or within 64 blocks. |

**Flag placement rules:** you must stand in open air, inside your claim, on a solid
non-falling block that isn't a container or machine. You need a clear 3x3 space, two blocks high,
around you, fully inside the claim. No lava or fire may be adjacent, and other flags must be at
least 4 blocks away.

After placement nobody can put blocks into that space, or break the flag or the block under it.
Attackers can always reach the flag by breaking through whatever surrounds the space.

## Administrator commands (permission level 2 by default)

| Command | What it does |
|---|---|
| `/war admin status` | Diagnostics: safe mode, raid state, claim counts, claims needing recovery, expired claims, interrupted conquests, team integration, config problems. |
| `/war admin reload` | Re-validate the config, rebuild caches and re-detect FTB Teams. |
| `/war admin mode disabled\|always\|custom` | Change the raid mode (saved to the config). |
| `/war admin setwindow 15:00 06:00` | Change the custom raid window (saved). |
| `/war admin flag restore <claim>` | Rebuild a missing flag at its registered position. |
| `/war admin flag relocate <claim>` | Put the flag where you stand. This also activates a pending claim. |
| `/war admin claim inspect [<claim>]` | State, owners, flag block, accounting and the conquest journal. Without an argument it uses the claim you stand in. |
| `/war admin claim repair <claim>` | Re-sync with Flan, retry interrupted conquests and restore the flag. |
| `/war admin capture cancel <claim>` | Stop a capture in progress. |
| `/war admin conquest rollback <claim>` | Give the claim back to its previous owner. Members and subclaims are not restored. |

`<claim>` is the Flan claim UUID; press Tab to complete it. Operators who switch on Flan's own admin mode can still manage inactive claims through Flan.

---

## Configuration

`world/serverconfig/flanwarfare-server.toml`. Every value is documented in the file. The main
values are:

| Key | Default | Meaning |
|---|---|---|
| `raid.mode` | `CUSTOM` | `DISABLED`, `ALWAYS` or `CUSTOM` |
| `raid.start_time` / `end_time` | `15:00` / `06:00` | In-game time. 06:00 = tick 0, 15:00 = 9000, 00:00 = 18000. Windows may cross midnight. |
| `raid.allow_breaking` / `allow_placing` / `allow_containers` | `true` | Flan `BREAK`, `PLACE`, `OPENCONTAINER` |
| `raid.allow_interactions` / `allow_buckets` | `false` | Doors, gates, buttons and similar / buckets |
| `raid.extra_permissions` | `[]` | Any other Flan permission IDs, e.g. `["ANVIL"]` |
| `raid.warning_seconds` | `[60, 10]` | Advance warnings before a raid |
| `raid.disabled_dimensions` / `excluded_claims` | `[]` | Left entirely to Flan |
| `raid.world_effects.*` | all `false` | Explosions, fire spread, pistons, fluids, fake players during raids |
| `flags.placement_grace_seconds` | `600` | Reminder period (does not activate the claim) |
| `flags.inactive_claim_expiration_seconds` | `86400` | Lifetime of claims without a flag |
| `flags.auto_abandon_expired_claims` | `false` | Delete expired claims through Flan |
| `flags.movement_cooldown_seconds` | `300` | Outside raids |
| `capture.duration_seconds` / `decay_seconds` / `radius_blocks` | `180` / `60` / `2.0` | |
| `capture.contest_by_defenders` / `contest_by_rival_attackers` | `true` | |
| `conquest.allow_conquered_claim_resize` | `false` | |
| `conquest.max_conquered_claims_per_player` | `-1` | Unlimited |
| `conquest.use_ftb_teams` | `true` | |
| `conquest.defenders_include_flan_managers` | `true` | |
| `general.prevent_sleep_time_skip_during_raids` | `true` | |

Fixed rules (deliberately not configurable, for safety):

- Additional attackers never speed up a capture.
- Progress never increases outside raids and never carries into the next raid window.
- Capture progress is not saved, so a restart cancels running captures.

---

## Known limitations

- **Not yet compiled or run.** The build environment used to write this mod could not reach
  Forge's servers. The first GitHub Actions build may surface small compile errors in Minecraft
  method names. Paste the log and they get fixed. Follow `docs/TESTING.md` on a copy of the
  server before the season.
- **Modded machines** that set blocks without firing Forge events can still remove a flag. The
  add-on notices within 5 seconds and rebuilds it, and deletes fresh banner drops next to it. If
  it can't rebuild the flag 3 times in a row, the claim goes to `RECOVERY_REQUIRED`. The claim
  stays raidable and capturable, so destroying a flag never makes a claim immune.
- **Sleep mods** that change time directly instead of through Forge's sleep event can skip raid
  time.
- **FTB Teams** is accessed by reflection because its API could not be checked here.
  `/war admin status` tells you whether it was detected. If not, every player is their own side.
- **Other protection mods** (YAWP, WorldProtector) still apply. Raid access only stops Flan from
  objecting; it cannot override them.
- **Conquest crash safety:** Flan writes one file per owner, so a crash in the few milliseconds
  between Flan's two file writes could lose the claim from Flan. The add-on detects this at
  startup and lists it for admins, keeping Flan's full JSON snapshot of the claim in the
  journal. It cannot recreate the claim by itself.
- **Recaptured conquered land stays free.** A claim is free for whoever owns it once it has been
  conquered, including its original owner if they retake it.
- **Scoreboard:** Flan's scoreboard criterion for used claim blocks does not subtract conquered
  land. Flan's own block checks and `/flan` messages do.
