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

## 20. Flan permission GUI integration, CANSTAY, migration and build numbers

**What the Flan menus change** (verified in Flan 1.7.2):

- `Claim.playersGroups` is a `Map<UUID, String>`: one group per player per claim. Subclaims have
  their own map.
- *Claim-Menu → Edit Permissiongroups* uses `GroupScreenHandler`, `GroupPlayerScreenHandler` and
  `PermissionScreenHandler`, plus the `/flan` group, player and permission commands. They all end
  in the three methods hooked in section 19.
- A member's effective permission, as `Claim.canInteract` decides it: the group's value if the
  group sets the permission, otherwise the claim-wide value (a subclaim falling back to its
  parent). The owner may do everything.

**Notices.** `FlanBridge.effectivePermissions` reproduces that rule for all group-editable
permissions; Flan's global permissions are excluded. It is evaluated before the first edit and
after the last committed one.

| Situation | Notice |
|---|---|
| Not a member before, member after | ADDED, plus whether the group allows entering |
| Member before, not a member after | REMOVED |
| Member both times, effective permissions differ | CHANGED, plus a readable diff (CANSTAY phrased as entering, others listed) |
| Member both times, effective permissions identical | Nothing: a group switch or toggle with no effective difference isn't meaningful |

This also removes the redundant "permissions updated" message that section 19 listed as a
limitation.

Edits to claim-wide (global) permissions are not hooked; members whose group leaves such a
permission unset are affected without a notice.

**CANSTAY.** Flan enforces it in `EntityInteractEvents.updateClaim`, every tick, by teleporting
out any player for whom `canInteract(player, CANSTAY, pos)` is false. During raids the add-on
grants CANSTAY to outsiders in raidable claims (`raid.permissions.allow_entering`), otherwise a
claim that denies entry could never be raided or captured. CANSTAY is never a hostile action.
Authorized members keep Flan's own answer. Defender and capture eligibility use explicit
membership only, never public permissions.

**Migration.** `general.config_version` (0 = before versioning) is compared with
`CURRENT_CONFIG_VERSION`. Every value still exactly at an old default moves to the new default,
the change is logged with old and new values, and the version is saved. That makes it run once,
idempotently. Admin-changed values are kept. A value set by hand to exactly the old default can't
be told apart and is migrated; this is documented in the README, and setting it once more makes
it stick. War data (`war_data.json`, format version 1) needs no migration: new fields default to
empty, and a `.bak` of the previous file is kept on every save.

**Build numbers.** The version is `mod_version` plus `-b<GITHUB_RUN_NUMBER>` (for example
`1.1.0-b7`). GitHub increments the run number for every run and never reuses one. It appears in:

- the jar name and the jar's `Implementation-Version` (and therefore in Forge's mod list);
- the startup log;
- `/war admin version` and `/war admin status`;
- `/war status`.

Local builds show "local".

## 21. Kingdom ranks, chat tags and nametags

**Data** (`war_data.json`, new fields default to empty, so old files load unchanged):

- `rankRegistries`: main-claim UUID mapped to a `RankRegistry`, which holds:
  - `ranks`, an ordered map of stable id (`r…`) to `RankDef`. A `RankDef` has a name, colours
    (one colour, or 2 to 5 gradient stops), an effect, a prefix, a suffix and a default position.
  - `assignments`: player UUID to rank id.
  - `positionOverrides`: player UUID to LEFT/ABOVE/BELOW.
- `mainClaims`: player UUID to the main claim they designated.
- On load, null collections are repaired and assignments to missing rank ids are dropped.

Ids never change, so renaming a rank keeps every assignment. Deleting a rank removes its
assignments.

**Authority, read live from Flan on every check (no copy that could disagree):**

- **Manager:** the current Flan owner of the registry's claim, or an admin
  (`general.admin_permission_level`).
- **Command context:** a registry claim the player owns and stands in, otherwise their designated
  main claim if they still own it. Admins: any registry claim they stand in, or
  `/war admin rank …` with a kingdom argument.
- **Eligible holder:** the claim's owner, or a player with an explicit Flan group in the claim or
  any of its subclaims (`FlanBridge.isMemberAnywhere`). Players who only have public
  permissions are not members.
  - If someone is no longer eligible, their assignment is kept but `active()` returns nothing,
    so they're **suspended**.
  - When they become a member again, the rank reappears within 2 seconds.
  - With `require_membership = false`, anyone may hold a rank.
- **One rank per player server-wide.** Owners cannot take members of another kingdom. Admins
  can, and the old assignment is removed.
- **Ranks never call into Flan's permission API.** Nothing is granted or denied by a rank.

**Conquest and deletion:**

- **Conquest:** management follows `claim.getOwner()`, so a conquest hands the registry to the
  conqueror automatically. `ConquestService` calls `RankService.ownerChanged` only to notify the
  two players. A rollback reverses it the same way. Assignments stay; holders who aren't members
  after the transfer are suspended.
- **Deletion:** when `ClaimLifecycle.reconcileAll` finds the claim gone, it calls
  `RankService.claimDeleted`, which removes the registry and the `mainClaims` pointers.
  `pruneOrphans` does the same at startup.

**Rendering** (`RankRenderer`, `RankColors`):

- Text is `prefix + " " + name + " " + suffix`, one colour per code point.
  - A gradient interpolates linearly across all characters.
  - Runs of equal colour are merged into one component.
  - Colours are 1.16 RGB `Color.fromInt`, understood by unmodified clients.
- **Effects:**
  - BOLD sets the bold style.
  - GLOW brightens the colours by 35 % and adds bold. Vanilla has no text glow; entity glowing
    only outlines a model, and the labels have none.
  - OBFUSCATED scrambles the name only, keeping the decorations readable.
  - RAINBOW, PULSE and WAVE are frames: `RankDisplay.phase()` advances one twelfth of a cycle
    every `animation_update_interval_ticks` (default 20 ticks, so one cycle takes 12 s).
  - A frame is sent only if its JSON differs from the last one sent.
- **Validation:**
  - Names: letters, digits, spaces and `- _ ' . &`; no double spaces; max length.
  - Decorations: control, format, private-use and unassigned characters are refused, as is `§`.
    Code points above U+FFFF are refused too, because the 1.16 font can't draw them. An optional
    symbol allow-list applies.

**Display** (`RankDisplay`):

| Position | Mechanism | Notes |
|---|---|---|
| LEFT | Per-player scoreboard team `fwr` + 13 hex chars of the UUID, with the tag + separator as prefix | Vanilla shows it on the nametag and in the tab list. Players already in a foreign team are left alone and fall back to BELOW. Our teams are removed at start and stop, and only names matching `fwr[0-9a-f]{13}` are ever touched. |
| BELOW | Marker armour stand (invisible, no gravity, invulnerable, zero size) riding the player | Passenger offset 0.75 × player height, so the name is about 1.85 blocks above the feet, under the player's own name at 2.3. |
| ABOVE | Two radius-0 area-effect clouds (height 0.5, no particles, infinite duration), then a marker stand, stacked on the player | Name at about 2.6 blocks, above the player's name. |

Why the label entities are safe:

- **No hitbox.** Marker stands and clouds aren't `canBeCollidedWith`, so they never absorb
  attacks, arrows or clicks in PvP.
- **Smooth movement.** Passengers are positioned by each client from the vehicle every frame.
- **Not saved to chunks while riding.** Players aren't saved in chunks, and passengers are saved
  only inside their vehicle. A player's own file can list them under `Passengers`, but player
  loading ignores that list.
- **Strays are refused.** Every label carries the tag `flanwarfare_rank_label`. A tagged entity
  this session didn't create is cancelled in `EntityJoinWorldEvent`, also before the add-on is
  ready, so crash leftovers are dropped from chunks.
- **Removal:** on logout, death, respawn (new player object), dimension change and server stop.
- **Validation every 5 ticks.** It checks each link (alive, same world, riding the right
  entity); a broken chain is rebuilt. Vanilla dismounts living passengers under water, so a
  chain broken while the player is in water is held until they leave it.
- **Hiding:** sneaking or invisible players get `customNameVisible = false`. Spectators and dead
  players get no label.

Per-player state is recomputed every 40 ticks and immediately after any rank change, which
covers Flan membership changes and config reloads. Position resolution is `RankRules.resolve`:
the personal override if allowed, then the rank default, then the global default. ABOVE or BELOW
fall back to LEFT when the server disables them.

**Chat.** `ServerChatEvent` (normal priority) replaces the component with the configured format:

- literal text in grey;
- the tag at the current frame;
- the player's plain name;
- the message through `ForgeHooks.newChatWithLinks` (Forge's own URL handling).

Players without an active rank keep the vanilla line. The config value is validated when it's loaded (each
placeholder exactly once). Forge replaces an invalid format with the default.

**Commands:** `/war rank …` and `/war admin rank …` (see the README). Rank arguments accept
quoted names or ids. Player arguments use `GameProfileArgument`, so offline players known to
the profile cache work.

**Limitations:**

- **Fixed label heights.** Vanilla offers no per-entity name offset.
- **Chat shows one frame.** It cannot animate after sending.
- **Display names.** A LEFT prefix also appears wherever vanilla prints the display name (death
  messages, for example).
- **Untested in game.** The display mechanics follow vanilla 1.16.5 behaviour, but no in-game
  test has run yet (see `docs/TESTING.md`, F1–F26).

## 22. Flag model (Flan Warfare Flags companion mod)

**Before:** flags were vanilla blocks placed by the server: pole blocks B..B+height (dark oak
fence by default) and one wall banner beside the pole. Its level is the stepped capture
animation, and its design comes from `ClaimRecord.designSnbt` (a banner item SNBT: colour plus
`BlockEntityTag.Patterns`). There was no client code: a server-only mod can't add models.

**New companion mod** (`flags-mod/`, modid `flanwarfareflags`, installed on server and clients):

- **Block `flanwarfareflags:flag`**, with `facing` (horizontal) and `part` (0..5):
  - Render type INVISIBLE, no drops, unbreakable in survival, no item.
  - Shapes: `part` 0 is the pedestal (16×2×16 + 8×4×8) plus the 2×2 pole; `part` 1..5 are the
    2×2 pole only. Collision equals these shapes.
- **Tile entity on `part` 0**, holding `Base` (dye id), `Patterns` (vanilla banner pattern list)
  and `Raise` (0..1). It syncs through the standard update tag and packet.
  `getRenderBoundingBox` covers about 3×9×3 blocks, and the renderer is a global renderer, so
  the tall model isn't culled with its base chunk section.
- **`FlagRenderer`** (client package, registered in `FMLClientSetupEvent` through
  `DistExecutor`, so it is never loaded on a dedicated server) draws, in pixels for a south-facing
  flag:

| Part | Size (px) | Position (y, px) |
|---|---|---|
| Lower pedestal | 16 × 2 × 16 | 0 – 2 |
| Upper pedestal | 8 × 4 × 8 | 2 – 6 |
| Pole | 2 × 2 | 6 – 94 |
| Banner (raised) | 16 × 32 × 0.8 | 58 – 90 (exposed shaft below: 52 px = 3.25 blocks = 1.6 × fabric) |
| Crossbar | 20 × 2.5 × 3.6 (1.25 blocks) | 90 – 92.5 |
| End caps | 3 × 3.5 × 4.5 | 89.5 – 93 |
| Tabs | 4 × (1.5 × 2) | 88 – 90 |
| Finial | 4 × 3 × 4 + 2 × 2 × 2 | 94 – 99 |
| **Total** | **20 × 99 px** | **6.19 blocks tall** |

  The parts use `textures/entity/flag_parts.png` (wood, dark wood, stone, gold), split into
  16 px pieces so there's no texture stretching.

  The fabric is the vanilla banner flag model (20 × 40 × 1) scaled 0.8 and drawn by vanilla's
  banner pattern renderer (`BannerTileEntityRenderer.func_230180_a_`). That gives identical
  designs and both faces. Its back is 0.3 px in front of the pole, so nothing z-fights.

  Facing rotates the whole assembly about the block centre. The banner front points to the
  flag's side, the direction the player faced on `/flag place`.

- **Smooth `Raise`:** the client moves the drawn height towards `Raise` at 0.4 of the travel per
  second, so the server's 24 capture steps look continuous.

**Flan Warfare integration** (no compile-time dependency; the block is found through the
registry, and data is written as NBT):

- **`FlagStructure`:** a model flag has `poleBlock = flanwarfareflags:flag` and `height` 5. Its
  column is B..B+5, `poleStateAt` sets `part` and `facing`, it has no banner slots, and it uses
  24 animation levels.
- **`FlagService`:**
  - `build` and `restore` place the column states.
  - `applyCurrent` writes `Base`, `Patterns` and `Raise` into the tile entity, and only when
    they changed.
  - `intact` also requires the tile entity.
  - Protection, the index, orphans and relocation work unchanged through `poleBlocks()` /
    `allBlocks()`.
- **`convertIfNeeded`:** once per flag per run, never during an animation, and only if the new
  column is free, flags are rebuilt in the configured form through the normal relocation path.
- **`CaptureManager`:** uses `FlagStructure.animationLevels()`.

**Verified:**

- Both projects compile on GitHub Actions against Forge 1.16.5-36.2.39, including
  reobfuscation, and Flan Warfare's logic tests pass.
- A geometry check plotted the renderer's cuboids from the same numbers, front and back
  (matplotlib, not Minecraft).

**Not verified (needs a client):** the in-game look, lighting, rotation for all four facings,
render bounds at the screen edge, and the animation. See `docs/TESTING.md`, M1–M14.

## 23. Viewer-specific capture HUD

**Before:** one boss bar per capture session, the same for everyone in range: red while
capturing, yellow while contested, white while decaying. The title was the claim label, state,
percentage and capturer.

**Now:** `CaptureHud` (one per session, created in `CaptureManager.open`, closed with the
session) resolves each viewer's perspective at every capture evaluation:

- **DEFENDER:** the current Flan owner, `SideResolver.sameTeam(viewer, owner)`, or
  `FlanBridge.isMemberAnywhere(claim, viewer)`. Invited members count with any permission
  group, also members of subclaims.
- **ATTACKER:** the viewer's `SideResolver.sideOf` is one of the attacking sides seen by the
  capture model during this session (`Presence.attackerSides`, recorded through
  `noteAttackers`).
- **NEUTRAL:** everyone else within the existing boss-bar distance.

`HudView` (pure, tested) maps the perspective and `CaptureModel.Status` to the presentation:

- **Trend:** CAPTURING is increasing, CONTESTED is contested, DECAYING or IDLE is decreasing.
  It is taken from the authoritative model state, never from comparing percentages.
- **Tone, status, subtitle and arrow:** as in the README table. Arrows follow the shared
  progress, not the viewer.
- **Fill:** `round(fraction × width)`, left-anchored, identical for every viewer.

Names and banners:

- **Names:** `SideResolver.kingdomOf(player).name` (the FTB Teams party name), falling back to
  the player's name.
  - The attacker is the session's designated capturer, or the last one if nobody is designated
    right now.
  - The defender is the claim owner.
- **Banners:** the defender's is `ClaimRecord.designSnbt`, the claim's flag design. The
  attacker's is the designated capturer's saved design (`FlagService.designFor`). Both are sent
  as `Base` and `Patterns`, so the state colour never tints a banner.

**Delivery.** With `flanwarfareflags` loaded on the server (Forge then requires it on every
client), each viewer gets a vanilla `SCustomPayloadPlayPacket` on channel
`flanwarfareflags:hud`:

- **update:** type 0, claim UUID, perspective, trend, fraction (float), attacker name, defender
  name, attacker banner NBT, defender banner NBT;
- **remove:** type 1, claim UUID;
- **when updates are sent:** only when something visible changes (0.1 % steps), plus a
  keep-alive every 2 s;
- **removal:** a remove is sent when the viewer leaves range or the session ends (completed,
  failed, cancelled or server stop). The client also drops a panel that hasn't been updated for
  5 s.

The client (`CaptureHudClient`) draws one compact panel near the top centre. It prefers a
capture in which the viewer is attacker or defender, otherwise the most recently updated one:

- **Width:** at most 280 GUI px, and at most the screen width minus 8.
- **Banners:** real banner items in the end frames.
- **Long names:** trimmed with "..." so they never reach the status.
- **Contested:** the bar is striped.
- **Trail:** a fading trail (red for raiders, green for defenders) covers the lost range for
  1.5 s whenever the fraction drops. It is never a second meter.

Without the flags mod there are three vanilla boss bars per session, one per perspective
(GREEN/RED/YELLOW/WHITE), and each viewer is in exactly one. The title is "ATTACKER   STATUS
» 62%   DEFENDER".

Capture rules, speed, contest and decay are unchanged.

## 24. Capture radius 8 and `/flanwarfare capturezone`

- **Default radius:** `capture.radius_blocks` defaults to **8** (range 1–32). It is still the
  single radius read live by detection, contesting, the particle ring and the action-bar
  readout. The vertical rule (`capture.vertical_tolerance_blocks`, default 3) is unchanged.
- **Migration:** config version 3 moves a radius still at the old default 3.0 to 8.0, once. A
  radius set to anything else is kept.
- **Command:** `/flanwarfare capturezone radius [blocks|reset]`, also under `/war admin`,
  needs the admin permission level. It writes the config value and saves it. Every flag uses
  the new radius from the next evaluation (half a second at most); running captures keep their
  percentage. The change is audit-logged.
- **Ring:** it uses at least one particle per block of circumference (51 at radius 8).
- **HUD range:** it covers at least the radius plus 8 blocks, so everyone inside a large zone
  always sees it.
- **Overlap warning:** `WarConfig.validate` warns when `min_flag_distance` is below twice the
  radius, because zones of nearby flags then overlap.

## 25. Flag Designer

### 25.1 Fixes in Flan Warfare 1.2.1 / Flags 1.2.0 (root causes)

| Symptom | Root cause | Fix |
|---|---|---|
| Save Flag crash "String too big (was 17 bytes encoded, max 16)" in `mouseClicked` | `DesignerClient.sendSave` wrote each layer's pattern id with `writeString(id, 16)`. Vanilla ids are 1–3 characters (checked in the 1.16.5 bytecode: `BASE("base","b")`), but mods register patterns through Forge's `BannerPattern.create` with free-form ids, and one used in your pack is 17 bytes. The encoder threw on the client thread, inside the click handler. | Limit `DesignRules.MAX_ID_LENGTH = 64` characters, written and read with the same rule on both sides (`readString(64)` on the server). The client checks every id first and shows an error instead of throwing. The server still accepts only ids of patterns registered in the game. Ids stay as strings, not numbers, because enum ordinals can differ when mods add patterns, while the NBT id is what the banner itself stores. |
| Cancel did nothing (and could not close) | The button called `Screen.onClose()`, which in these mappings is the lifecycle hook run *after* a screen is replaced. The method that closes is `closeScreen()`. Escape used the right one. | Cancel, Escape and the inventory key all call `closeScreen()`. Cancel never sends a packet. |
| Clicks near Cancel/Save hit patterns | The click handler tested the pattern grid before the buttons, and the grid had overflowed over them. | The footer buttons are tested first. Every component has fixed bounds, and one click reaches exactly one component. |
| Pattern grid overflowing the window | The grid had a fixed 4 × 8 layout. Every pattern of every installed mod was added to it, so extra rows spilled over the item row, the layers and the buttons. | The grid is a fixed 8 × 3 viewport with whole-row scrolling (wheel and draggable scrollbar), clipped with a GL scissor scaled by the window's GUI scale. Clicks are mapped only inside the viewport, to visible cells. |
| Item icons above other elements | Patterns were drawn as `ItemStack`s through the item renderer, at its own Z levels. | Icons are flat banner faces drawn from the banner pattern atlas (`Atlases.BANNER_ATLAS`, front-face UVs of each pattern sprite, tinted like the world renderer), at the panel's depth. |
| Possible server error on open with a long claim name | The label was written with `writeString(label, 64)`, which counts bytes. A long or non-ASCII name could throw on the server. | The label is clipped to 64 characters (`DesignRules.clip`, which never splits a surrogate pair) and read with `readString(64)`. Result messages are clipped to 256 the same way. |

### 25.2 Opening

- **Index.** `FlagService.flagBlockIndex` maps dimension → block → claim, covering every block
  of every flag (model column or fence pole, plus banner slots).
- **Click handling.** `WarEvents.onLeftClickBlock` (server, priority HIGH) cancels the click
  on any flag block, so nothing is mined or moved, including in creative.
- **Selection box.** The flags mod's `FlagBlock.getShape` adds a thin plate over the raised
  banner, so the cloth can be clicked. Collision stays the pedestal and pole.
- **Authority.** `mayEdit` = the live Flan owner, or an admin when
  `flags.designer_admin_override` is on (default off).
- **Session and container.** The server stores
  `Session{claimId, dimension, flagPos, random token, 15 min}`. It then calls
  `NetworkHooks.openGui` with the Flags mod's container type `flanwarfareflags:designer`
  (looked up by registry name, with no compile dependency). The extra data is: claimId, token,
  label (≤64 chars), {Base, Patterns}, max layers.

### 25.3 Container and the Banner Pattern Item slot (`flags-mod/FlagDesignerContainer`)

- **Slots.** Slot 0 accepts only `BannerPatternItem`s, a stack of 1. Slots 1..36 are the
  player's inventory, placed off-screen.
- **Moving items.** The screen moves items only with vanilla `QUICK_MOVE` window clicks: the
  slot when filled, a chosen inventory slot when empty. The server runs
  `transferStackInSlot` and syncs as for any container, so nothing can be duplicated and
  nothing goes out of sync. Hotbar swaps, drops and cursor pick-ups are blocked in the screen,
  so no item ever sits on the cursor.
- **Closing.** `onContainerClosed` calls `clearContainer`: the item goes back to the inventory,
  or drops at the player's feet if it is full. This runs on Cancel, Escape, a successful save,
  death (`canInteractWith = isAlive`) and logout (`FlagDesigner.onLogout` calls
  `closeContainer()` before player data is saved).
- **Item-locked patterns.** These are every pattern applied by any registered
  `BannerPatternItem` (vanilla's six plus mods'). Client and server compute the set the same
  way, from the item registry.

### 25.4 Screen (`flags-mod/client/FlagDesignerScreen`, a `ContainerScreen`)

- **Window.** 312 × 234 scaled pixels, centred. Minecraft keeps the scaled screen at least
  320 × 240 at every GUI scale, so it always fits.
- **Layout rectangles** (relative to the window):
  - preview: 7,20 90×172, with reset 85,194;
  - colours: 102,19 204×46;
  - pattern grid: 106,80 135×80, with scrollbar 242,80;
  - item panel: 248,66 58×97 (slot at 269,91, the add box at 269,121);
  - layers: 102,165 204×42;
  - Cancel: 6,210 147×20;
  - Save Flag: 158,210 148×20.
  - A layout script checked that every rectangle lies inside its panel and that none overlap.
- **Input.** Footer, then popup or message, then preview, colours, grid, item panel, layers.
  Nothing is handed to vanilla slot handling. Dragging the preview or scrollbar and scrolling
  never trigger a button.
- **3D preview.** Unchanged in approach: the world renderer's static `drawStructure` and
  `drawFabric`, its own camera, and a scissor matching the GUI scale. Auto-turn is 24°/s,
  pausing while dragging and for 2.5 s after. Pitch is clamped −30..50° and zoom 0.6..1.8.
- **Saving.**
  - The client first checks the item rule itself, for a clear message.
  - It then sends the save, keeps the window open with "Saving...", and closes only on the
    server's OK.
  - On an error it shows the message and keeps the edit.
  - With no answer in 10 s, it re-enables Save.

### 25.5 Save (`CCustomPayloadPacket` on `flanwarfare:designer`)

- **Packet:** UUID, long token, varint base, varint count (≤16 on the wire), then per layer
  (string id ≤64, varint dye). Anything malformed is logged and dropped.
- **Server checks, in order:**
  1. The session and token match, and the session hasn't expired.
  2. The claim and flag exist.
  3. The flag hasn't moved.
  4. The sender is still the owner.
  5. The designer container is still open.
  6. `DesignRules.check` passes: colours 0..15, at most 6 layers, ids registered and not base,
     and an item-locked id only if the server-side slot holds its item or the flag already
     had it.
- **Result.** `FlagService.setClaimDesign` sets `ClaimRecord.designSnbt` (persisted, carried by
  `/flag move`) and redraws the flag through the normal path, so nearby clients get block or
  tile entity updates.
- **Reply.** Type 1 (ok, message ≤256).

### 25.6 Tests

`LogicSelfTest.designRules()` has 19 checks (209 in total). They cover:
- empty, 6-layer and 7-layer designs;
- bad colours, unknown, base and over-long ids;
- a 17+ character modded id being accepted;
- vanilla and modded item patterns, with and without the item;
- the `clip` helper.

## 26. Flan Warfare Designer: a separate mod (Designer 1.0.0 / Flags 1.4.0 / Flan Warfare 1.4.0)

### The three mods
- **Flags 1.4.0** has exactly the source of build #22 (Flags 1.0.0): the flag model and the
  capture HUD only. All designer code was removed from it.
- **`designer-mod/` (`flanwarfaredesigner`)** contains:
  - the `FlagDesignerBlock` (craftable, axe, drops itself);
  - `FlagDesignerContainer` (the Banner Pattern Item slot);
  - `FlagDesignerScreen` and `DesignerClient`;
  - `PreviewRenderer`, a copy of the flag model geometry for the 3D preview, with its own
    `flag_parts.png`. The mod has no dependency on the Flags mod.
- **Registry and channel ids:**
  - block and item: `flanwarfaredesigner:flag_designer`;
  - menu: `flanwarfaredesigner:designer`;
  - result channel: `flanwarfaredesigner:designer`;
  - saves still go to `flanwarfare:designer`.
- **Recipe:** gold ingot, any banner (`#minecraft:banners`), gold ingot / planks, loom, planks
  / three planks.

### Server (Flan Warfare)
- **Opening.** `WarEvents.onRightClickBlock` (priority HIGHEST) catches right-clicks on a Flag
  Designer and cancels them with SUCCESS. Sneaking with an item in hand is left alone, so blocks
  can still be placed against it. It then:
  1. finds the parent claim at the block (`FlanBridge.parentAt`);
  2. requires a conquest flag (not an admin or excluded claim, and `hasFlag`);
  3. opens the designer for that claim, applying `designer_station_access`.
- **Save checks,** in addition to §25.5:
  - the block still exists;
  - it still stands in the same claim;
  - the player is within 8 blocks of it.
- **Removed:** admin station linking and the link data from 1.3.0. Old war data files that
  contain `designerStations` still load; the field is ignored.
- **Left-click:** left-clicking a flag does nothing designer-related unless
  `designer_open_on_flag_left_click` is on.

## 27. Claim teleports beside the flag; preview backdrop (Flan Warfare 1.5.0 / Designer 1.1.0)

### Teleports
Flan 1.7.2 (`PlayerClaimData`, checked in its bytecode) queues `/flan teleport` with
`setTeleportTo(home)` and sets `trappedTick = 101`. Every tick it decrements the counter. At 0
it teleports to `home` after climbing the **MOTION_BLOCKING heightmap** column until the player
fits. With the home at the flag base (set by conquest), that climb ends on top of the pole or
banner, or on the roof.

`PlayerClaimDataMixin` injects at the HEAD of `tick(Claim, Consumer)`. When `tpPos != null` and
`trappedTick == 1` (the teleport tick), it calls `ClaimTeleport.teleportNow`:
1. It finds the parent claim at `tpPos`. If that claim has a Flan Warfare flag in this
   dimension, Flan Warfare takes over the destination.
2. It searches for a safe spot: `TeleportSpots.offsets` gives 8 then 16 positions around the
   base, each tried at dy 0, −1, +1. A spot must:
   - be in the same claim;
   - not be a flag block, or have one above or below it;
   - have a floor that `isSolidSide(UP)` and isn't on the hazard list;
   - leave a 0.6 × 1.8 box free of collisions and liquids.
3. It teleports with `ServerPlayerEntity.teleport`, facing the flag (stopping any riding and
   resetting fall distance), or cancels with a message.
4. Either way it clears `tpPos` and sets `trappedTick = 0`, so Flan does nothing more.

Claims without a flag aren't handled, and Flan teleports normally.

### Backdrop
- **Texture:** `designer-mod/.../textures/gui/preview_backdrop.png`, 512 × 256, original,
  generated by `designer-mod/make_backdrop.py`. It wraps seamlessly and has a Gaussian blur
  (σ 1.7) baked in.
- **Filtering:** its `.mcmeta` turns on linear filtering. The texture repeats (clamp off), so
  panning wraps around.
- **Drawing:** it is blitted into the preview box only, at z 0, before the model. The u offset
  is ¼ of the yaw. A soft vignette sits at the top and bottom. The model is drawn afterwards,
  sharp, with the same scissor as before.
