package net.flanwarfare.capture;

import io.netty.buffer.Unpooled;
import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.flag.BannerDesigns;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.CaptureModel;
import net.flanwarfare.logic.HudView;
import net.flanwarfare.team.SideResolver;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SCustomPayloadPlayPacket;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.BossInfo;
import net.minecraft.world.server.ServerBossInfo;
import net.minecraftforge.fml.ModList;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The capture HUD of one session, from each viewer's own perspective.
 *
 * <p><b>Who sees what</b> (resolved fresh at every update, so stale colours never stay):
 * <ul>
 *   <li>DEFENDER: the claim's current owner, the owner's FTB party and every player invited
 *   to the claim or one of its subclaims through Flan (any permission group);</li>
 *   <li>ATTACKER: players of a side that has taken part in this capture (stood in the ring
 *   as a capturer during this session, resolved through the capture system's own sides);</li>
 *   <li>NEUTRAL: everyone else in range, with no side colours.</li>
 * </ul>
 * Everyone gets the same authoritative fraction; status, colour and subtitle follow
 * {@link HudView}.
 *
 * <p><b>How it is shown:</b> with the Flan Warfare Flags mod installed (then every client has it,
 * Forge enforces that), each viewer receives a small data packet on channel
 * {@code flanwarfareflags:hud} and the client draws the panel with both kingdoms' names and
 * banners. Without it, the HUD is a vanilla boss bar per perspective (green, red, yellow or
 * white) whose title carries the kingdom names and the viewer's status.
 */
final class CaptureHud {
    static final ResourceLocation CHANNEL = new ResourceLocation("flanwarfareflags", "hud");
    private static final int KEEPALIVE_TICKS = 40;

    private final WarCore core;
    private final CaptureSession session;
    private final Map<HudView.Perspective, ServerBossInfo> bars = new EnumMap<>(HudView.Perspective.class);
    private final Map<HudView.Perspective, String> barKeys = new EnumMap<>(HudView.Perspective.class);
    /** Viewer -> last payload key sent (client HUD mode). */
    private final Map<UUID, String> sent = new HashMap<>();
    private final Map<UUID, Long> sentAt = new HashMap<>();
    private final Set<String> attackerSides = new HashSet<>();
    private UUID lastAttacker;

    CaptureHud(WarCore core, CaptureSession session) {
        this.core = core;
        this.session = session;
    }

    static boolean clientHud() {
        return ModList.get().isLoaded("flanwarfareflags");
    }

    /** The capturing sides seen in this session (to recognise attackers who step out). */
    void noteAttackers(Set<String> sides) {
        attackerSides.addAll(sides);
        UUID designated = session.selector.designated();
        if (designated != null) {
            lastAttacker = designated;
        }
    }

    HudView.Perspective perspectiveOf(ServerPlayerEntity player, Claim claim) {
        UUID id = player.getUniqueID();
        UUID owner = claim.getOwner();
        if (id.equals(owner) || core.sides().sameTeam(id, owner) || FlanBridge.isMemberAnywhere(claim, id)) {
            return HudView.Perspective.DEFENDER;
        }
        if (attackerSides.contains(core.sides().sideOf(id))) {
            return HudView.Perspective.ATTACKER;
        }
        return HudView.Perspective.NEUTRAL;
    }

    private String kingdomName(UUID player) {
        if (player == null) {
            return "ATTACKERS";
        }
        SideResolver.Kingdom kingdom = core.sides().kingdomOf(player);
        String name = kingdom != null ? kingdom.name : FlanBridge.playerName(core.server(), player);
        return name.toUpperCase(java.util.Locale.ROOT);
    }

    private static CompoundNBT banner(String snbt) {
        ItemStack stack = BannerDesigns.fromSnbt(snbt == null ? BannerDesigns.defaultSnbt() : snbt);
        CompoundNBT tag = new CompoundNBT();
        tag.putInt("Base", BannerDesigns.color(stack).getId());
        CompoundNBT source = stack.getChildTag("BlockEntityTag");
        tag.put("Patterns", source != null && source.contains("Patterns", 9) ? source.getList("Patterns", 10).copy() : new ListNBT());
        return tag;
    }

    void update(Claim claim, ClaimRecord record, CaptureModel.Status status, Set<ServerPlayerEntity> viewers, long ticks) {
        HudView.Trend trend = HudView.trendOf(status);
        double fraction = session.model.fraction();
        String attackerName = kingdomName(lastAttacker);
        String defenderName = kingdomName(claim.getOwner());
        if (clientHud()) {
            for (ServerBossInfo bar : bars.values()) {
                bar.removeAllPlayers();
            }
            String attackerDesign = session.attackerDesign != null ? session.attackerDesign
                    : lastAttacker != null ? core.flags().designFor(lastAttacker) : BannerDesigns.defaultSnbt();
            CompoundNBT attackerBanner = banner(attackerDesign);
            CompoundNBT defenderBanner = banner(record.designSnbt);
            Set<UUID> present = new HashSet<>();
            for (ServerPlayerEntity viewer : viewers) {
                UUID id = viewer.getUniqueID();
                present.add(id);
                HudView.Perspective p = perspectiveOf(viewer, claim);
                String key = p + "|" + trend + "|" + Math.round(fraction * 1000) + "|" + attackerName + "|" + defenderName
                        + "|" + attackerBanner + "|" + defenderBanner;
                Long at = sentAt.get(id);
                if (!key.equals(sent.get(id)) || at == null || ticks - at >= KEEPALIVE_TICKS) {
                    sendUpdate(viewer, p, trend, fraction, attackerName, defenderName, attackerBanner, defenderBanner);
                    sent.put(id, key);
                    sentAt.put(id, ticks);
                }
            }
            for (UUID gone : new HashSet<>(sent.keySet())) {
                if (!present.contains(gone)) {
                    sendRemove(core.server().getPlayerList().getPlayerByUUID(gone));
                    sent.remove(gone);
                    sentAt.remove(gone);
                }
            }
            return;
        }
        // Vanilla boss bars, one per perspective.
        Map<HudView.Perspective, Set<ServerPlayerEntity>> groups = new EnumMap<>(HudView.Perspective.class);
        for (HudView.Perspective p : HudView.Perspective.values()) {
            groups.put(p, new HashSet<>());
        }
        for (ServerPlayerEntity viewer : viewers) {
            groups.get(perspectiveOf(viewer, claim)).add(viewer);
        }
        int percent = (int) Math.floor(fraction * 100.0);
        for (HudView.Perspective p : HudView.Perspective.values()) {
            ServerBossInfo bar = bars.computeIfAbsent(p, k -> new ServerBossInfo(new StringTextComponent(""),
                    BossInfo.Color.WHITE, BossInfo.Overlay.NOTCHED_10));
            HudView.Tone tone = HudView.tone(p, trend);
            String key = trend + "|" + percent + "|" + attackerName + "|" + defenderName;
            if (!key.equals(barKeys.get(p))) {
                bar.setName(title(p, trend, percent, attackerName, defenderName));
                bar.setColor(color(tone));
                barKeys.put(p, key);
            }
            bar.setPercent((float) fraction);
            Set<ServerPlayerEntity> members = groups.get(p);
            for (ServerPlayerEntity player : new HashSet<>(bar.getPlayers())) {
                if (!members.contains(player)) {
                    bar.removePlayer(player);
                }
            }
            for (ServerPlayerEntity player : members) {
                if (!bar.getPlayers().contains(player)) {
                    bar.addPlayer(player);
                }
            }
        }
    }

    private static BossInfo.Color color(HudView.Tone tone) {
        switch (tone) {
            case GOOD:
                return BossInfo.Color.GREEN;
            case BAD:
                return BossInfo.Color.RED;
            case PAUSED:
                return BossInfo.Color.YELLOW;
            default:
                return BossInfo.Color.WHITE;
        }
    }

    private static TextFormatting format(HudView.Tone tone) {
        switch (tone) {
            case GOOD:
                return TextFormatting.GREEN;
            case BAD:
                return TextFormatting.RED;
            case PAUSED:
                return TextFormatting.GOLD;
            default:
                return TextFormatting.WHITE;
        }
    }

    private static IFormattableTextComponent title(HudView.Perspective p, HudView.Trend t, int percent,
                                                   String attacker, String defender) {
        HudView.Arrow arrow = HudView.arrow(t);
        String symbol = arrow == HudView.Arrow.RIGHT ? " »" : arrow == HudView.Arrow.LEFT ? " «" : " ||";
        IFormattableTextComponent title = new StringTextComponent(attacker + "   ").mergeStyle(TextFormatting.GRAY);
        title.appendSibling(new StringTextComponent(HudView.status(p, t) + symbol + " " + percent + "%")
                .mergeStyle(format(HudView.tone(p, t)), TextFormatting.BOLD));
        title.appendSibling(new StringTextComponent("   " + defender).mergeStyle(TextFormatting.GRAY));
        return title;
    }

    // ------------------------------------------------------------------ client HUD packets

    private void sendUpdate(ServerPlayerEntity viewer, HudView.Perspective p, HudView.Trend t, double fraction,
                            String attacker, String defender, CompoundNBT attackerBanner, CompoundNBT defenderBanner) {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeByte(0);
        buf.writeUniqueId(session.claimId);
        buf.writeByte(p.ordinal());
        buf.writeByte(t.ordinal());
        buf.writeFloat((float) fraction);
        buf.writeString(attacker, 64);
        buf.writeString(defender, 64);
        buf.writeCompoundTag(attackerBanner);
        buf.writeCompoundTag(defenderBanner);
        viewer.connection.sendPacket(new SCustomPayloadPlayPacket(CHANNEL, buf));
    }

    private void sendRemove(ServerPlayerEntity viewer) {
        if (viewer == null) {
            return;
        }
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeByte(1);
        buf.writeUniqueId(session.claimId);
        viewer.connection.sendPacket(new SCustomPayloadPlayPacket(CHANNEL, buf));
    }

    /** Session over (completed, failed, cancelled): every HUD of it disappears. */
    void close() {
        for (ServerBossInfo bar : bars.values()) {
            bar.removeAllPlayers();
            bar.setVisible(false);
        }
        for (UUID id : sent.keySet()) {
            sendRemove(core.server().getPlayerList().getPlayerByUUID(id));
        }
        sent.clear();
        sentAt.clear();
    }
}
