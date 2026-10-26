package net.grandtab.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Contents of config/grandtab/config.json. Every field has a default, so a missing or partial
 * file still works; the README documents each key.
 */
public final class TabConfig {
    public boolean enabled = true;
    /** How often headers, names and teams are refreshed (they also refresh on join/leave/change). */
    public int updateIntervalTicks = 40;
    /** How often ping bars are resent (vanilla only does it every 600 ticks = 30 s). */
    public int pingUpdateIntervalTicks = 40;
    /** Permission level for /tabrank (2 = operators). */
    public int commandPermissionLevel = 2;

    public Header header = new Header();
    public Footer footer = new Footer();
    public Players players = new Players();
    public List<Kingdom> kingdoms = new ArrayList<>(Arrays.asList(
            new Kingdom("K1", "Kingdom I", "K1", "#55FF55"),
            new Kingdom("K2", "Kingdom II", "K2", "#5599FF"),
            new Kingdom("K3", "Kingdom III", "K3", "#BB66FF")));
    public List<Role> roles = new ArrayList<>(Arrays.asList(
            new Role("monarch", "MONARCH", 1, true, "#FFD700"),
            new Role("champion", "CHAMPION", 2, true, ""),
            new Role("warden", "WARDEN", 3, true, ""),
            new Role("sage", "SAGE", 4, false, ""),
            new Role("knight", "KNIGHT", 5, false, "")));

    public static final class Header {
        /** AUTO: logo image for players who loaded the server resource pack, text title for others. PACK, TEXT or NONE. */
        public String logoMode = "AUTO";
        public String logoFont = "grandtab:logo";
        public String logoGlyph = "";
        /** Empty lines reserved under the logo glyph (the glyph is taller than one text line). */
        public int logoSpacerLines = 6;
        public boolean showTitleWithLogo = false;
        public String title = "THE GRAND EXPEDITION";
        public String titleColor = "#55FFFF";
        public boolean titleBold = true;
        public boolean showSubtitle = true;
        public String subtitle = "CRAZY CRAFT UPDATED";
        public String subtitleColor = "#FFAA00";
        public boolean subtitleBold = true;
        public boolean showRule = true;
        public String ruleColor = "#555555";
        public int ruleWidth = 48;
        public boolean showPlayerCount = true;
        public String playerCountLabel = "ONLINE PLAYERS: ";
        public String playerCountLabelColor = "#AAAAAA";
        public String playerCountValue = "{online}";
        public String playerCountValueColor = "#55FF55";
    }

    public static final class Footer {
        public List<FooterLine> lines = new ArrayList<>(Arrays.asList(
                new FooterLine("DISCORD: ", "#AAAAAA", "discord.gg/yourserver", "#5599FF", true),
                new FooterLine("WEBSITE: ", "#AAAAAA", "yourserver.com", "#55FFFF", true)));
        public boolean showRule = true;
    }

    public static final class FooterLine {
        public String label = "";
        public String labelColor = "#AAAAAA";
        public String value = "";
        public String valueColor = "#FFFFFF";
        public boolean bold = true;

        public FooterLine() {
        }

        public FooterLine(String label, String labelColor, String value, String valueColor, boolean bold) {
            this.label = label;
            this.labelColor = labelColor;
            this.value = value;
            this.valueColor = valueColor;
            this.bold = bold;
        }
    }

    public static final class Players {
        /** Placeholders: {kingdom} (tag), {kingdom_name}, {role}. */
        public String prefixFormat = "[{kingdom} {role}]";
        /** KINGDOM (name in the kingdom colour), ROLE (in the prefix colour), or a colour such as white / #FFFFFF. */
        public String nameColor = "KINGDOM";
        public boolean nameBold = false;
        public String unrankedNameColor = "#FFFFFF";
        /** Adds " 45ms" after each name (the ping bars themselves are always drawn by the client). */
        public boolean showPingMs = false;
        /** Sort by kingdom and role using scoreboard teams (players in another mod's team keep it). */
        public boolean sortByRank = false;
    }

    public static final class Kingdom {
        public String id;
        public String name;
        public String tag;
        public String color;

        public Kingdom() {
        }

        public Kingdom(String id, String name, String tag, String color) {
            this.id = id;
            this.name = name;
            this.tag = tag;
            this.color = color;
        }
    }

    public static final class Role {
        public String id;
        public String name;
        public int order;
        public boolean bold;
        /** Prefix colour for this role; empty = the kingdom colour. */
        public String color = "";

        public Role() {
        }

        public Role(String id, String name, int order, boolean bold, String color) {
            this.id = id;
            this.name = name;
            this.order = order;
            this.bold = bold;
            this.color = color;
        }
    }

    /** Fills anything a hand-edited file left out. */
    public void repair() {
        if (header == null) {
            header = new Header();
        }
        if (footer == null) {
            footer = new Footer();
        }
        if (footer.lines == null) {
            footer.lines = new ArrayList<>();
        }
        if (players == null) {
            players = new Players();
        }
        if (kingdoms == null) {
            kingdoms = new ArrayList<>();
        }
        if (roles == null) {
            roles = new ArrayList<>();
        }
        kingdoms.removeIf(k -> k == null || k.id == null);
        roles.removeIf(r -> r == null || r.id == null);
        for (Kingdom k : kingdoms) {
            if (k.name == null) {
                k.name = k.id;
            }
            if (k.tag == null) {
                k.tag = k.id;
            }
            if (k.color == null) {
                k.color = "#FFFFFF";
            }
        }
        for (Role r : roles) {
            if (r.name == null) {
                r.name = r.id.toUpperCase(java.util.Locale.ROOT);
            }
            if (r.color == null) {
                r.color = "";
            }
        }
        updateIntervalTicks = Math.max(10, updateIntervalTicks);
        pingUpdateIntervalTicks = Math.max(10, pingUpdateIntervalTicks);
        if (header.logoGlyph == null || header.logoGlyph.isEmpty()) {
            header.logoGlyph = "";
        }
    }

    public Kingdom kingdom(String id) {
        for (Kingdom k : kingdoms) {
            if (k.id.equalsIgnoreCase(id)) {
                return k;
            }
        }
        return null;
    }

    public int kingdomIndex(Kingdom kingdom) {
        return kingdoms.indexOf(kingdom);
    }

    public Role role(String id) {
        for (Role r : roles) {
            if (r.id.equalsIgnoreCase(id)) {
                return r;
            }
        }
        return null;
    }
}
