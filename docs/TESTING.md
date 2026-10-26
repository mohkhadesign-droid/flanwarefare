# Test plan and results

**Honest status:**

- **Executed:**
  - The project compiles and reobfuscates on GitHub Actions against Forge 1.16.5-36.2.39.
  - `TabLogicSelfTest` passes: all 21 checks (colours, prefix format, sorting-team order and
    names, ping colours, player count, ids).
  - Every Minecraft/Forge member the mod uses was checked against the mapped Forge jar (javap).
- **Not executed:** every in-game scenario below. They need a running server with clients.

## In-game scenarios (all NOT RUN)

| # | Scenario | Expected |
|---|---|---|
| T1 | Join without the resource pack configured | Header: rule, title (cyan, bold), subtitle (gold), ONLINE PLAYERS: 1; footer: Discord, website, rule. |
| T2 | `/tabrank set <you> K1 monarch` | Within 1 s your Tab name is **[K1 MONARCH]** (gold, bold) followed by your name in green. Ping bars and head unchanged. |
| T3 | Second account joins / leaves | Count goes to 2 / back to 1 within a second; the new player has a head and ping. |
| T4 | `/team add test` then `/team join test <you>`; then `/team leave <you>` | Prefix stays in Tab both times. |
| T5 | Restart the server | Assignments and edited texts still there. |
| T6 | Configure the resource pack (README §3), accept it on join | Logo image at the top instead of the title text; no overlap with the subtitle. |
| T7 | Decline the pack on another account | That player sees the text title; the first still sees the logo. `/tabrank info` shows DECLINED / SUCCESSFULLY_LOADED. |
| T8 | `/tabrank kingdom K3 color #AA00FF`, `/tabrank role warden name GUARDIAN` | K3 names update; prefixes read [K3 GUARDIAN]. |
| T9 | `/tabrank discord discord.gg/test`, `/tabrank title MY SERVER` | Footer and header update without a restart. |
| T10 | Break `config.json` (remove a brace), `/tabrank reload` | Error message; tab list keeps working; file untouched. Fix it, reload: OK. |
| T11 | `showPingMs: true`, reload | " 45ms" after names, colour by quality, updating every 2 s. |
| T12 | `sortByRank: true`, reload, with players from several kingdoms | Order K1 monarch … K3 knight, unranked last. A player in a foreign team keeps it and is listed in `/tabrank list`. |
| T13 | `/tabrank disable` | Vanilla tab list (no header/footer, plain names). `enable` restores it. |
| T14 | With Server Utilities ranks active | Server Utilities chat unchanged; Tab shows only this mod's prefix (no duplicates). |
| T15 | 15+ players (or as many accounts as available) | All listed; header and footer still centred. |
| T16 | Player dies and respawns | Prefix still shown. |
| T17 | A client with the modpack but without this mod joins | Can join; the mod is not required on the client. |
