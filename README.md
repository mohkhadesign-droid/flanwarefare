# Grand Tab List

A server-side Forge **1.16.5** mod that turns the Tab player list into a styled server screen:

- a logo, title and subtitle at the top;
- a live **ONLINE PLAYERS** count;
- kingdom rank prefixes such as **[K1 MONARCH] Ali**;
- live ping;
- Discord and website lines at the bottom.

Made for The Grand Expedition (Crazy Craft Updated). It's separate from Flan Warfare & Conquest.

> **Status:** compiles on GitHub Actions against Forge 1.16.5-36.2.39, and its 21 automated
> logic checks pass. It has **not** been tested on a running server yet; see
> [docs/TESTING.md](docs/TESTING.md).

---

## 1. What is possible without a client mod (feasibility)

The reference screenshot was checked against what a 1.16.5 **server** controls on an unmodified
client.

| Part of the screenshot | Server-only (Option A)? | How |
|---|---|---|
| Logo image at the top | **Yes, with the server resource pack** | The logo is a font character (bitmap glyph `U+E000` in font `grandtab:logo`). 1.16 text supports custom fonts, so the header can display it. |
| Title, subtitle, colours, bold | **Yes** | The header is a text component with full RGB colours. Each line is centred by the client. |
| ONLINE PLAYERS: X | **Yes** | Rebuilt on join, leave and every 2 s. |
| Player heads | **Yes (vanilla)** | Drawn by the client when the server runs `online-mode=true`. |
| Rank prefix + coloured name | **Yes** | Forge's `TabListNameFormat` event sets each player's tab name. **No scoreboard teams are involved**, so mods that remove players from teams can't remove the prefixes. |
| Ping bars | **Yes (vanilla)** | Resent every 2 s instead of every 30 s. Optional "45ms" text after names. |
| Discord / website footer | **Yes** | Footer text component. |
| Horizontal lines | **Yes** | Struck-through spaces. |
| Sorting by rank | **Partly** | Vanilla sorts the list by scoreboard team name, then player name. Rank sorting therefore needs teams (optional, see §5). |
| Custom background panel, its size, transparency or texture | **No** | The client draws the Tab background itself, and the server can't change it. Doing so needs Option B (a client mod). |
| Hiding ping bars | **No** | The client always draws them. |
| Logo for players who decline the resource pack | Text fallback | 1.16.5 can't force a pack. The mod sees each player's pack answer and shows the logo to those who loaded it. Everyone else gets the title as text, so nobody sees broken boxes. |

**Recommendation: Option A, this mod.** It reproduces the screenshot's content and style, and
players install nothing. Only the exact background panel would need Option B. Option B means
every player installs a client mod, and the background is the only visible difference.

**Do clients need to install anything?** No. The mod tells Forge it is server-only, registers no
blocks, items or network channels, and only uses vanilla packets. The resource pack is offered by
the server automatically when players join; it's optional.

---

## 2. Installation on Pterodactyl

1. **Get the jar.** In GitHub, open **Actions**, click the newest green **Build** run, and
   download **grandtab-jar**. It contains `grandtab-1.0.0-bN.jar` (use the one *without*
   `-sources`) and `GrandTab-ResourcePack.zip`.
2. **Stop the server** in the Pterodactyl panel.
3. **File Manager → `mods/`**: upload `grandtab-1.0.0-bN.jar`. Remove any older `grandtab-*.jar`.
4. **Start the server.** The mod creates `config/grandtab/config.json` and
   `config/grandtab/assignments.json`, and the log shows `Grand Tab List ... ready`.
5. **Set the texts.** Edit `config/grandtab/config.json` in the File Manager (Discord, website,
   title …) and run `/tabrank reload`. Or use the commands in §4 without touching the file.
6. **Add the logo (optional).** See §3.

To update: stop the server, replace the jar, start again. Your config and assignments are kept.

### Building the jar yourself

GitHub builds it on every push (`.github/workflows/build.yml`). Locally, with JDK 8:

```
./gradlew build
```

The jar appears in `build/libs/`. The first build downloads Minecraft and Forge, which takes a
few minutes.

---

## 3. The logo (server resource pack)

`GrandTab-ResourcePack.zip` contains the "THE GRAND EXPEDITION" pixel logo (132×56) as the
glyph `U+E000` of the font `grandtab:logo`.

1. **Host the zip at a direct-download URL.** The easiest way is this repository, if it's
   public:
   `https://raw.githubusercontent.com/<you>/<repo>/main/resourcepack/GrandTab-ResourcePack.zip`.
   A Dropbox link ending in `?dl=1` also works. Discord attachment links expire, so don't use
   them.
2. **In Pterodactyl → File Manager → `server.properties`**, set:
   ```
   resource-pack=https://raw.githubusercontent.com/<you>/<repo>/main/resourcepack/GrandTab-ResourcePack.zip
   resource-pack-sha1=3ea9306fed9910323c17c8515748c5976b142b1c
   ```
   The SHA-1 above matches the zip in this repository. If you change the zip, run
   `python3 tools/build_pack.py`; it prints the new value.
3. **Restart.** Players are asked to accept the pack when they join. With `logoMode: "AUTO"`
   (the default), players who loaded it see the logo; the others see the title as text.

**Your own logo:**

1. Replace `resourcepack/source/assets/grandtab/textures/font/logo.png`.
2. Set `"height"` in `resourcepack/source/assets/grandtab/font/logo.json` to the image height.
3. Set `logoSpacerLines` to about `height / 9`.
4. Rebuild the zip and update the SHA-1.

`tools/make_logo.py` is the script that drew the current logo.

> If Crazy Craft Updated already offers its own server resource pack, merge the `assets/grandtab`
> folder into that pack. A server can offer only one pack.

---

## 4. Commands

All commands need permission level 2 (operators) by default; this is `commandPermissionLevel`
in the config. Changes are saved immediately and appear in Tab within a second. `<player>`
works for offline players the server has seen before.

| Command | What it does |
|---|---|
| `/tabrank set <player> <kingdom> <role>` | Give a rank, e.g. `/tabrank set Ali K1 monarch` → **[K1 MONARCH] Ali** |
| `/tabrank clear <player>` | Remove the rank |
| `/tabrank info <player>` | Their rank and resource-pack status |
| `/tabrank list` | All ranked players by kingdom and role |
| `/tabrank kingdoms` | Kingdom and role ids, names and colours |
| `/tabrank kingdom <K1> color <green\|#55FF55>` | Kingdom colour |
| `/tabrank kingdom <K1> name <Kingdom I>` / `tag <K1>` | Kingdom name / the short tag used in prefixes |
| `/tabrank role <monarch> name <MONARCH>` | Role display name |
| `/tabrank role <monarch> color <gold\|#FFD700\|kingdom>` | Role colour (`kingdom` = use the kingdom colour) |
| `/tabrank role <monarch> bold true\|false` | Bold prefix for that role |
| `/tabrank title <text>` / `subtitle <text>` | Header texts |
| `/tabrank discord <text>` / `website <text>` | Footer texts |
| `/tabrank enable` / `disable` | Switch the custom tab list on or off (off = vanilla) |
| `/tabrank reload` | Re-read both files after editing them by hand |
| `/tabrank version` | Installed version and build |

---

## 5. Configuration (`config/grandtab/config.json`)

Plain JSON. Run `/tabrank reload` after editing. If the file has a syntax error, the error is
logged and reported by `/tabrank reload`, the previous settings stay active, and the file is
**not** overwritten. Colours accept the 16 Minecraft names (`gold`, `dark_purple` …) or
`#RRGGBB`.

```json
{
  "enabled": true,
  "updateIntervalTicks": 40,
  "pingUpdateIntervalTicks": 40,
  "commandPermissionLevel": 2,
  "header": {
    "logoMode": "AUTO",
    "logoFont": "grandtab:logo",
    "logoGlyph": "",
    "logoSpacerLines": 6,
    "showTitleWithLogo": false,
    "title": "THE GRAND EXPEDITION",
    "titleColor": "#55FFFF",
    "titleBold": true,
    "showSubtitle": true,
    "subtitle": "CRAZY CRAFT UPDATED",
    "subtitleColor": "#FFAA00",
    "subtitleBold": true,
    "showRule": true,
    "ruleColor": "#555555",
    "ruleWidth": 48,
    "showPlayerCount": true,
    "playerCountLabel": "ONLINE PLAYERS: ",
    "playerCountLabelColor": "#AAAAAA",
    "playerCountValue": "{online}",
    "playerCountValueColor": "#55FF55"
  },
  "footer": {
    "lines": [
      { "label": "DISCORD: ", "labelColor": "#AAAAAA", "value": "discord.gg/yourserver", "valueColor": "#5599FF", "bold": true },
      { "label": "WEBSITE: ", "labelColor": "#AAAAAA", "value": "yourserver.com", "valueColor": "#55FFFF", "bold": true }
    ],
    "showRule": true
  },
  "players": {
    "prefixFormat": "[{kingdom} {role}]",
    "nameColor": "KINGDOM",
    "nameBold": false,
    "unrankedNameColor": "#FFFFFF",
    "showPingMs": false,
    "sortByRank": false
  },
  "kingdoms": [
    { "id": "K1", "name": "Kingdom I",   "tag": "K1", "color": "#55FF55" },
    { "id": "K2", "name": "Kingdom II",  "tag": "K2", "color": "#5599FF" },
    { "id": "K3", "name": "Kingdom III", "tag": "K3", "color": "#BB66FF" }
  ],
  "roles": [
    { "id": "monarch",  "name": "MONARCH",  "order": 1, "bold": true,  "color": "#FFD700" },
    { "id": "champion", "name": "CHAMPION", "order": 2, "bold": true,  "color": "" },
    { "id": "warden",   "name": "WARDEN",   "order": 3, "bold": true,  "color": "" },
    { "id": "sage",     "name": "SAGE",     "order": 4, "bold": false, "color": "" },
    { "id": "knight",   "name": "KNIGHT",   "order": 5, "bold": false, "color": "" }
  ]
}
```

**Top level**

| Key | Meaning |
|---|---|
| `enabled` | `false` = vanilla tab list (names, header and footer cleared) |
| `updateIntervalTicks` | How often everything is checked for changes (20 ticks = 1 s). Join, leave and commands refresh right away. |
| `pingUpdateIntervalTicks` | How often ping bars are resent |

**`header`**

| Key | Meaning |
|---|---|
| `logoMode` | `AUTO` (logo if the player loaded the pack, otherwise the title as text), `PACK` (always the logo), `TEXT` (always text), `NONE` (no logo or title) |
| `logoFont` / `logoGlyph` | Font and character of the logo in the resource pack |
| `logoSpacerLines` | Empty lines reserved under the logo |
| `showTitleWithLogo` | Also show the title text under the logo |
| `title*` / `subtitle*` | Header texts, colours and bold |
| `showRule`, `ruleColor`, `ruleWidth` | The horizontal lines at the top and bottom |
| `playerCount*` | Label and number; `{online}` and `{max}` are filled in |

**`footer`**

| Key | Meaning |
|---|---|
| `lines` | Any number of label + value lines. `/tabrank discord` and `/tabrank website` edit the first two. |

**`players`**

| Key | Meaning |
|---|---|
| `prefixFormat` | `{kingdom}` (tag, K1), `{kingdom_name}` (Kingdom I), `{role}` (MONARCH) |
| `nameColor` | `KINGDOM`, `ROLE` (prefix colour), or a fixed colour |
| `showPingMs` | Adds " 45ms" after names, coloured by quality |
| `sortByRank` | Order the list by kingdom, then role, using scoreboard teams `gt0001`…`gt9999`. Players another mod puts into its own team keep that team and appear in vanilla order; `/tabrank list` names them. Off by default, because you reported that something on your server removes team members. |

**`kingdoms`** and **`roles`**

- Add, remove or rename freely.
- `id` is what commands use. `tag` and `name` are what players see.
- A role's `color` empty means the kingdom colour.
- The prefix uses the role colour; names use `nameColor`.
- Kingdoms are listed in file order and roles by `order` (for `/tabrank list` and sorting).

**`assignments.json`** is written by `/tabrank set` and `clear`. It is keyed by player UUID, so
name changes don't lose ranks:

```json
{
  "0f3e…-uuid": { "name": "Ali", "kingdom": "K1", "role": "monarch" }
}
```

---

## 6. Compatibility notes

- **Server Utilities 2.8.0**
  - **Its ranks are not read.** I have not inspected its jar yet; send it and I can add an
    integration.
  - **Assign kingdom ranks with `/tabrank` instead.** Server Utilities keeps managing chat and
    permissions; this mod never touches permissions.
  - **No duplicate prefixes in Tab.** The tab name is built from the account name, not from the
    display name other mods decorate, and this mod sets it last (lowest priority).
  - **Chat is unchanged.** Server Utilities' chat prefix stays as it is.
- **Flan, FTB Teams, FTB Chunks:** untouched. The mod reads nothing from them and changes no
  claims, teams or chunks.
- **Flan Warfare & Conquest**
  - **Tab shows this mod's prefix only.** If its kingdom-rank nametags use `LEFT` (a scoreboard
    team prefix), the Tab list still shows only this mod's name, because a tab display name
    replaces the team prefix there.
  - **No rank sorting for those players.** Players in Flan Warfare's teams aren't sorted by
    rank. Set Flan Warfare's `rank_nametags.default_position` to `BELOW` if you use
    `sortByRank`.
- **Other mods that also set Tab names:** this mod's name wins. Set `enabled` to `false` to give
  control back.

---

## 7. Known limitations

- **Not tested in game yet.** Follow `docs/TESTING.md` on a copy of the server first.
- **Background panel:** the Tab background can't be changed without a client mod (Option B).
- **Resource pack is optional for players:** 1.16.5 cannot force it, so decliners see the text
  title.
- **Logo size:** large logos make the header tall. Vanilla also limits how wide the list can be
  on small screens.
- **Sorting** depends on scoreboard teams (vanilla rule). Without `sortByRank`, players are
  listed alphabetically.
- **Player heads** need `online-mode=true`, which is the normal setting.
