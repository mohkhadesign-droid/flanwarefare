package net.flanwarfare.alerts;

import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.AlertEntry;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.AlertThrottle;
import net.flanwarfare.team.SideResolver;
import net.flanwarfare.util.Msg;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.play.server.SPlaySoundEffectPacket;
import net.minecraft.network.play.server.STitlePacket;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Live "your base is being raided" alerts, separate from raid-window announcements.
 *
 * <p>Triggers (all during raid windows, all for ordinary claims only; admin claims never reach
 * this class because every caller excludes them by claim type, not by the owner's op status):
 * <ul>
 *   <li>Hostile raid action granted to a non-member: breaking, placing, opening containers,
 *   using doors, redstone, machines, hurting animals or entities; and explosions that remove
 *   blocks of the claim. Walking through, pearls, PvP and fake players do not count.</li>
 *   <li>Flag capture started (high priority, never throttled).</li>
 *   <li>Capture contested (chat, throttled), capture failed or cancelled, claim conquered.</li>
 * </ul>
 * Vanilla title, sound and chat packets only; no client mod needed. Offline owners get a
 * bounded, coalesced history shown at their next login.
 */
public final class RaidAlerts {
    private static final long HINT_INTERVAL_MILLIS = 5000L;
    private static final long CONTEST_INTERVAL_MILLIS = 15000L;

    public static final String TYPE_RAID = "under attack";
    public static final String TYPE_CAPTURE = "flag capture attempt";
    public static final String TYPE_CONQUERED = "claim conquered";

    private final WarCore core;
    private final Map<UUID, AlertThrottle> throttles = new HashMap<>();

    public RaidAlerts(WarCore core) {
        this.core = core;
    }

    private static boolean enabled() {
        return WarConfig.ALERTS_ENABLED.get();
    }

    private AlertThrottle throttle(UUID claimId) {
        return throttles.computeIfAbsent(claimId, id -> new AlertThrottle());
    }

    /** True while the claim has had hostile activity within the ordinary alert cooldown. */
    public boolean underAttack(UUID claimId) {
        AlertThrottle throttle = throttles.get(claimId);
        return throttle != null && throttle.underAttack(System.currentTimeMillis(), cooldownMillis());
    }

    private static long cooldownMillis() {
        return WarConfig.ALERT_COOLDOWN_SECONDS.get() * 1000L;
    }

    // ------------------------------------------------------------------ triggers

    public void hostileAction(Claim claim, ClaimRecord record, String what) {
        if (!enabled() || claim.isAdminClaim()) {
            return;
        }
        AlertThrottle.Decision decision = throttle(record.claimId)
                .hostile(System.currentTimeMillis(), cooldownMillis(), HINT_INTERVAL_MILLIS);
        if (decision == AlertThrottle.Decision.NONE) {
            return;
        }
        String label = FlanBridge.label(claim);
        Set<UUID> recipients = recipients(claim);
        if (decision == AlertThrottle.Decision.TITLE) {
            core.audit("Raid activity on claim {} ({})", record.claimId, what);
            for (UUID id : recipients) {
                ServerPlayerEntity player = online(id);
                if (player == null) {
                    continue;
                }
                title(player, "YOUR BASE IS UNDER RAID!", "Enemies are attacking your territory!", TextFormatting.RED);
                sound(player, WarConfig.ALERT_RAID_SOUND.get());
                chat(player, "[WAR ALERT] Your claim \"" + label + "\" is under attack (" + what + ")"
                        + where(claim, record) + "!");
            }
            remember(claim, record, TYPE_RAID);
        } else {
            for (UUID id : recipients) {
                ServerPlayerEntity player = online(id);
                if (player != null) {
                    Msg.actionBar(player, Msg.text("WAR ALERT: \"" + label + "\" is still under attack", TextFormatting.RED));
                }
            }
        }
    }

    public void captureStarted(Claim claim, ClaimRecord record, int percent) {
        if (!enabled() || claim.isAdminClaim()) {
            return;
        }
        String label = FlanBridge.label(claim);
        core.audit("Flag capture started on claim {}", record.claimId);
        boolean showTitle = WarConfig.ALERT_CAPTURE_HIGH_PRIORITY.get()
                || throttle(record.claimId).hostile(System.currentTimeMillis(), cooldownMillis(), HINT_INTERVAL_MILLIS)
                == AlertThrottle.Decision.TITLE;
        for (UUID id : recipients(claim)) {
            ServerPlayerEntity player = online(id);
            if (player == null) {
                continue;
            }
            if (showTitle) {
                title(player, "YOUR FLAG IS BEING CAPTURED!", "Defend your flag before your claim is lost!", TextFormatting.DARK_RED);
                sound(player, WarConfig.ALERT_CAPTURE_SOUND.get());
            }
            chat(player, "[WAR ALERT] Your flag at \"" + label + "\" is being captured! Progress: " + percent + "%."
                    + flagAt(record));
        }
        remember(claim, record, TYPE_CAPTURE);
    }

    public void captureContested(Claim claim, ClaimRecord record, int percent, boolean byDefender) {
        if (!enabled() || claim.isAdminClaim()
                || !throttle(record.claimId).contestUpdate(System.currentTimeMillis(), CONTEST_INTERVAL_MILLIS)) {
            return;
        }
        String label = FlanBridge.label(claim);
        for (UUID id : recipients(claim)) {
            ServerPlayerEntity player = online(id);
            if (player != null) {
                chat(player, "[WAR ALERT] Capture of \"" + label + "\" is CONTESTED"
                        + (byDefender ? " by a defender" : " by rival attackers") + " - paused at " + percent + "%.");
            }
        }
    }

    public void captureFailed(Claim claim, ClaimRecord record) {
        resolution(claim, record, "The capture attempt on \"" + FlanBridge.label(claim) + "\" has failed. Your flag is safe.");
    }

    public void captureCancelled(Claim claim, ClaimRecord record) {
        resolution(claim, record, "The capture attempt on \"" + FlanBridge.label(claim)
                + "\" was cancelled by an administrator.");
    }

    private void resolution(Claim claim, ClaimRecord record, String text) {
        if (!enabled() || claim.isAdminClaim()) {
            return;
        }
        for (UUID id : recipients(claim)) {
            ServerPlayerEntity player = online(id);
            if (player != null) {
                Msg.send(player, Msg.text("[WAR ALERT] " + text, TextFormatting.GREEN));
            }
        }
    }

    /**
     * Distinct loss notification. Recipients must be computed before the conquest wipes the
     * claim's members ({@link #recipients} is called by the conquest service beforehand).
     */
    public void conquered(Set<UUID> previousRecipients, UUID previousOwner, ClaimRecord record, String label,
                          String conquerorName) {
        if (!enabled()) {
            return;
        }
        for (UUID id : previousRecipients) {
            ServerPlayerEntity player = online(id);
            if (player == null) {
                continue;
            }
            title(player, "YOUR CLAIM WAS CONQUERED!", "\"" + label + "\" was taken by " + conquerorName, TextFormatting.DARK_RED);
            sound(player, WarConfig.ALERT_CAPTURE_SOUND.get());
            chat(player, "[WAR ALERT] \"" + label + "\" has been conquered by " + conquerorName + ".");
        }
        if (online(previousOwner) == null && WarConfig.ALERT_OFFLINE_SUMMARY.get()) {
            core.data().recordAlert(previousOwner, TYPE_CONQUERED, record.claimId, label, record.dimension,
                    record.flagX, record.flagZ);
        }
    }

    // ------------------------------------------------------------------ recipients and output

    /** Owner, defender members and team members per config, minus players who muted alerts. */
    public Set<UUID> recipients(Claim claim) {
        Set<UUID> recipients = new LinkedHashSet<>();
        UUID owner = claim.getOwner();
        if (WarConfig.ALERT_OWNER.get()) {
            recipients.add(owner);
        }
        if (WarConfig.ALERT_DEFENDER_MEMBERS.get()) {
            for (UUID member : FlanBridge.members(claim).keySet()) {
                if (SideResolver.isDefenderMember(claim, member)) {
                    recipients.add(member);
                }
            }
        }
        if (WarConfig.ALERT_TEAM_MEMBERS.get()) {
            for (ServerPlayerEntity player : core.server().getPlayerList().getPlayers()) {
                if (core.sides().sameTeam(owner, player.getUniqueID())) {
                    recipients.add(player.getUniqueID());
                }
            }
        }
        recipients.removeIf(id -> core.data().isMuted(id));
        return recipients;
    }

    private ServerPlayerEntity online(UUID id) {
        return core.server().getPlayerList().getPlayerByUUID(id);
    }

    private void remember(Claim claim, ClaimRecord record, String type) {
        if (!WarConfig.ALERT_OFFLINE_SUMMARY.get() || online(claim.getOwner()) != null || core.data().isMuted(claim.getOwner())) {
            return;
        }
        core.data().recordAlert(claim.getOwner(), type, record.claimId, FlanBridge.label(claim), record.dimension,
                record.flagX, record.flagZ);
    }

    private static String where(Claim claim, ClaimRecord record) {
        if (!WarConfig.ALERT_COORDINATES.get()) {
            return " in " + record.dimension;
        }
        int x;
        int z;
        if (record.hasFlag) {
            x = record.flagX;
            z = record.flagZ;
        } else {
            int[] d = claim.getDimensions();
            x = (d[0] + d[1]) / 2;
            z = (d[2] + d[3]) / 2;
        }
        return " at X: " + x + ", Z: " + z + " (" + record.dimension + ")";
    }

    private static String flagAt(ClaimRecord record) {
        return WarConfig.ALERT_COORDINATES.get() && record.hasFlag
                ? " Flag at X: " + record.flagX + ", Y: " + record.flagY + ", Z: " + record.flagZ + " (" + record.dimension + ")."
                : "";
    }

    private static void title(ServerPlayerEntity player, String title, String subtitle, TextFormatting color) {
        if (!WarConfig.ALERT_TITLE.get()) {
            return;
        }
        ITextComponent main = Msg.text(title, color).mergeStyle(TextFormatting.BOLD);
        ITextComponent sub = Msg.text(subtitle, TextFormatting.YELLOW);
        player.connection.sendPacket(new STitlePacket(5, 70, 20));
        player.connection.sendPacket(new STitlePacket(STitlePacket.Type.SUBTITLE, sub));
        player.connection.sendPacket(new STitlePacket(STitlePacket.Type.TITLE, main));
    }

    private static void sound(ServerPlayerEntity player, String id) {
        if (!WarConfig.ALERT_SOUND.get() || id == null || id.isEmpty()) {
            return;
        }
        ResourceLocation location = ResourceLocation.tryCreate(id);
        SoundEvent sound = location == null ? null : ForgeRegistries.SOUND_EVENTS.getValue(location);
        if (sound != null) {
            player.connection.sendPacket(new SPlaySoundEffectPacket(sound, SoundCategory.MASTER,
                    player.getPosX(), player.getPosY(), player.getPosZ(), 1.0F, 1.0F));
        }
    }

    private static void chat(ServerPlayerEntity player, String text) {
        if (WarConfig.ALERT_CHAT.get()) {
            Msg.send(player, Msg.text(text, TextFormatting.RED));
        }
    }

    /** Login summary of attacks that happened while the player was offline. */
    public void loginSummary(ServerPlayerEntity player) {
        List<AlertEntry> entries = core.data().takeAlerts(player.getUniqueID());
        if (entries.isEmpty() || !WarConfig.ALERT_OFFLINE_SUMMARY.get()) {
            return;
        }
        Msg.send(player, Msg.text("[WAR ALERT] While you were away:", TextFormatting.RED));
        long now = System.currentTimeMillis();
        for (AlertEntry entry : entries) {
            String location = WarConfig.ALERT_COORDINATES.get() ? " at X: " + entry.x + ", Z: " + entry.z : "";
            Msg.send(player, Msg.text(" - \"" + entry.label + "\"" + location + " (" + entry.dimension + "): " + entry.type
                    + (entry.count > 1 ? " x" + entry.count : "") + ", " + Msg.duration(now - entry.lastAt) + " ago",
                    TextFormatting.GOLD));
        }
    }

    public void forget(UUID claimId) {
        throttles.remove(claimId);
    }
}
