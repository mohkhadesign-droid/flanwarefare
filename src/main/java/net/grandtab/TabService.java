package net.grandtab;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPipeline;
import net.grandtab.config.ConfigStore;
import net.grandtab.config.TabConfig;
import net.grandtab.logic.TabLogic;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPlayerListHeaderFooterPacket;
import net.minecraft.network.play.server.SPlayerListItemPacket;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.Color;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and sends everything shown on the Tab screen. All of it uses vanilla packets, so
 * unmodified clients display it:
 * <ul>
 *   <li>header and footer: one text component each (SPlayerListHeaderFooterPacket), per player,
 *   so players who loaded the server resource pack get the logo image and the others a text
 *   title;</li>
 *   <li>player names: Forge's TabListNameFormat event sets each player's tab-list display name.
 *   This does not use scoreboard teams, so other mods changing teams cannot remove the
 *   prefixes, and the name is built from the account name, so prefixes added to the display
 *   name by other mods are not repeated;</li>
 *   <li>pings: the vanilla latency update, sent more often than vanilla's 30 s;</li>
 *   <li>optional sorting through scoreboard teams named gt0000-gt9999 (vanilla sorts the list by
 *   team name).</li>
 * </ul>
 */
public final class TabService {
    private final MinecraftServer server;
    private final ConfigStore store;
    private final Map<UUID, String> packStatus = new ConcurrentHashMap<>();
    private final Map<UUID, String> sentHeaders = new HashMap<>();
    private final Map<UUID, String> sentNames = new HashMap<>();
    private volatile boolean dirty = true;
    private long ticks;

    public TabService(MinecraftServer server, ConfigStore store) {
        this.server = server;
        this.store = store;
    }

    public TabConfig config() {
        return store.config();
    }

    public ConfigStore store() {
        return store;
    }

    public void markDirty() {
        dirty = true;
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        cleanupTeams(!config().players.sortByRank);
        dirty = true;
    }

    public void stop() {
        cleanupTeams(true);
    }

    public void tick() {
        ticks++;
        TabConfig cfg = config();
        boolean refresh = dirty || ticks % cfg.updateIntervalTicks == 0;
        boolean ping = ticks % cfg.pingUpdateIntervalTicks == 0;
        if (refresh) {
            dirty = false;
            refreshAll();
        }
        if (ping && cfg.enabled) {
            List<ServerPlayerEntity> players = server.getPlayerList().getPlayers();
            if (!players.isEmpty()) {
                server.getPlayerList().sendPacketToAllPlayers(
                        new SPlayerListItemPacket(SPlayerListItemPacket.Action.UPDATE_LATENCY, players));
                if (cfg.players.showPingMs && !refresh) {
                    refreshNames();
                }
            }
        }
    }

    private void refreshAll() {
        TabConfig cfg = config();
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            sendHeader(player);
        }
        refreshNames();
        if (cfg.enabled && cfg.players.sortByRank) {
            applySortTeams();
        }
    }

    private void refreshNames() {
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            ITextComponent name = displayName(player);
            String json = name == null ? "" : ITextComponent.Serializer.toJson(name);
            if (!json.equals(sentNames.get(player.getUniqueID()))) {
                sentNames.put(player.getUniqueID(), json);
                player.refreshTabListName(); // fires TabListNameFormat and sends the update to everyone
            }
        }
    }

    public void onLogin(ServerPlayerEntity player) {
        installPackListener(player);
        sentHeaders.remove(player.getUniqueID());
        sentNames.remove(player.getUniqueID());
        dirty = true;
    }

    public void onLogout(ServerPlayerEntity player) {
        packStatus.remove(player.getUniqueID());
        sentHeaders.remove(player.getUniqueID());
        sentNames.remove(player.getUniqueID());
        dirty = true; // the online count drops on the next tick, when the player is gone
    }

    // ------------------------------------------------------------------ resource pack status

    private void installPackListener(ServerPlayerEntity player) {
        try {
            ChannelPipeline pipeline = player.connection.netManager.channel().pipeline();
            if (pipeline.get(PackStatusListener.NAME) == null && pipeline.get("packet_handler") != null) {
                pipeline.addBefore("packet_handler", PackStatusListener.NAME, new PackStatusListener(this, player.getUniqueID()));
            }
        } catch (RuntimeException e) {
            GrandTab.LOG.warn("Grand Tab: cannot watch resource pack status of {} (text title will be used): {}",
                    player.getGameProfile().getName(), e.toString());
        }
    }

    /** Called from the network thread when a client reports its resource pack status. */
    void packStatus(UUID player, String status) {
        packStatus.put(player, status);
        dirty = true;
    }

    public String packStatusOf(UUID player) {
        return packStatus.getOrDefault(player, "NONE (no pack offered or no answer)");
    }

    private boolean showLogo(ServerPlayerEntity player) {
        String mode = config().header.logoMode.toUpperCase(java.util.Locale.ROOT);
        if (mode.equals("PACK")) {
            return true;
        }
        if (mode.equals("AUTO")) {
            return "SUCCESSFULLY_LOADED".equals(packStatus.get(player.getUniqueID()));
        }
        return false;
    }

    // ------------------------------------------------------------------ header and footer

    private static IFormattableTextComponent text(String s, String color, int fallback, boolean bold) {
        IFormattableTextComponent c = new StringTextComponent(s);
        int rgb = TabLogic.colorOr(color, fallback);
        c.modifyStyle(style -> style.setColor(Color.fromInt(rgb)));
        if (bold) {
            c.mergeStyle(TextFormatting.BOLD);
        }
        return c;
    }

    private static IFormattableTextComponent rule(TabConfig.Header h) {
        IFormattableTextComponent c = text(TabLogic.rule(h.ruleWidth), h.ruleColor, 0x555555, false);
        c.mergeStyle(TextFormatting.STRIKETHROUGH);
        return c;
    }

    private static void line(IFormattableTextComponent root, ITextComponent part) {
        if (!root.getSiblings().isEmpty()) {
            root.appendSibling(new StringTextComponent("\n"));
        }
        root.appendSibling(part);
    }

    public ITextComponent header(ServerPlayerEntity player) {
        TabConfig cfg = config();
        TabConfig.Header h = cfg.header;
        IFormattableTextComponent root = new StringTextComponent("");
        if (h.showRule) {
            line(root, rule(h));
        }
        boolean logo = !h.logoMode.equalsIgnoreCase("NONE") && showLogo(player);
        if (logo) {
            IFormattableTextComponent glyph = new StringTextComponent(h.logoGlyph);
            ResourceLocation font = ResourceLocation.tryCreate(h.logoFont);
            if (font != null) {
                glyph.modifyStyle(style -> style.setFontId(font).setColor(Color.fromInt(0xFFFFFF)));
            }
            line(root, glyph);
            for (int i = 0; i < Math.max(0, Math.min(20, h.logoSpacerLines)); i++) {
                line(root, new StringTextComponent(" "));
            }
        }
        if (!h.logoMode.equalsIgnoreCase("NONE") && (!logo || h.showTitleWithLogo) && !h.title.isEmpty()) {
            line(root, text(h.title, h.titleColor, 0x55FFFF, h.titleBold));
        }
        if (h.showSubtitle && !h.subtitle.isEmpty()) {
            line(root, text(h.subtitle, h.subtitleColor, 0xFFAA00, h.subtitleBold));
        }
        if (h.showPlayerCount) {
            line(root, new StringTextComponent(" "));
            int online = server.getPlayerList().getCurrentPlayerCount();
            int max = server.getPlayerList().getMaxPlayers();
            IFormattableTextComponent count = text(TabLogic.count(h.playerCountLabel, online, max), h.playerCountLabelColor, 0xAAAAAA, false);
            count.appendSibling(text(TabLogic.count(h.playerCountValue, online, max), h.playerCountValueColor, 0x55FF55, true));
            line(root, count);
        }
        line(root, new StringTextComponent(" "));
        return root;
    }

    public ITextComponent footer() {
        TabConfig cfg = config();
        IFormattableTextComponent root = new StringTextComponent("");
        line(root, new StringTextComponent(" "));
        for (TabConfig.FooterLine fl : cfg.footer.lines) {
            if (fl == null) {
                continue;
            }
            IFormattableTextComponent l = text(fl.label == null ? "" : fl.label, fl.labelColor, 0xAAAAAA, false);
            l.appendSibling(text(fl.value == null ? "" : fl.value, fl.valueColor, 0xFFFFFF, fl.bold));
            line(root, l);
        }
        if (cfg.footer.showRule) {
            line(root, rule(cfg.header));
        }
        return root;
    }

    private void sendHeader(ServerPlayerEntity player) {
        TabConfig cfg = config();
        ITextComponent header = cfg.enabled ? header(player) : new StringTextComponent("");
        ITextComponent footer = cfg.enabled ? footer() : new StringTextComponent("");
        String key = ITextComponent.Serializer.toJson(header) + "|" + ITextComponent.Serializer.toJson(footer);
        if (key.equals(sentHeaders.get(player.getUniqueID()))) {
            return;
        }
        SPlayerListHeaderFooterPacket packet = new SPlayerListHeaderFooterPacket();
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try {
            // Filled through the packet's own decoder, so no private fields are touched.
            buffer.writeTextComponent(header);
            buffer.writeTextComponent(footer);
            packet.readPacketData(buffer);
        } catch (IOException e) {
            GrandTab.LOG.error("Grand Tab: cannot build the tab header", e);
            return;
        } finally {
            buffer.release();
        }
        player.connection.sendPacket(packet);
        sentHeaders.put(player.getUniqueID(), key);
    }

    // ------------------------------------------------------------------ player names

    /** The rank of a player, resolved against the current config; null if unranked or unknown. */
    public static final class Rank {
        public final TabConfig.Kingdom kingdom;
        public final TabConfig.Role role;

        Rank(TabConfig.Kingdom kingdom, TabConfig.Role role) {
            this.kingdom = kingdom;
            this.role = role;
        }
    }

    public Rank rankOf(UUID player) {
        ConfigStore.Assignment a = store.assignments().get(player);
        if (a == null) {
            return null;
        }
        TabConfig.Kingdom k = config().kingdom(a.kingdom);
        TabConfig.Role r = config().role(a.role);
        return k == null || r == null ? null : new Rank(k, r);
    }

    /** Tab-list name, or null to leave the vanilla name (display disabled). */
    public ITextComponent displayName(ServerPlayerEntity player) {
        TabConfig cfg = config();
        if (!cfg.enabled) {
            return null;
        }
        TabConfig.Players p = cfg.players;
        String name = player.getGameProfile().getName();
        Rank rank = rankOf(player.getUniqueID());
        IFormattableTextComponent root = new StringTextComponent("");
        if (rank == null) {
            root.appendSibling(text(name, p.unrankedNameColor, 0xFFFFFF, p.nameBold));
        } else {
            int kingdomColor = TabLogic.colorOr(rank.kingdom.color, 0xFFFFFF);
            int prefixColor = TabLogic.colorOr(rank.role.color, kingdomColor);
            String prefix = TabLogic.prefix(p.prefixFormat, rank.kingdom.tag, rank.kingdom.name, rank.role.name);
            if (!prefix.isEmpty()) {
                root.appendSibling(text(prefix, TabLogic.formatColor(prefixColor), prefixColor, rank.role.bold));
                root.appendSibling(new StringTextComponent(" "));
            }
            int nameColor;
            if ("KINGDOM".equalsIgnoreCase(p.nameColor)) {
                nameColor = kingdomColor;
            } else if ("ROLE".equalsIgnoreCase(p.nameColor)) {
                nameColor = prefixColor;
            } else {
                nameColor = TabLogic.colorOr(p.nameColor, 0xFFFFFF);
            }
            root.appendSibling(text(name, TabLogic.formatColor(nameColor), nameColor, p.nameBold));
        }
        if (p.showPingMs) {
            int ms = player.ping;
            root.appendSibling(text(" " + ms + "ms", TabLogic.formatColor(TabLogic.pingColor(ms)), 0xAAAAAA, false));
        }
        return root;
    }

    // ------------------------------------------------------------------ sorting teams

    private void applySortTeams() {
        ServerScoreboard board = server.getScoreboard();
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            Rank rank = rankOf(player.getUniqueID());
            String wanted = rank == null ? TabLogic.UNRANKED_TEAM
                    : TabLogic.sortTeam(config().kingdomIndex(rank.kingdom), rank.role.order);
            String entry = player.getScoreboardName();
            ScorePlayerTeam current = board.getPlayersTeam(entry);
            if (current != null && !TabLogic.isSortTeam(current.getName())) {
                continue; // another mod's or datapack's team: leave it alone (vanilla order for this player)
            }
            if (current != null && current.getName().equals(wanted)) {
                continue;
            }
            ScorePlayerTeam team = board.getTeam(wanted);
            if (team == null) {
                team = board.createTeam(wanted);
                team.setSeeFriendlyInvisiblesEnabled(false);
            }
            board.addPlayerToTeam(entry, team);
        }
        // Drop empty sorting teams.
        for (ScorePlayerTeam team : new ArrayList<>(board.getTeams())) {
            if (TabLogic.isSortTeam(team.getName()) && team.getMembershipCollection().isEmpty()) {
                board.removeTeam(team);
            }
        }
    }

    /** Removes sorting teams (all of them, or none if sorting stays on). */
    public void cleanupTeams(boolean all) {
        if (!all) {
            return;
        }
        ServerScoreboard board = server.getScoreboard();
        for (ScorePlayerTeam team : new ArrayList<>(board.getTeams())) {
            if (TabLogic.isSortTeam(team.getName())) {
                board.removeTeam(team);
            }
        }
    }

    /** Number of online players who are in another mod's team (so not sorted by rank). */
    public List<String> playersInForeignTeams() {
        List<String> names = new ArrayList<>();
        ServerScoreboard board = server.getScoreboard();
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            ScorePlayerTeam team = board.getPlayersTeam(player.getScoreboardName());
            if (team != null && !TabLogic.isSortTeam(team.getName())) {
                names.add(player.getGameProfile().getName() + " (team " + team.getName() + ")");
            }
        }
        return names;
    }
}
