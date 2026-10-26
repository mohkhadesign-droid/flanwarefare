package net.flanwarfare.claims;

import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.claim.ClaimStorage;
import io.github.flemmli97.flan.player.EnumEditMode;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.data.ClaimState;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.flan.Guards;
import net.flanwarfare.util.Msg;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Registration, reminders, expiry and reconciliation of claim records.
 *
 * <p>Flan 1.7.2 has no claim-creation event, so new claims are discovered two ways: lazily,
 * the first time anything asks about them (every permission check and every guard), and by a
 * scan of online players' claims every 2 seconds. A claim that slipped past both would still
 * have Flan's normal protection, which is the safe direction.
 */
public final class ClaimLifecycle {
    private final WarCore core;

    public ClaimLifecycle(WarCore core) {
        this.core = core;
    }

    /** Record for a parent player claim, registering it as PENDING_FLAG if unseen. */
    public ClaimRecord recordFor(Claim parent) {
        ClaimRecord record = core.data().claim(parent.getClaimID());
        if (record == null) {
            record = register(parent);
        } else if (!parent.getOwner().equals(record.owner)) {
            // Ownership changed through Flan itself (e.g. an ordinary /flan transferClaim of an
            // active claim, or an admin). Flan is authoritative.
            core.audit("Claim {} owner changed in Flan from {} to {}", parent.getClaimID(), record.owner, parent.getOwner());
            record.owner = parent.getOwner();
            core.data().markDirty();
        }
        return record;
    }

    private ClaimRecord register(Claim parent) {
        long now = System.currentTimeMillis();
        ClaimRecord record = new ClaimRecord();
        record.claimId = parent.getClaimID();
        record.dimension = FlanBridge.dimensionId(parent.getWorld());
        record.owner = parent.getOwner();
        record.state = ClaimState.PENDING_FLAG;
        record.createdAt = now;
        record.graceEndsAt = now + WarConfig.PLACEMENT_GRACE_SECONDS.get() * 1000L;
        int lifetime = WarConfig.INACTIVE_EXPIRATION_SECONDS.get();
        record.expiresAt = lifetime > 0 ? now + lifetime * 1000L : 0L;
        record.lastReminderAt = now;
        core.data().claims().put(record.claimId, record);
        core.data().markDirty();
        core.audit("Registered new claim {} of {} in {} as PENDING_FLAG", record.claimId, record.owner, record.dimension);
        ServerPlayerEntity owner = core.server().getPlayerList().getPlayerByUUID(record.owner);
        if (owner != null) {
            Msg.send(owner, Msg.warn("Your new claim is INACTIVE: it does not protect anything and cannot be managed "
                    + "until you place its flag. Stand inside it and run /flag place. "
                    + "Reminder period: " + Msg.duration(record.graceEndsAt - now) + "."));
        }
        return record;
    }

    public void tick(long ticks) {
        if (ticks % 40 == 0) {
            scanOnlinePlayers();
        }
        if (ticks % 100 == 0) {
            reconcileAll();
            remindAndExpire();
        }
    }

    private void scanOnlinePlayers() {
        for (ServerPlayerEntity player : core.server().getPlayerList().getPlayers()) {
            for (ServerWorld world : core.server().getWorlds()) {
                Collection<Claim> owned = ClaimStorage.get(world).allClaimsFromPlayer(player.getUniqueID());
                if (owned == null) {
                    continue;
                }
                for (Claim claim : owned) {
                    if (claim.parentClaim() == null && !claim.isAdminClaim() && !core.isExcluded(claim)) {
                        recordFor(claim);
                    }
                }
            }
        }
    }

    /** Drops records of deleted claims (removing their flags) and syncs owners. */
    public void reconcileAll() {
        List<ClaimRecord> gone = new ArrayList<>();
        for (ClaimRecord record : core.data().claims().values()) {
            Claim claim = FlanBridge.find(core.server(), record);
            if (claim == null) {
                gone.add(record);
            } else if (!claim.getOwner().equals(record.owner)) {
                recordFor(claim);
            }
        }
        for (ClaimRecord record : gone) {
            core.flags().removeFlagOf(record);
            core.data().claims().remove(record.claimId);
            core.capture().cancel(record.claimId);
            core.alerts().forget(record.claimId);
            core.ranks().claimDeleted(record.claimId);
            core.data().markDirty();
            core.audit("Claim {} no longer exists in Flan; record and flag removed", record.claimId);
        }
    }

    private void remindAndExpire() {
        long now = System.currentTimeMillis();
        long interval = WarConfig.REMINDER_INTERVAL_SECONDS.get() * 1000L;
        long warning = WarConfig.EXPIRATION_WARNING_SECONDS.get() * 1000L;
        List<ClaimRecord> expired = new ArrayList<>();
        for (ClaimRecord record : core.data().claims().values()) {
            if (record.state != ClaimState.PENDING_FLAG) {
                continue;
            }
            ServerPlayerEntity owner = core.server().getPlayerList().getPlayerByUUID(record.owner);
            if (record.expiresAt > 0 && now >= record.expiresAt) {
                expired.add(record);
                continue;
            }
            if (record.expiresAt > 0 && !record.expiryWarned && now >= record.expiresAt - warning && owner != null) {
                record.expiryWarned = true;
                core.data().markDirty();
                Msg.send(owner, Msg.error("One of your claims has no flag and expires in "
                        + Msg.duration(record.expiresAt - now) + ". Run /flag place inside it."));
            }
            if (owner != null && now - record.lastReminderAt >= interval) {
                record.lastReminderAt = now;
                String timing = now < record.graceEndsAt
                        ? "Reminder period ends in " + Msg.duration(record.graceEndsAt - now) + "."
                        : "The reminder period is over; the claim stays inactive until you place the flag.";
                Msg.send(owner, Msg.warn("You own an inactive claim without a flag (" + record.dimension + "). "
                        + "It protects nothing. Stand inside it and run /flag place. " + timing));
            }
        }
        for (ClaimRecord record : expired) {
            handleExpired(record);
        }
    }

    private void handleExpired(ClaimRecord record) {
        if (!WarConfig.AUTO_ABANDON_EXPIRED.get()) {
            return; // listed by /war admin status; an administrator decides
        }
        Claim claim = FlanBridge.find(core.server(), record);
        if (claim == null || claim.isAdminClaim() || !claim.getOwner().equals(record.owner)
                || record.state != ClaimState.PENDING_FLAG || record.flagEverPlaced) {
            core.audit("Expired claim {} not abandoned automatically: state is not certain", record.claimId);
            return;
        }
        if (deleteThroughFlan(claim)) {
            core.data().claims().remove(record.claimId);
            core.data().markDirty();
            core.saveNow();
            core.audit("Expired inactive claim {} of {} abandoned through Flan", record.claimId, record.owner);
            ServerPlayerEntity owner = core.server().getPlayerList().getPlayerByUUID(record.owner);
            Msg.send(owner, Msg.error("Your inactive claim expired without a flag and was abandoned."));
        }
    }

    /** Deletes a parent claim with Flan's own deletion path, so its accounting stays correct. */
    public boolean deleteThroughFlan(Claim claim) {
        ServerWorld world = claim.getWorld();
        return Guards.bypass(() -> ClaimStorage.get(world).deleteClaim(claim, true, EnumEditMode.DEFAULT, world));
    }

    public List<ClaimRecord> expiredPending() {
        long now = System.currentTimeMillis();
        List<ClaimRecord> list = new ArrayList<>();
        for (ClaimRecord record : core.data().claims().values()) {
            if (record.state == ClaimState.PENDING_FLAG && record.expiresAt > 0 && now >= record.expiresAt) {
                list.add(record);
            }
        }
        return list;
    }

    public List<ClaimRecord> pendingOf(UUID owner) {
        List<ClaimRecord> list = new ArrayList<>();
        for (ClaimRecord record : core.data().claims().values()) {
            if (record.state == ClaimState.PENDING_FLAG && owner.equals(record.owner)) {
                list.add(record);
            }
        }
        return list;
    }
}
