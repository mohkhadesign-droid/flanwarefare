# Technical notes: Flan integration, accounting and recovery

Everything below was established by disassembling the installed
`flan-1.16.5-1.7.2-forge.jar` (`javap -c -p`), not from Flan's source history.

## 1. Feasibility findings

| Question | Finding in Flan 1.7.2 | Consequence |
|---|---|---|
| Can raid access be granted dynamically? | `Claim.canInteract(player, perm, pos, msg)` posts `PermissionCheckEvent` **first**, before its admin-claim, owner and group checks. The default result is `PASS`; `SUCCESS` allows, `FAIL` denies. | All player permissions are decided in one Forge listener. No claim data is changed, so protection returns the moment a raid ends. The listener must skip admin claims itself, because the event fires before Flan's admin check. |
| Do world effects reach the event? | No. Explosions, fire spread, pistons, fluids, mob spawns and lightning call `canInteract(null, perm, pos)`, which never posts the event. | One `@Inject` at the head of `Claim.canInteract`, active only for a null player, during raids, per configuration (explosions on by default per source, fire/pistons/fluids off). See section 9. |
| Is `ClaimStorage.transferOwner` usable for conquest? | It returns `false` unless `player.getUniqueID()` equals the current owner. When it succeeds it moves the claim between owners in `playerClaimMap` and marks both owners' files dirty. | It is the right operation (it keeps Flan's index and saves consistent), but it needs the old owner as caller. The add-on passes a Forge `FakePlayer` carrying the old owner's UUID. |
| Does `Claim.transferOwner(UUID)` update indexes? | No. It only sets the field (and the subclaims' owners). | Never called directly. |
| How are used claim blocks computed? | `PlayerClaimData.usedClaimBlocks()` calls the private `calculateUsedClaimBlocks()`, which sums `getPlane()` over `allClaimsFromPlayer(uuid)` filtered to non-admin claims in every world, on every call. `OfflinePlayerData.usedClaimBlocks()` does the same. | (a) Losing a claim restores the old owner's allowance automatically, so **no refund is ever granted** (granting one would duplicate blocks). (b) Conquerors would be charged, so the add-on subtracts conquered land at the end of both methods. |
| How is the claim-slot limit enforced? | `createClaim` compares `playerClaimMap.get(owner).size()` with `maxClaims` (via `PermissionNodeHandler.permBelowEqVal`). | A `@Redirect` of that `Set.size()` call counts only non-conquered claims. |
| Is there a claim-creation event? | No. | New claims are registered lazily on the first permission check or guard that touches them, plus by a scan of online players' claims every 40 ticks. A claim that is missed keeps Flan's normal protection, which is the safe direction. |
| Do management commands go through the event? | Mostly. Trust, groups, permissions, potions, name, titles, home, resize, subclaims and deletion check `canInteract` with `EDITCLAIM`, `EDITPERMS` or `EDITPOTIONS`. Exceptions: `/flan transferClaim` (only an owner check) and GUI sub-screens that call `setPlayerGroup` and similar directly. | Pending claims are vetoed through the event. The exceptions are guarded at the operation itself: `transferOwner`, `resizeClaim`, `setPlayerGroup`, `setHomePos`, `setClaimName`. |
| Can old trust data be cleared through the public API? | Only through `removePermGroup(player, group)`, which re-checks `EDITPERMS` at the *player's* position, and `setPlayerGroup(uuid, null, …)`, which needs every member UUID. Those live in the private `playersGroups` map. | A read-only `@Accessor` exposes the three permission maps, which are then cleared. |
| How does Flan save? | One JSON file per owner per dimension under `<world>/data/claims` (other dimensions use their own data folder), written by `ClaimStorage.save` for every owner in the dirty set, when the world saves. Claims are read in the `ClaimStorage` constructor while each `ServerWorld` is created. | No cross-file atomicity is possible. The add-on journals conquests and saves Flan immediately (section 4). It starts after `FMLServerStartedEvent` (lowest priority), when every claim is loaded. |
| Resize internals | `resizeClaim(claim, from, to, player)` keeps the corner opposite `from`, builds the new area from it to `to`, and checks the owner's blocks via `PlayerClaimData`/`OfflinePlayerData`. | The resize guard mirrors that corner logic to reject resizes that would leave the flag outside the claim. |
| Permission IDs | `ClaimPermission.id` equals the field name (`BREAK`, `OPENCONTAINER`, …). `PermissionRegistry.get` throws on unknown IDs. | The config uses those IDs and catches unknown ones. |

## 2. Mixins (all `remap = false`, Flan classes only, `required: true`)

| Mixin | Target | Purpose |
|---|---|---|
| `ClaimAccessor` | `Claim.playersGroups`, `permissions`, `globalPerm` | Clear old trust data on conquest; read members for defender and raid checks. |
| `ClaimMixin` | `Claim.canInteract(..)Z` at HEAD, null player only | Raid world effects: explosions per source, fire, pistons, fluids. |
| | `setPlayerGroup`, `setHomePos`, `setClaimName` at HEAD | Block management of claims without a flag on every path. |
| `ClaimStorageMixin` | `resizeClaim` at HEAD | Block resize when pending, when conquered, or when the flag would end up outside. |
| | `transferOwner` at HEAD | Block ordinary transfer of pending or conquered claims. |
| | `createClaim` `Set.size()` redirect | Make conquered claims slot-free. |
| `PlayerClaimDataMixin` | `calculateUsedClaimBlocks` at RETURN | Subtract conquered land. |
| `OfflinePlayerDataMixin` | `usedClaimBlocks` at RETURN | Same, for offline owners. |

With `required: true` and `defaultRequire: 1`, the server refuses to start if any target is
missing. That is intended: it means the Flan version changed and the add-on's assumptions no
longer hold. The add-on's own calls run inside `Guards.bypass(...)`.

## 3. Claim-block accounting, precisely

- **Old owner:** nothing is added. Their used blocks drop by exactly the claim's area the moment
  Flan's owner index moves the claim (`playerClaimMap`), because Flan recomputes usage live.
- **New owner:** `used = Flan's sum − Σ getPlane()` of their claims marked conquered in
  `war_data.json`. Both terms come from the same live claim list, so the result can never go
  below the area of their ordinary claims, and the exemption can never create blocks.
- **Slots:** conquered claims are skipped when Flan counts claims for `maxClaims`.
- **Deleting or abandoning conquered land:** frees nothing, because it was never counted. No
  refund exists anywhere in the add-on.
- **Repeated capture:** each capture only moves an existing claim. The conquered flag is sticky,
  so the claim stays free and still generates nothing.
- **Resize:** conquered claims cannot be resized (unless `allow_conquered_claim_resize`), so
  "free" land cannot grow.
- **Transfer:** conquered claims cannot be transferred through Flan, so they cannot be turned
  back into ordinary claims.
- **Data loss:** if `war_data.json` were lost, conquered land would simply start counting
  against its owner again. That is safe: no free blocks. A *corrupted* (unreadable) file instead
  puts the add-on into safe mode. See below.

## 4. Conquest transaction and crash recovery

Steps, all on the server thread:

1. Revalidate: raid open, same raid window, capturer online and in range, claim intact and
   not admin, per-player conquest limit.
2. Lock the claim, then write a `PENDING` journal entry containing old and new owner, the
   previous and new banner design, and Flan's own `toJson` snapshot of the claim. Flush
   `war_data.json`. If the flush fails, abort with nothing changed.
3. In memory, in one tick: Flan `transferOwner` (via FakePlayer); delete every subclaim; clear
   members, groups and claim-wide overrides; remove potions; clear name and enter/leave titles;
   set home to the flag. Mark the record conquered and switch the banner design.
4. `ClaimStorage.save` for that dimension, which writes both owners' files immediately.
5. Mark the entry `COMMITTED` and flush `war_data.json`.

At startup every `PENDING` entry is compared with what Flan loaded:

| Flan shows | Action |
|---|---|
| new owner | Re-run step 3 (idempotent), save, `COMMITTED`. |
| old owner | `ABORTED`. Flan never saved the change; nothing happened. |
| claim missing | `NEEDS_ADMIN`. The snapshot stays in the journal; listed in `/war admin status`. |
| someone else | `NEEDS_ADMIN`; the claim is set to `RECOVERY_REQUIRED`. |

Because no step ever adds claim blocks, no ordering of crashes can duplicate them. Two owners
cannot coexist: Flan's single owner field is the source of truth, and the record follows it.

## 5. Flags

- **Identity:** the Flan claim UUID. Position, rotation and design live in the record.
- **Relocation** is crash-safe:
  1. Queue the old position as an "orphan" and record the new one, then save.
  2. Place the new banner.
  3. Remove the old banner and drop the orphan entry.
  A crash at any point leaves at most one banner after restart: the restore loop builds the
  recorded one, and the orphan list removes the old one when its chunk loads.
- **Protection:** Forge `BreakEvent`, `EntityPlaceEvent` (including multi-place),
  `FluidPlaceBlockEvent`, `ExplosionEvent.Detonate` and `PistonEvent.Pre` protect the banner
  and its support block, and keep the 3x3x2 space around the banner clear.
- **Restoration:** every 5 seconds, loaded flags are checked. A missing banner is rebuilt (with
  a support block if needed), and fresh banner item drops within 2 blocks are removed so a
  destroyed flag cannot be farmed. After 3 failures the claim goes to `RECOVERY_REQUIRED` and
  admins are told. It stays raidable and capturable throughout.
- **Chunks** are never force-loaded, except by the explicit admin `restore` and `repair` commands.

## 6. Safe mode

If `war_data.json` exists but cannot be parsed, the add-on does nothing at all. Flan's normal
protection applies everywhere, and raids, flags and capture are off. Without this, every
existing claim would look new and therefore unprotected. Fix it by restoring
`war_data.json.bak` or deleting the file (which makes every claim need a flag again), then
restart.

## 7. Performance

- Capture checks run every 10 ticks and cost (online players × flags in their dimension).
- Claim discovery runs every 40 ticks over online players only. Reconciliation runs every 100
  ticks as map lookups.
- No block scanning, no forced chunk loading, no async world access.
- `war_data.json` is written at most every 10 seconds when changed, and immediately after
  placement, relocation and conquest.
- Boss bar updates are sent only when the title, percentage or colour changes.

## 8. Raid permissions: mapping to Flan 1.7.2

| Config option | Flan permission IDs |
|---|---|
| `allow_breaking` | `BREAK` |
| `allow_placing` | `PLACE` (TNT is a block, so placing TNT is covered) |
| `allow_containers` | `OPENCONTAINER`. Flan uses one permission for opening and taking items, so there is no separate "looting" option. |
| `allow_doors_and_trapdoors` | `DOOR`, `TRAPDOOR`, `FENCEGATE` |
| `allow_redstone_interactions` | `BUTTONLEVER`, `PRESSUREPLATE`, `REDSTONE`, `TARGETBLOCK` |
| `allow_block_interactions` | `INTERACTBLOCK` (Flan's generic fallback, which is where crafting stations and most modded machines land), `ANVIL`, `ENCHANTMENTTABLE`, `BEACON`, `LECTERNTAKE`, `NOTEBLOCK`, `JUKEBOX` |
| `allow_projectiles_and_pearls` | `PROJECTILES`, `ENDERPEARL`, `CHORUSFRUIT`, `TRAMPLE` |
| `allow_entity_damage` | `HURTANIMAL`, `BREAKNONLIVING`, `ARMORSTAND`, `ITEMFRAMEROTATE` |
| `allow_pvp_during_raids` | `HURTPLAYER`, granted to everyone in the claim (members too, so defenders can fight back) |
| `allow_buckets` | `BUCKET` |
| `allow_fake_players` | `FAKEPLAYER` (Flan substitutes this for every action of a non-member fake player) |

All of these go through `PermissionCheckEvent` and are answered live, so they stop the instant a
raid ends. Explosions, fire, pistons and fluid flow have no player and are handled by the
`canInteract(null, …)` hook (section 2).

## 9. Explosions per source

Flan's `Detonate` listener runs at normal priority. It removes each affected block for which
`canInteract(null, EXPLOSIONS, pos)` is false, and it never touches the affected-entity list.
The add-on hooks in at three points:

1. **HIGHEST:** classify the explosion from `Explosion.getExploder()`:
   - `TNTEntity` / `TNTMinecartEntity`: TNT;
   - `CreeperEntity`: creeper;
   - any other `net.minecraft.*` entity, or no entity: other;
   - any other entity: modded.

   The result is stored in a thread-local.
2. **Flan (NORMAL):** for each block, the hook returns *allowed* only if a raid is active, the
   block's parent claim is an ordinary claim (active, recovery or without a flag; never admin,
   excluded or in a disabled dimension) and that source's option is on. Otherwise Flan's normal
   answer stands. Admin-claim and wilderness blocks are untouched, so a mixed explosion is
   filtered block by block.
3. **LOW:** flags and the blocks under them are removed from the list.
4. **LOWEST:** if `allow_explosion_entity_damage = false`, entities standing in raidable claims
   are removed from the victim list during raids. Claims that still lose blocks get a raid
   alert. Then the thread-local is cleared.

Because the decision is made at detonation time, a fuse lit during a raid that ends before
it explodes does no claim damage.

## 10. Capture zone, aura and defender contesting

`logic/CaptureZone` is the only zone geometry:

- horizontal distance from the flag block's centre ≤ `capture.radius_blocks`;
- feet between 1 block below and radius + 1 blocks above the flag base.

Capture detection, contesting, the particle ring (points placed exactly on the radius,
verified by the logic tests) and the action-bar distance all call it.

Presence is rebuilt from the online player list at every evaluation (every 10 ticks). Each
player in the zone is classified:

- **defender:** the owner, the owner's FTB party, and Flan members per `trusted_defenders`;
- **attacker:** everyone else;
- **neutral:** members who are not defenders, and the owner if `owner_can_contest = false`.

Players who died, logged out, teleported away, changed dimension or turned spectator are simply
not in the next evaluation. Any defender present together with an attacker means CONTESTED: no
gain and no decay. With no attackers, normal abandonment decay applies. After the raid, decay
applies whoever is present.

The ring is sent with per-player `spawnParticle`, only to players within
`visibility_distance_blocks` of the flag. Nothing is broadcast and no chunk is loaded.

## 11. Raid alerts

There are three independent sources, and the derived state (`ClaimWarState`) never confuses them:

- the raid window: global announcements only;
- hostile actions: per-claim `AlertThrottle`;
- the capture session.

| Event | Output | Throttle |
|---|---|---|
| First hostile action, or first after the cooldown | Title, subtitle, sound, chat with claim name and coordinates | `ordinary_raid_cooldown_seconds` (45) |
| Further hostile actions | Action bar "still under attack" | At most every 5 s |
| Enemy at the flag (capture session starts) | High-priority title, horn sound, chat with progress | Never suppressed |
| Capture becomes contested | Chat | At most every 15 s |
| Capture fails or is cancelled by an admin | Chat | Once |
| Claim conquered | Title, sound, chat to the previous owner and defenders | Once |

- **Hostile actions:** a raid permission granted to a non-member, excluding pearls, chorus fruit,
  trampling, pressure plates, projectiles, PvP and fake players (walking through is not an
  attack). Also any explosion that removes the claim's blocks.
- **Recipients:** the owner, defender members and party members (each configurable), minus
  anyone who ran `/war alerts off`.
- **Admin claims:** excluded by claim type at every trigger. The owner's op status plays no part.
- **Offline owners:** events are kept in `war_data.json`, coalesced per claim and type within
  10 minutes, at most 20 per player, and shown and cleared at login.

## 12. PvP during raids (verified in Flan 1.7.2)

Flan decides player damage in `EntityInteractEvents.attackSimple(attacker, target)`, called from
two places:

- `AttackEntityEvent` (melee);
- the `LivingDamageEvent` handler `preventDamage`, which uses `DamageSource.getTrueSource()`. That
  covers arrows, crossbows, tridents and any modded weapon whose damage source credits the
  attacking player.

For a player target it checks `canInteract(attacker, HURTPLAYER, target position)`, which posts
`PermissionCheckEvent`. During raids the add-on answers SUCCESS for HURTPLAYER in ordinary claims
(active, recovery, or without a flag), for members as well, so both sides can fight. Fake
players are included only if `allow_fake_players` is on.

This never overrides:

- `pvp=false` in `server.properties`, which vanilla applies before any event;
- other mods' invulnerability or friendly-fire rules, which cancel their own events;
- admin claims, which are skipped entirely.

Defender combat and capture contesting are independent: contesting only needs presence.

**Correction to section 9:** Flan's `preventDamage` also protects non-player, non-monster
entities (animals, villagers) from explosion damage in claims, by asking
`canInteract(null, EXPLOSIONS, entityPos)`. That happens after the Detonate event, so the
add-on's source context is not set. The hook tells the two calls apart:

- inside Detonate it decides block damage per source;
- outside it decides entity damage with `allow_explosion_entity_damage`.

## 13. Conquest limit and recapture immunity

- **Conquest slots:** `Accounting.conqueredCount` counts the claims a player currently owns in
  Flan that are marked conquered, across all worlds. Each claim counts once and the count is
  computed live, so losing, deleting or abandoning a claim frees the slot instantly. Ordinary
  claims never count.
- **When slots are checked:**
  - at every capture evaluation: an attacker at the limit is ignored and told why, so an eligible
    teammate becomes the designated capturer;
  - again inside the conquest transaction.
- **Lowering the limit** removes nothing.
- **Immunity:** `ClaimRecord.immuneUntil` holds real epoch milliseconds. It is set inside the
  conquest's `apply` step on the first application only (startedAt + configured hours), so crash
  recovery does not extend it. The previous value is kept in the journal and restored by
  rollback.
- **While immune:**
  - no capture session can start;
  - an existing session (for example after an admin sets immunity mid-capture) cannot progress;
  - completion is refused.
- **Raid permissions and raid alerts are unaffected.** No capture alert is sent, because no
  session starts.
- **Survival:** the timer survives restarts (war_data.json), flag moves and renames (the key is
  the claim UUID). It is deleted with the claim record when Flan deletes the claim. Changing
  `recapture_immunity_hours` only affects future conquests.

## 14. Season statistics and war log

**Storage.** The active season (id, name, start, end, events) lives in `war_data.json`. Archives
are written to `data/flanwarfare/seasons/<id>.json` with final rankings.

**Exactly once.**

- A conquest event's id is its transaction id. It is added in the same atomic file write that
  marks the transaction COMMITTED, both normally and during startup recovery. A crash leaves
  either PENDING with no event, which recovery then completes and records, or COMMITTED with the
  event.
- The aggregator also ignores repeated ids, and `/war admin stats check` removes them.

**Events.** `CONQUEST` doubles as the territory-loss record for the previous owner. `DEFENSE`
holds one entry per qualifying capture session. `CONQUEST_REVERSED` is written by administrator
rollback, and the original conquest is then marked `reversed` instead of being deleted.

**Statistics from events.**

| Statistic | Counted from |
|---|---|
| Conquests | Non-reversed CONQUEST where the player is the new owner |
| Lost | Non-reversed CONQUEST where the player is the previous owner |
| Defenses | DEFENSE events listing the player. The kingdom gets 1 per event |
| Territory held | Live from Flan owners of the add-on's claim records (admin and excluded claims have none) |

**Kingdoms.** An FTB Teams party, identified by team UUID with its display name. The kingdom is
recorded on each event at the time it happens, while current territory uses the owner's present
kingdom. When FTB Teams or the party type is unavailable, kingdom fields stay empty and only
player statistics are shown.

**Defense credit** (`logic/DefenseRules`). Each capture session tracks four things:

- its peak progress;
- the defenders who stood in the zone while an attacker was there during the raid;
- the attackers it saw;
- whether an attacker stayed after progress stopped being allowed.

When the session ends by decaying to zero with nobody present, a defense is recorded if:

- peak ≥ `defense_minimum_capture_percent`;
- at least one defender contested;
- no attacker stayed after the raid;
- the claim's last credited defense is older than `defense_cooldown_minutes`.

Sessions ended by conquest, admin cancellation, a new raid window or claim deletion never count.

**Seasons.**

- `start` refuses while a season is running, and auto-archives an ended one.
- `end` freezes recording.
- `archive` writes the archive file and clears the active season.
- `reset confirm` writes a backup archive, then clears the events.

None of them touch claims, owners, slots, immunity, flags or configuration. Between seasons
nothing is recorded, and `/war admin status` says so.

**Reconciliation** at startup and through `/war admin stats check`:

- removes duplicate or invalid events;
- re-syncs claim owners from Flan;
- reports conquests without kingdom attribution;
- reports committed conquests that have no statistics entry. These are never invented.

## 15. Flagpoles and the capture animation (feasibility)

**What 1.16.5 allows server-side.** There are no display entities (they came in 1.19.4), and
banner blocks exist only at whole-block positions. The options considered:

| Option | Smooth? | Problems |
|---|---|---|
| **Wall banners stepping along a fence pole** (chosen) | Stepped | Plain blocks: persistent, protected by the same role index, restorable, with exact positions known server-side |
| Invisible marker armour stand wearing the banner, teleported each tick | Yes (client interpolation) | An extra entity per flag, saved with chunks (orphans after crashes), killable with `/kill`. Head-slot banner positioning can't be checked without a client |
| Client mod | Yes | Breaks the server-side-only requirement; not introduced |

**Geometry** (`flag/FlagStructure`, height H from `flagpole.height_blocks`):

- Pole blocks at B..B+H, where B is the base the player stood on.
- A wall banner on side S, the direction the player faced, at levels 1..H.
- `B.offset(S).up(level)` hangs on the pole block at the same height. Wall banners are valid on
  any solid-material block, which fences, walls and iron bars all are.
- Building only uses air blocks. Placement is rejected if the pole column or banner column
  isn't empty or isn't in the claim, so nothing is ever overwritten.
- The structure (height, side, block) is stored on the claim record, so config changes don't
  alter existing poles. Records with `poleHeight = 0` are legacy single banners and keep working.

**Protection.** Every pole block and every banner slot has role FLAG (unbreakable, can't be
built into, exploded or pushed). The block under B is SUPPORT, and the 3x3x2 space around B is
POCKET, as before. `/flag move` and admin relocation move the whole structure with the same
crash-safe orphan procedure. The restore loop rebuilds missing pole blocks, puts the banner back
at its expected level, removes stray banners from other slots, and deletes fresh banner or pole
item drops so nothing can be duplicated.

**Animation** (`logic/FlagAnimation`, tested). The frame is a pure function of the capture
fraction, the midpoint and H:

- below the midpoint: the owner's design, level = H − round(f/m·(H−1));
- from the midpoint up: the designated capturer's design, level = 1 + round((f−m)/(1−m)·(H−1)).

Blocks change only when the frame changes, so a contested capture sends no updates. Decay uses
the same function, so it runs backwards automatically. The attacker design follows the current
designated capturer. Ownership is never touched until 100%.

When a session ends (failure, cancellation, conquest or server stop), the banner returns to the
top with the current owner's design, which is the new owner's after a conquest. At startup every
flag is marked for a refresh, so a crash mid-animation is corrected when the chunk loads.

**Capture centre.** The capture centre is the ground-level base. The vertical tolerance (3 blocks
by default) applies around the base, so the raised banner never moves the zone.

## 16. Global capture time

`/war admin capturetime <seconds>` writes `capture.duration_seconds` in the server config
through Forge. That config value is the only stored copy: no runtime duplicate, it survives
restarts, and the file and the command never disagree. `reset` writes the built-in default
(90 s).

Every capture evaluation compares the running session's durations with the config. On a
difference, `CaptureModel.rescale` keeps the percentage, so the remaining time scales and the
change can never complete a capture by itself (tested). The animation follows the percentage, so
it stays in sync.

## 17. Statistics check and repair

`/war admin stats check` is read-only. Its summary covers the season, tracked players and
kingdoms, completed conquests, defenses, current conquered claims and war-log entries.

It warns about:

- duplicate or invalid event IDs;
- conquests recorded twice for one transaction;
- events from another season;
- conquest events without owners;
- orphaned claim records;
- owner mismatches with Flan;
- invalid immunity timestamps;
- incomplete conquest transactions.

Its notes cover players above a lowered limit, conquests without kingdom attribution, and
committed conquests missing from statistics. Those are reported, never invented.

`/war admin stats check <player>` gives the per-player view with consistency checks against Flan
accounting.

`/war admin stats repair confirm` applies the safe fixes and audit-logs them:

- remove duplicates;
- re-label season IDs;
- re-sync owners from Flan;
- remove records of deleted claims;
- clear invalid immunity.

At startup only the read-only check runs, and its warnings go to the log.

## 18. Kingdom identification

Kingdoms come from FTB Teams parties (`team/SideResolver`), accessed by reflection under both
known API package names. Flan trust is never used to infer kingdom membership; it only decides
claim defenders, per `trusted_defenders`. Each event records the kingdom at that moment, while
current territory uses the owner's present kingdom. Without a party, events count as individual
achievements only.

The installed FTB Teams JAR has not been inspected yet. Its exact API (`getPlayerTeam`, team
`getId`, `getType`/`isPartyTeam`, display name) should be verified against it, the same way Flan
was. `/war admin status` reports what was detected.

## 19. Flan authorization, member notices, 3-block radius

**Radius.** `capture.radius_blocks` now defaults to 3. It is still the only radius setting,
shared by detection, contesting, the ring and the readout. `min_flag_distance` defaults to 7, so
two zones never overlap.

**Who counts as authorized.** Everything is read live from Flan on every check; there is no
second permission database.

- `FlanBridge.isAuthorizedAt(claim, pos, player)` is true for the owner, a member of the claim,
  or a member of the subclaim containing `pos`.
- `allowedForEveryone(claim, pos, permission)` mirrors Flan 1.7.2's `Claim.hasPerm`: the
  subclaim's claim-wide value, falling back to the parent's when unset.

**Raid permissions.** For an authorized player, or an action Flan already allows everyone, the
add-on returns PASS: Flan's exact answer stands, never widened or narrowed, and no raid alert.
Only unauthorized players get the raid permissions and can trigger alerts.

**Explosions.** An explosion set off by an authorized player, judged by `Explosion` "placed by"
(for example their own TNT), is not reported as a raid.

**Capture eligibility.** `SideResolver.roleOf` treats Flan members of the claim or any subclaim
as neutral while `trusted_members_cannot_capture` is on: they cannot capture. Defender members
(per `trusted_defenders`) contest. Roles are recomputed at every evaluation from live Flan data,
so adding or removing someone mid-capture changes their role on the next check (at most 0.5 s),
without resetting progress, and nobody counts as both.

**Conquered claims.** Management still goes through Flan: for an ACTIVE claim the add-on passes
EDITCLAIM, EDITPERMS and EDITPOTIONS, so Flan lets the owner manage it. The only conquered-claim
restrictions are `resizeClaim` and `transferOwner`; subclaim creation is not affected. Conquest
clears old members but blacklists nobody, so the new owner can re-invite the previous owner like
anyone else.

**Member notices.** Flan 1.7.2 has no membership event, so `ClaimMixin` observes the three
mutators all paths use:

| Method | Covers |
|---|---|
| `setPlayerGroup(UUID, String, boolean)` | Add, move between groups, and remove (null group). Called by `/flan addPlayer`, `removePlayer`, `forceAddPlayer` and the group-player menu |
| `removePermGroup(ServerPlayerEntity, String)` | Deletes a group together with its members |
| `editPerms(…, String, ClaimPermission, int, boolean)` | Permission edits of a group |

The hooks work like this:

- at HEAD, the affected players' current group is remembered;
- at RETURN, the change is marked only if Flan returned true. A cancelled or failed call marks
  nothing.
- About 3 s after the last change, `MembershipChange.classify(before, after, groupEdited)`
  yields ADDED, REMOVED, CHANGED or NONE. NONE covers, for example, "removing" a non-member,
  which Flan reports as success, or adding and removing within seconds.

Offline players get one net notice per claim, merged with `MembershipChange.combine`: added then
removed cancels out, and removed then re-added becomes "permissions updated". At most 20 are
kept, and they are delivered at login.

Conquest clears members directly, not through Flan's mutators, so it sends its own "access
ended" notice before the wipe.

**Limitation.** A Flan permission edit that changes nothing still marks the group as edited.
Flan reports success without saying whether the value changed, so an occasional redundant
"permissions updated" message is possible.
