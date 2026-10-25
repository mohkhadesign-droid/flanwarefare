# Test plan and results

**Honest status:**

- **Executed and passing:** the automated logic tests (`./gradlew logicTest`, also run by
  `./gradlew build`), plus a mechanical check that every Flan stub member matches the real
  Flan 1.7.2 JAR (85 of 85 members identical).
- **Not yet executed:** every in-game scenario below. They need a running server, which the
  environment used to write the mod could not provide. Each one is marked NOT RUN, with its
  expected result.

Run the in-game tests on a **copy** of the server (same mods and configs, a copy of the world)
with at least two accounts, A and B. Set `raid.mode` with `/war admin mode` and move time with
`/time set` to reach raid and non-raid states quickly.

## Automated (executed)

| Test | How | Expected | Actual |
|---|---|---|---|
| Clock conversion, 06:00=0 … 05:00=23000; format round-trip; invalid input rejected | `LogicSelfTest.clockConversion` | exact ticks | **PASS** |
| Windows crossing 06:00 (15-06, 18-05) and same-day windows (08-20), end exclusive | `windowsCrossingSunrise`, `windowsSameDay` | membership as specified | **PASS** |
| Time to next transition; one window id across the 06:00 wrap; new id next day | `transitions` | as specified | **PASS** |
| Sleep clamp: no skip during a raid; skip stops at raid start; unaffected otherwise | `sleepClamp` | as specified | **PASS** |
| Solo capture completes after exactly 180 s | `soloCapture` | COMPLETE at 3600 ticks | **PASS** |
| Defender pauses without resetting; resumes after leaving | `defenderPauses` | as specified | **PASS** |
| Two sides present: no gain | `rivalsContest` | CONTESTED, unchanged | **PASS** |
| Rival alone erodes, never inherits; starts fresh at 0 | `rivalCannotSteal` | B's progress = only its own 210 ticks | **PASS** |
| Attackers leave: full bar drains in 60 s; session ends at 0 | `decayWhenAttackersLeave` | 75% to 50% after 15 s | **PASS** |
| After the raid: decays even with attackers present, never completes | `decayAfterRaidEvenWithAttackers` | as specified | **PASS** |
| Capturer: initiator kept; then longest present; returning player does not regain the role; UUID tie-break; reset on side change | `capturerPolicy` | as specified | **PASS** |

| Defender entering at 99% prevents completion and freezes progress | `defenderAt99Percent` | CONTESTED, 3560/3600 kept | **PASS** |
| Owner steps out for exactly one evaluation, then back in | `ownerExitsAndReenters` | gains exactly one step, then paused | **PASS** |
| Two defenders alternating (always one present) | `alternatingDefenders` | stays paused | **PASS** |
| Attacker and defender enter on the same evaluation | `simultaneousEntry` | CONTESTED, 0 progress | **PASS** |
| Raid ends while contested | `raidEndsWhileContested` | decays 50% to 25% in 15 s | **PASS** |
| Attackers leave, defender stays | `defenderAloneDecays` | normal abandonment decay | **PASS** |
| Zone geometry; every aura point on the capture radius; distance readout | `zoneGeometry` | as specified | **PASS** |
| 50 blocks broken in 25 s: 1 title, at most 1 hint per 5 s; new title after cooldown; contest throttle | `alertThrottle` | 1 title, 4 hints | **PASS** |
| Live state derivation (capture beats raid activity; raid window alone is PEACEFUL) | `warStates` | as specified | **PASS** |

| 90 s of uncontested time completes; 15 s of contest in the middle does not count | `ninetySecondCapture` | completes at exactly 90 s uncontested | **PASS** |
| Immunity durations: 24h, 90m, 1d12h, 0; invalid input rejected | `durations` | as specified | **PASS** |
| Defense qualification: threshold (incl. exactly 10%), contest required, raid-end-with-attackers excluded, cooldown | `defenseRules` | as specified | **PASS** |
| Statistics exactly once: replayed duplicate and reversed conquests ignored; losses; held vs total; history survives losing territory | `statsExactlyOnce` | as specified | **PASS** |
| Defense credit per defender, one per kingdom; historical kingdoms kept | `statsKingdoms` | as specified | **PASS** |
| Ranking order and tie-breaks | `rankingOrder` | as specified | **PASS** |

| Vertical tolerance: 3 below/above inside, tunnel/roof outside, standing next to the raised banner outside | `zoneGeometry` | as specified | **PASS** |
| Capture time 90 s -> 120 s at 50%: percentage kept, exactly 60 s more; shortening never completes | `captureTimeChange` | as specified | **PASS** |
| Flag animation frames: 0/20/49/50/70/100%, monotonic descent, decay below midpoint shows owner | `flagAnimation` | as specified | **PASS** |

| Membership net change: added, removed, group change, group edit, no-op silent, offline merge rules | `membershipChanges` | as specified | **PASS** |

| Effective-permission diff wording (CANSTAY gained/lost, lists), no diff -> no lines; group switch with identical permissions is silent | `permissionDiff`, `membershipChanges` | as specified | **PASS** |

Result of the last run: `LogicSelfTest: all 143 checks passed` (locally, and on every GitHub
Actions build as part of `./gradlew build`).

## In-game scenarios (all NOT RUN)

| # | Scenario | How to test | Expected |
|---|---|---|---|
| 1 | New claim is `PENDING_FLAG` | A claims land. | Chat warning; `/flag info` shows INACTIVE. |
| 2 | No protection before flag | B breaks blocks and opens a chest in A's claim, outside raid hours. | Allowed. Flight and no-hunger perks are not granted. |
| 3 | No management before flag | A tries `/flan` trust, permissions, name, home, subclaim, resize, transfer, and the GUI menu. | All refused ("inactive until its flag is placed"). |
| 4 | Placement | A stands in open space inside the claim and runs `/flag place`. | Banner appears; claim ACTIVE. |
| 5 | Activation immediate | B retries #2; A retries #3. | B denied by Flan; A can manage. |
| 6 | Grace expiry does not activate | Set `placement_grace_seconds = 30`, wait. | Still INACTIVE; reminders change wording. |
| 7 | Inactive expiry | Set `inactive_claim_expiration_seconds = 120`, both values of `auto_abandon_expired_claims`. | `false`: listed in `/war admin status`. `true`: deleted through Flan, and A's used blocks drop. |
| 8 | Move outside raids | `/flag move` elsewhere in the claim. | Old banner gone, new one placed, no item drop; cooldown message on repeat. |
| 9 | Move during raid | `/war admin mode always`, then `/flag move`. | Refused. |
| 10 | Flag resists destruction | Mine it (survival and creative), TNT, creeper, piston push/pull, water/lava, break the support, place blocks in the 3x3 space, a fake-player machine (e.g. a quarry), `/setblock` it to air. | Events cancelled. `/setblock`: rebuilt within 5 s; any dropped banner item removed. |
| 11 | Raid starts on time | `CUSTOM 15:00-06:00`; `/time set 8999`, then wait. | Announcement at 15:00 (tick 9000); warnings at 60 s and 10 s before. |
| 12 | Raid access | B breaks, places and loots in A's active claim. | Allowed; doors/buttons only if `allow_interactions`. |
| 13 | Protection returns | `/time set 0`. | Announcement; B denied immediately. |
| 14 | Admin claims protected | B inside the spawn admin claim during a raid. | Denied; no capture; `/flag place` refused. |
| 15 | Capture starts | B within 2 blocks of A's flag during a raid. | Boss bar visible to nearby players; A notified. |
| 16 | Boss bar accuracy | Stopwatch. | About 1% per 1.8 s; 100% at 180 s. |
| 17 | Defender pauses | A, or A's teammate, or a Flan member with EDITPERMS, enters the radius. | "Contested"; percentage frozen. A member with only container trust does **not** pause it. |
| 18 | Rival contest | B and C (different FTB parties) both in the radius. | Contested; nobody gains. |
| 19 | Leaving decays | B walks away at 50%. | Drains to 0 in about 30 s. |
| 20 | Raid end decays | Reach 75%, then `/time set 0` with B still there. | Drains to 0; never completes. |
| 21 | No gain outside raids | B stands at the flag outside a raid. | No session starts. |
| 22 | Conquest to the right individual | B and teammate D capture; B arrived first and stays. | B becomes the Flan owner (`/flan` info). |
| 23 | Old trust removed | A had trusted E before. | E has no access; groups and overrides empty. |
| 24 | Subclaims deleted | A had subclaims. | None remain; B can create new ones. |
| 25 | Metadata cleared | A had a name, home, titles and potions. | All cleared; home equals the flag position. |
| 26 | Banner changes | B saved a design with `/flag design set`. | Flag shows B's design at the same position. |
| 27 | World intact | Compare before and after. | Same boundaries, blocks and items. |
| 28 | Old owner allowance | A checks claim blocks before and after. | Used blocks drop by exactly the claim area; total unchanged. |
| 29 | Conqueror not charged | B checks blocks and claims count. | Used blocks and claim count unchanged by the conquered claim. |
| 30 | No free expansion | B resizes the conquered claim with the golden shovel. | Refused. |
| 31 | Repeated conquest | A recaptures, B recaptures, several times. | Nobody's total claim blocks change. |
| 32 | Deleting conquered land | B deletes it. | B's used and total blocks unchanged. |
| 33 | Restart persistence | Restart the server. | Owners, flags, designs, states unchanged. |
| 34 | Crash during conquest | Kill the server process (no clean stop) right after a conquest message; also test a kill between journal and Flan save (needs a debug build or timing). | Startup log shows COMMITTED or ABORTED, never a duplicate owner, never extra blocks. |
| 35 | Beds during raids | Sleep during a raid. | Spawn point set; time does not skip. Outside raids, a skip stops at raid start. |
| 36 | No client mod | Join with a vanilla-Forge client that lacks the mod. | Can join; all text, bars and banners visible. |

## Additional in-game scenarios for raids, aura, contesting and alerts (all NOT RUN)

| # | Scenario | How to test | Expected |
|---|---|---|---|
| A1 | TNT in an ordinary claim | During a raid, B places and lights TNT in A's active claim. | Blocks destroyed; flag and the block under it intact; A gets "UNDER RAID" title. |
| A2 | TNT in the admin claim | Same at spawn. | Nothing destroyed (Flan's normal protection). No alert to anyone. |
| A3 | Explosion on the border | TNT straddling A's claim and the admin claim. | Only A's blocks (and wilderness, per Flan) destroyed. |
| A4 | Looting | B opens and empties A's chest and furnace, plus a modded machine. | Allowed. |
| A5 | Doors, buttons, levers | B uses them. | Allowed. |
| A6 | Raid end | `/time set 0`, then B retries A1, A4 and A5; also light TNT just before the end. | All denied. A fuse that explodes after the end damages nothing. |
| A7 | Aura visible | Stand near A's flag during a raid. | Grey ring on the ground around the flag. |
| A8 | Aura matches radius | Walk outward watching the action bar. | "INSIDE" exactly while within the ring; capture starts only inside. |
| A9 | No aura at spawn | Look around the admin claim. | No ring. |
| A10 | Capture begins | B enters the ring. | Ring turns red; boss bar; A gets "YOUR FLAG IS BEING CAPTURED!" title. |
| A11 | Owner pauses it | A steps into the ring. | Within half a second: CONTESTED, yellow ring, frozen percentage. |
| A12 | Paused while any defender stays | A and a manager-member both inside, A leaves. | Still CONTESTED. |
| A13 | Resumes after all leave | Both leave, B stays. | Capturing resumes from the same percentage. |
| A14 | Defender at 99% | Let it reach 99%, then A enters. | Never completes while A stays. |
| A15 | Immediate raid alert | B breaks one block in A's claim during a raid. | Title, sound, chat with claim name and X/Z. |
| A16 | Capture alert priority | Right after A15, B goes to the flag. | Capture title shown despite the cooldown. |
| A17 | No spam | B breaks 50 blocks. | One title, then occasional action-bar lines only. |
| A18 | Correct claim named | A owns two claims; B attacks the second. | The alert names the second claim and its coordinates. |
| A19 | Offline summary | A offline; B raids and tries to capture; A logs in. | Summary lines listing the claim and events. |
| A20 | Admin claim silent | B attacks around spawn. | The admin (spawn owner) gets no alerts. |
| A21 | Admin's ordinary claim | The admin owns a normal flagged claim; B raids it. | The admin gets normal alerts. |
| A22 | Vanilla client | Join without the mod. | Titles, sounds, ring and boss bar all visible. |

## Amendment scenarios: 90 s capture, limit, PvP, immunity, statistics (all NOT RUN)

| # | Scenario | Expected |
|---|---|---|
| B1 | Uncontested capture | Completes after 90 s. |
| B2 | Defender enters / multiple defenders / all leave | Pauses immediately; stays paused until the last leaves; resumes. |
| B3 | Attackers leave / raid ends / next window | Gradual decay; never completes after the window; next window starts fresh. |
| B4 | Player with 2 conquered claims captures | Succeeds (3/3). |
| B5 | Player with 3 tries a 4th | "Conquest Limit Reached!" message; no progress. With an eligible teammate present, the teammate captures and receives the claim. |
| B6 | Lose a conquered claim | `/war claims` shows a freed slot. |
| B7 | Ordinary claims | `/war claims` count unaffected; conquered claims still cost no claim blocks. |
| B8 | Restart | `/war claims` unchanged. |
| B9 | Lower the limit to 1 while owning 3 | No claims removed; no further captures. |
| B10 | PvP during a raid (melee, bow, crossbow, a modded weapon) | Raider hurts defender and vice versa inside the claim. |
| B11 | PvP after the raid / at spawn | Back to Flan's normal rules / spawn protected. |
| B12 | Server `pvp=false` | Still no PvP (not overridden). |
| B13 | Conquest | `/flag info` shows 24 h immunity; ring turns blue. |
| B14 | Attacker enters an immune zone | "This territory is immune to conquest. Recapture available in: …"; no capture; no capture alert to the owner. |
| B15 | Raid an immune claim | Breaking, looting, TNT and PvP work; raid alert sent. |
| B16 | Restart / `/time set` / sleep / flag move | Immunity remaining unchanged, apart from real time passing. |
| B17 | Server offline 2 h | Immunity is 2 h shorter afterwards. |
| B18 | `recapture_immunity_hours = 0` | New conquests have no immunity. |
| B19 | `/war admin immunity set 1h` / `clear` / `inspect` as op, then as non-op | Works and is audit-logged / non-op denied. |
| B20 | Conquest statistics | Winner's conquests +1 exactly once (also after restart); loser's lost +1; one war-log entry. |
| B21 | Qualifying defense (≥10%, defender contests, attackers leave, decay to 0) | Each contesting defender +1; kingdom +1; log entry. |
| B22 | Non-qualifying: 5% attempt / no defender / raid ends with attackers present / second attempt within 30 min | No defense recorded. |
| B23 | Leaderboards (`players`, `kingdoms`, `defenses`, `territory`), `/war stats`, `/war stats <player>` | Correct values, order and pagination; "Your Rank". |
| B24 | Change a player's FTB party after a conquest | Old log entry keeps the old kingdom. |
| B25 | `/war log`, `player`, `claim`, `kingdom`, pages | Correct filtering, newest first, readable timestamps. |
| B26 | Season end, archive, start, reset confirm | Archive viewable with `/war seasons`; claims, ownership, immunity and flags unchanged. |
| B27 | Rollback a conquest | Ownership and immunity restored; log shows the conquest as reversed plus a reversal entry; statistics no longer count it. |
| B28 | Kill the server right after a conquest | After restart exactly one conquest event; `/war admin stats check` reports no duplicates. |

## Flagpole, capture time and statistics-check scenarios (all NOT RUN)

| # | Scenario | Expected |
|---|---|---|
| C1 | `/war admin capturetime 120`, then `/war admin capturetime` | "90s -> 120s"; shows 120 s; config file updated. |
| C2 | Capture another flag | Takes 120 s. |
| C3 | Restart | Still 120 s. |
| C4 | Change 90 -> 120 at 50% mid-capture | Bar stays at 50%; about 60 s more needed; no instant conquest. |
| C5 | `/war admin capturetime reset`; non-op tries it | Back to 90 s / denied. |
| C6 | Owner, defender, raider and a neutral player near a flag | All see the same ring (up to 48 blocks). |
| C7 | Walk outward | Action bar says INSIDE exactly within the ring; capture only inside. |
| C8 | Spawn admin claim | No ring. |
| C9 | `/flag place` at Y = 50 | Fence pole Y 50-54, banner hanging at the top on the side you faced; you are moved one step aside. |
| C10 | `/flag place` under a low ceiling, or facing a wall | Rejected with an explanation; no blocks changed. |
| C11 | Mine / TNT / piston / build next to the pole and banner slots | All prevented; with `/setblock` removal the pole is rebuilt within 5 s, no item drops left. |
| C12 | Capture to 25%, 49% | Owner banner steps down the pole. |
| C13 | Reach 50% | Banner at the bottom shows the attacker's design; `/flag info` still shows the old owner. |
| C14 | 70%, 100% | Attacker banner rises; conquest happens only at 100%; new owner's banner on top. |
| C15 | Defender steps in | Banner stops moving; CONTESTED. |
| C16 | Attackers leave at 70% | Banner lowers, switches back to the owner below 50%, rises to the top at 0%. |
| C17 | `/flag move` | Whole pole moves; old one gone; no drops. |
| C18 | Stand on top of the pole / 4 blocks below in a tunnel | Not counted as being in the zone. |
| C19 | Kill the server mid-animation, restart | Banner back on top with the owner's design. |
| C20 | `/war admin stats check` | Summary plus integrity; nothing changes (compare war_data.json before/after). |
| C21 | `/war admin stats check <player>` | Correct per-player values. |
| C22 | Introduce an inconsistency (e.g. delete a claim with the mod removed), check, then `stats repair confirm` | Warning shown, then fixed; audit-log line. |
| C23 | FTB party members vs a player only trusted in a claim | Only party members share a kingdom on the leaderboard. |

## Radius 3, Flan authorization and member-notice scenarios (all NOT RUN)

| # | Scenario | Expected |
|---|---|---|
| D1 | Ring and capture at 2.9 vs 3.1 blocks from the pole base | Ring radius 3; capture and contest only inside. |
| D2 | B conquers A's claim, opens the Flan menu | B can add players, edit groups/permissions and create subclaims; resizing is refused. |
| D3 | B adds A through the Flan menu | A gets "You have been added to B's claim…" plus action bar; A has exactly the granted permissions. |
| D4 | B toggles several permissions of A's group quickly | A gets one "permissions updated" message about 3 s later. |
| D5 | B removes A | A gets "removed…"; A loses access immediately. |
| D6 | B adds A while A is offline, then A logs in | Notice delivered at login; add+remove while offline produces nothing. |
| D7 | Raid hours: A (trusted) enters, opens doors, uses granted containers | No raid alert to B; A cannot use containers B did not grant. |
| D8 | Raid hours: untrusted C breaks blocks | Raid alert to B as before. |
| D9 | B removes A during a raid, A breaks a block | Raid permissions apply to A and B is alerted. |
| D10 | Trusted A stands in the ring while C captures | A contests only if A's group is a manager group (default policy); A can never capture B's claim while trusted. |
| D11 | A is removed mid-capture | A's role changes within half a second; progress is not reset. |
| D12 | B's own TNT inside B's claim during a raid | No raid alert. |
| D13 | Restart | Membership and behaviour unchanged (all read from Flan). |
| D14 | Old members of a claim that gets conquered | "Your access to … ended: conquered by …". |

## Flan permission GUI, CANSTAY, migration and version scenarios (all NOT RUN)

| # | Scenario | Expected |
|---|---|---|
| E1 | Claim-Menu → Edit Permissiongroups → add NoUserWorks to a group | One "added" notice about 3 s later, saying whether the group allows entering. |
| E2 | Remove NoUserWorks from the group | One "removed" notice. |
| E3 | Toggle CANSTAY on/off in his group | "You may now enter this claim." / "You no longer have permission to enter."; never "removed". |
| E4 | Toggle a permission twice quickly, or move him to a group with identical permissions | No notice. |
| E5 | Repeat E1 while he is offline, then he logs in | Notice delivered once at login. |
| E6 | Owner denies CANSTAY for everyone; outsider walks in outside raid hours / during raid hours | Teleported out / can enter and capture. |
| E7 | NoUserWorks (member with CANSTAY, door, container) enters during a raid and uses door/container | No raid alert; he cannot use what his group doesn't allow. |
| E8 | Public CANSTAY claim: random visitor stands in the ring during a capture | Not a defender; can capture (if not a member). |
| E9 | NoUserWorks (member) tries to capture | Cannot. After removal: can, within half a second. |
| E10 | Manager-group member stands in the ring | CONTESTED, animation freezes. |
| E11 | Conqueror opens Claim-Menu → Edit Permissiongroups, adds the former owner, edits CANSTAY | Works; former owner notified and gets exactly those permissions; resize still refused. |
| E12 | Old config with radius 2.0 and a custom capture time 120 | After update: radius 3.0 (logged), capture time stays 120. Restart again: nothing logged. |
| E13 | `/war admin version` and the startup log | Same version and build number as the jar name. |

Fill in **Actual** and **Pass/Fail** for each when you run them, and report any failure with
the server log. Note also that `/war admin status` reports whether FTB Teams was detected.
Scenarios 17, 18 and 22 depend on it.
