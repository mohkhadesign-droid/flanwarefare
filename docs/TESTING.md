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

Result of the last run: `LogicSelfTest: all 83 checks passed` (locally, and on every GitHub
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

Fill in **Actual** and **Pass/Fail** for each when you run them, and report any failure with
the server log. Note also that `/war admin status` reports whether FTB Teams was detected.
Scenarios 17, 18 and 22 depend on it.
