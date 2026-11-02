# Grand Expedition Kingdom Spawn

A small **server-side** Forge 1.16.5 mod (Forge 36.2.x, Java 8). Players don't need to install
anything.

- **Kingdom assignments:** preassign players to Kingdom I, II and III before they ever join.
- **Distant biome search:** find each kingdom's roulette biome as far out as possible, with
  the three kingdoms far apart.
- **Deployment:** spawn each kingdom's members together on their **first** join.
- **Hub teleports:** `/spawn` to the central hub and `/return` back, with anti-combat-escape
  rules.

It doesn't touch Flan claims, beds, respawn anchors, the world spawn or your world border.

## Install
1. Put `kingdomspawn-<version>.jar` in the **server's** `mods` folder only.
2. Start the server once. It creates `config/kingdomspawn.json`. A full example is in
   [`docs/example-kingdomspawn.json`](docs/example-kingdomspawn.json).
3. Runtime state is stored in `<world>/kingdomspawn/state.json`: locations, who has been
   deployed, and saved `/return` points.

## Season setup (in order)
1. **Biomes:** run `/kingdomspawn setbiome kingdom_1 <biome_id>` for each kingdom. Use the exact
   registry id; tab completion lists every vanilla and modded biome on the server.
2. **Players:** run `/kingdomspawn assign <uuid|name> kingdom_1`, or edit the config's
   `players` list and run `/kingdomspawn reload`. UUIDs are the real key; names are optional.
   A player who never joined can be assigned by UUID, or by name if Mojang knows it.
3. **Search:** run `/kingdomspawn findall`.
   - It scans biomes from the outer edge (corners first) inward, finds candidate areas of each
     kingdom's biome, and picks one point per kingdom together. Each pair of kingdoms is at
     least `min_separation` (15,000) blocks apart, as far out and as spread as possible. Then
     it checks the terrain at the chosen points.
   - Progress appears in chat and in the server log. `/kingdomspawn status` and
     `/kingdomspawn cancel` are available.
4. **Review:** use `/kingdomspawn preview <kingdom>` and `/kingdomspawn tp <kingdom>` to look
   at a proposed point.
5. **Approve:** run `/kingdomspawn approve <kingdom>`. Or stand somewhere you like and run
   `/kingdomspawn sethere <kingdom>`.
6. **Hub:** stand at the hub and run `/kingdomspawn sethub` (and `/kingdomspawn setholding` if
   unassigned players should wait somewhere else).

From then on, an assigned player joining for the first time lands 5–10 blocks from their
kingdom's point, on a spot of their own, with a welcome message. This happens **once**. Later
logins, deaths and respawns work normally: bed, anchor, otherwise the world spawn.

## Commands (operators, `/kingdomspawn …`)

| Command | What it does |
|---|---|
| `kingdoms` | Overview of all kingdoms |
| `assign <player> <kingdom>` / `unassign <player>` | Preassign or remove (UUID or name) |
| `players <kingdom>` | Assigned players and their state (deployed / waiting / not joined) |
| `resetplayer <player>` | Deploy this player again at their next login |
| `setbiome <kingdom> <biome_id>` | Set the roulette biome (validated, never substituted) |
| `find <kingdom>` / `findall` | Search for one kingdom (keeping away from approved ones) or all |
| `status` / `cancel` | Search progress / stop it |
| `preview <kingdom>` / `approve <kingdom>` | Show / accept a proposal |
| `sethere <kingdom>` | Use your position as the kingdom's point |
| `tp <kingdom>` | Go to its proposed or approved point |
| `info <kingdom>` | Biome, point, distance from 0,0, separation, players |
| `sethub` / `setholding` | Set the hub / holding area to your position |
| `reload` | Re-read the config (a broken file is reported and the old config kept) |

**Players:** `/spawn` (to the hub) and `/return` (back to where you were). Both names are
configurable.

## How the search works
- **Biome sampling:** biomes are read from the world generator's biome source on a grid
  (`sample_spacing`, 256 blocks by default, about 150,000 samples for 100k × 100k). **No chunks
  are loaded or generated** for this. It runs on the server thread within `budget_ms_per_tick`
  (8 ms per tick), so the server keeps 20 TPS.
- **Order:** farthest first (horizontal Euclidean distance from 0,0), in `band_width` bands.
  After each band, if every kingdom has `stop_after_candidates` candidates and a valid layout
  exists, the scan stops early, so it rarely has to cover the whole world.
- **Candidates:** a sample counts only if at least `core_min_matches` of 8 points on a ring of
  `core_radius` blocks around it are the same biome. That keeps candidates inside the biome
  rather than on a sliver of it.
- **Layout:** one candidate per kingdom is chosen **jointly**. Every pair (including
  already-approved kingdoms) must be at least `min_separation` apart; the score favours points
  far from the centre and far from each other. If nothing qualifies, the admin gets the best
  separation that was possible and no location is chosen.
- **Terrain:** first the generator's height estimate (no chunks: above sea level, not steep).
  Then about 9 chunks around the point are generated, `chunk_loads_per_tick` at a time, and it
  needs a safe centre in the biome with `min_safe_slots` safe, level spots 5–10 blocks around.
  "Safe" means solid ground, room to stand, and no water, lava, fire or leaves. A failed
  candidate is excluded and the layout recomputed (up to `max_terrain_attempts`).

## `/spawn` and `/return` rules
- **Delay:** 5 s, cancelled by moving or taking any damage.
- **Combat:** blocked for `combat_seconds` after dealing or taking player damage.
- **Cooldown:** `cooldown_seconds`.
- **Saved spot:** `/spawn` saves where you were, unless you're already at the hub, so repeats
  don't overwrite it. `/return` restores position, dimension and facing, then clears the saved
  spot.
- **When `/return` works:** only from the hub (`return_only_from_hub`). It's refused if the spot
  is outside the world border or the configured bounds, or is blocked or in lava (the spot is
  kept). The saved spot is cleared when you die, and can expire (`return_expiry_minutes`).
- **Operators** skip the delay, cooldown and combat block (`admins_bypass`).
- **Other mods:** if another mod (e.g. FTB Essentials) also has `/spawn`, this mod registers
  last and its `/spawn` is the one used. Set `register_spawn_commands: false` or rename the
  commands to avoid that.

## Unassigned players
A brand-new player without a kingdom, or whose kingdom has no approved point yet, is sent to the
holding area (the hub by default) and told to wait. As soon as an admin assigns them or approves
their kingdom's point, waiting players who are online are deployed immediately; offline ones are
deployed at their next login. Players who had already played before being assigned are only
deployed if `deploy_existing_players` is on, or after `/kingdomspawn resetplayer`.

## Testing checklist (not yet run in game)

| # | Test | Expected |
|---|---|---|
| 1 | Assign a UUID of someone who never joined; they join | Arrives 5–10 blocks from the kingdom point; welcome message. |
| 2 | Second and third members of the same kingdom join on later days | Each gets a different nearby spot at the same point. |
| 3 | Three kingdoms with different biomes, `findall` | Three points in the right biomes, each pair ≥ 15,000 apart, as far out as possible. |
| 4 | A modded biome (BOP, BYG, Atmospheric…) | Found and validated like a vanilla one; a typo is refused. |
| 5 | `min_separation` of 90,000 | Search reports that no layout fits, with the best possible separation. |
| 6 | Candidate over ocean or on a cliff | Rejected ("no flat land" / "not enough safe ground"); the next one is tried. |
| 7 | TPS during a search | Stays about 20. |
| 8 | Restart mid-season | Approved points, deployed players and saved `/return` spots kept. |
| 9 | A deployed player relogs | Stays where they logged out (no redeployment). |
| 10 | Death with and without a bed | Bed or anchor respawn, else world spawn; never the kingdom point. |
| 11 | `/spawn`, wait 5 s, `/return` | Hub, then back to the exact spot and facing. |
| 12 | `/spawn` then move or get hit | Cancelled. |
| 13 | Hit another player, then `/spawn` | Blocked for 15 s. |
| 14 | `/spawn` at the hub again | The original saved spot is kept. |
| 15 | Die after `/spawn`, then `/return` | "No saved location". |
| 16 | `/return` away from the hub | Refused (with `return_only_from_hub`). |
| 17 | Unassigned new player joins; admin assigns them | Waits at the holding area, then is sent to the kingdom immediately. |
| 18 | Reassign a deployed player | Config updated; they are not moved (use `resetplayer` to redeploy). |
| 19 | Break `kingdomspawn.json` (bad JSON), `/kingdomspawn reload` | Error reported; the previous config stays active; the file isn't overwritten. |
