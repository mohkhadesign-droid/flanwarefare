# Technical notes: Flan integration, accounting and recovery

Everything below was established by disassembling the installed
`flan-1.16.5-1.7.2-forge.jar` (`javap -c -p`), not from Flan's source history.

## 1. Feasibility findings

| Question | Finding in Flan 1.7.2 | Consequence |
|---|---|---|
| Can raid access be granted dynamically? | `Claim.canInteract(player, perm, pos, msg)` posts `PermissionCheckEvent` **first**, before its admin-claim, owner and group checks. The default result is `PASS`; `SUCCESS` allows, `FAIL` denies. | All player permissions are decided in one Forge listener. No claim data is changed, so protection returns the moment a raid ends. The listener must skip admin claims itself, because the event fires before Flan's admin check. |
| Do world effects reach the event? | No. Explosions, fire spread, pistons, fluids, mob spawns and lightning call `canInteract(null, perm, pos)`, which never posts the event. | One `@Inject` at the head of `Claim.canInteract`, active only for a null player and only when configured. Off by default. |
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
| `ClaimMixin` | `Claim.canInteract(..)Z` at HEAD, null player only | Optional world effects during raids. |
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
