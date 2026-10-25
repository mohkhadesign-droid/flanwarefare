package net.flanwarfare.claims;

import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.MemberNotice;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.MembershipChange;
import net.flanwarfare.util.Msg;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.TextFormatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tells players when they are added to, removed from, or have their permissions changed in a
 * Flan claim, whichever Flan command or menu was used.
 *
 * <p>Flan 1.7.2 has no membership event. Every membership change goes through
 * {@code Claim.setPlayerGroup}, {@code Claim.removePermGroup} (drops a group with its members)
 * and group permission edits through {@code Claim.editPerms}; {@code ClaimMixin} reports the
 * player's group before such a call and, only if the call returned success, marks the change.
 * A few seconds after the last change the net result is compared with the state before the
 * first one ({@link MembershipChange}), so only committed, real differences are announced,
 * once.
 */
public final class MemberNotices {
    private static final long DEBOUNCE_MILLIS = 3000L;
    private static final int MAX_QUEUED_PER_PLAYER = 20;

    private static final class Pending {
        final Claim claim;
        final UUID player;
        final String before;
        boolean committed;
        boolean groupPermsChanged;
        long due;

        Pending(Claim claim, UUID player, String before) {
            this.claim = claim;
            this.player = player;
            this.before = before;
        }
    }

    private final WarCore core;
    private final Map<String, Pending> pending = new HashMap<>();

    public MemberNotices(WarCore core) {
        this.core = core;
    }

    private static String key(Claim claim, UUID player) {
        return claim.getClaimID() + "|" + player;
    }

    /** Before a Flan membership/permission call: remember the player's current group. */
    public void before(Claim claim, UUID player) {
        String key = key(claim, player);
        if (!pending.containsKey(key)) {
            Pending p = new Pending(claim, player, FlanBridge.members(claim).get(player));
            p.due = System.currentTimeMillis() + DEBOUNCE_MILLIS;
            pending.put(key, p);
        }
    }

    /** After the call returned success: the change is committed in Flan. */
    public void committed(Claim claim, UUID player, boolean groupPermsChanged) {
        Pending p = pending.get(key(claim, player));
        if (p == null) {
            return;
        }
        p.committed = true;
        p.groupPermsChanged |= groupPermsChanged;
        p.due = System.currentTimeMillis() + DEBOUNCE_MILLIS;
    }

    public void tick(long ticks) {
        if (ticks % 10 != 0 || pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Pending> it = pending.values().iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (now < p.due) {
                continue;
            }
            it.remove();
            if (!p.committed || p.claim.isRemoved()) {
                continue;
            }
            String after = FlanBridge.members(p.claim).get(p.player);
            MembershipChange.Type type = MembershipChange.classify(p.before, after, p.groupPermsChanged);
            if (type != MembershipChange.Type.NONE) {
                deliver(p.claim, p.player, type.name(), null);
            }
        }
    }

    /**
     * Conquest wipes the claim's members and subclaims directly (not through Flan's
     * mutators), so their notice is sent here, before the wipe.
     */
    public void conquestRemoval(Claim parent, String conquerorName) {
        Set<UUID> members = new LinkedHashSet<>(FlanBridge.members(parent).keySet());
        for (Claim sub : parent.getAllSubclaims()) {
            members.addAll(FlanBridge.members(sub).keySet());
            for (UUID id : FlanBridge.members(sub).keySet()) {
                pending.remove(key(sub, id));
            }
        }
        for (UUID id : members) {
            pending.remove(key(parent, id));
            deliver(parent, id, "CONQUERED", conquerorName);
        }
    }

    private boolean wanted(String type) {
        if (!WarConfig.MEMBER_NOTICES.get()) {
            return false;
        }
        switch (type) {
            case "ADDED":
                return WarConfig.MEMBER_NOTICE_ADDED.get();
            case "CHANGED":
                return WarConfig.MEMBER_NOTICE_CHANGED.get();
            default:
                return WarConfig.MEMBER_NOTICE_REMOVED.get();
        }
    }

    private void deliver(Claim claim, UUID player, String type, String conqueror) {
        if (!wanted(type)) {
            return;
        }
        MemberNotice notice = new MemberNotice();
        notice.type = type;
        notice.claimId = claim.getClaimID();
        notice.label = label(claim);
        notice.ownerName = claim.isAdminClaim() ? "the server" : FlanBridge.playerName(core.server(), claim.getOwner());
        if (conqueror != null) {
            notice.ownerName = conqueror;
        }
        notice.dimension = FlanBridge.dimensionId(claim.getWorld());
        int[] d = claim.getDimensions();
        notice.x = (d[0] + d[1]) / 2;
        notice.z = (d[2] + d[3]) / 2;
        notice.at = System.currentTimeMillis();
        ServerPlayerEntity online = core.server().getPlayerList().getPlayerByUUID(player);
        if (online != null) {
            show(online, notice);
            return;
        }
        if (!WarConfig.MEMBER_NOTICE_OFFLINE.get()) {
            return;
        }
        queue(player, notice);
    }

    /** Keeps one net notice per claim for an offline player. */
    private void queue(UUID player, MemberNotice notice) {
        List<MemberNotice> list = core.data().memberNotices(player);
        Iterator<MemberNotice> it = list.iterator();
        while (it.hasNext()) {
            MemberNotice old = it.next();
            if (!old.claimId.equals(notice.claimId)) {
                continue;
            }
            it.remove();
            if (!"CONQUERED".equals(notice.type) && !"CONQUERED".equals(old.type)) {
                MembershipChange.Type merged = MembershipChange.combine(
                        MembershipChange.Type.valueOf(old.type), MembershipChange.Type.valueOf(notice.type));
                if (merged == MembershipChange.Type.NONE) {
                    core.data().markDirty();
                    return;
                }
                notice.type = merged.name();
            }
        }
        list.add(notice);
        while (list.size() > MAX_QUEUED_PER_PLAYER) {
            list.remove(0);
        }
        core.data().markDirty();
    }

    private static String label(Claim claim) {
        Claim parent = claim.parentClaim();
        if (parent == null) {
            return FlanBridge.label(claim);
        }
        String name = claim.getClaimName();
        return (name == null || name.isEmpty() ? "a subclaim" : name) + " (in " + FlanBridge.label(parent) + ")";
    }

    private static void show(ServerPlayerEntity player, MemberNotice n) {
        String where = " - " + n.dimension + " at X " + n.x + ", Z " + n.z;
        switch (n.type) {
            case "ADDED":
                Msg.send(player, Msg.text("[Flan Warfare] You have been added to " + n.ownerName + "'s claim: \""
                        + n.label + "\"" + where + ".", TextFormatting.GREEN));
                Msg.send(player, Msg.text("You now have the permissions assigned to you by the claim owner.", TextFormatting.GRAY));
                Msg.actionBar(player, Msg.text("You were added to " + n.label + "!", TextFormatting.GREEN));
                break;
            case "REMOVED":
                Msg.send(player, Msg.text("[Flan Warfare] You have been removed from " + n.ownerName + "'s claim: \""
                        + n.label + "\"" + where + ".", TextFormatting.YELLOW));
                Msg.send(player, Msg.text("Your previous claim permissions have been revoked.", TextFormatting.GRAY));
                break;
            case "CONQUERED":
                Msg.send(player, Msg.text("[Flan Warfare] Your access to \"" + n.label + "\"" + where
                        + " ended: the claim was conquered by " + n.ownerName + ".", TextFormatting.YELLOW));
                break;
            default:
                Msg.send(player, Msg.text("[Flan Warfare] Your permissions in \"" + n.label + "\"" + where
                        + " have been updated.", TextFormatting.AQUA));
                break;
        }
    }

    /** Delivers queued notices at login. */
    public void loginDelivery(ServerPlayerEntity player) {
        List<MemberNotice> notices = core.data().takeMemberNotices(player.getUniqueID());
        if (notices.isEmpty()) {
            return;
        }
        Msg.send(player, Msg.text("[Flan Warfare] Claim membership changes while you were away:", TextFormatting.GOLD));
        for (MemberNotice notice : new ArrayList<>(notices)) {
            show(player, notice);
        }
    }

    public int pendingCount() {
        return pending.size();
    }
}
