package net.flanwarfare.conquest;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.claim.ClaimStorage;
import net.flanwarfare.FlanWarfare;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.data.ClaimState;
import net.flanwarfare.data.ConquestTxn;
import net.flanwarfare.flan.Accounting;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.flan.Guards;
import net.flanwarfare.mixin.ClaimAccessor;
import net.flanwarfare.util.Msg;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.potion.Effect;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Conquest as a journaled transaction.
 *
 * <p>Order, chosen for Flan's save architecture (one JSON file per owner, written by
 * {@code ClaimStorage.save} for every owner marked dirty):
 * <ol>
 *   <li>Revalidate (raid open, claim intact, winner eligible, per-player limit).</li>
 *   <li>Lock the claim (one conquest at a time on the server thread) and journal a PENDING
 *   transaction with Flan's own JSON snapshot of the claim; flush the add-on file.</li>
 *   <li>Mutate Flan in memory, in one server tick: delete subclaims, clear members, groups,
 *   claim-wide overrides, potions, name, titles; set home to the flag; transfer ownership with
 *   Flan's own {@code transferOwner} (which updates its owner index and marks both owners'
 *   files dirty).</li>
 *   <li>Mark the record conquered (free) and switch the banner design.</li>
 *   <li>Save Flan's claim files for that dimension immediately, then mark the transaction
 *   COMMITTED and flush the add-on file.</li>
 * </ol>
 * There is no cross-file atomicity. On startup {@link #recoverPendingTransactions} compares
 * each PENDING journal entry with what Flan actually loaded and either finishes it
 * idempotently, marks it ABORTED (Flan never saved the change) or hands it to an administrator.
 * No path adds claim blocks, so no path can duplicate them.
 */
public final class ConquestService {
    private final WarCore core;
    private UUID locked;

    public ConquestService(WarCore core) {
        this.core = core;
    }

    /** Completes a capture. Returns true if ownership changed. */
    public boolean complete(Claim claim, ClaimRecord record, UUID winner, Set<UUID> present) {
        ServerPlayerEntity capturer = core.server().getPlayerList().getPlayerByUUID(winner);
        String problem = null;
        if (!core.raid().isActive()) {
            problem = "the raid window closed";
        } else if (capturer == null || !capturer.isAlive() || capturer.isSpectator() || !present.contains(winner)) {
            problem = "the capturing player is no longer at the flag";
        } else if (claim.isAdminClaim() || !record.isWarEnabled() || claim.isRemoved()) {
            problem = "the claim can no longer be captured";
        } else if (winner.equals(claim.getOwner())) {
            problem = "the capturer already owns the claim";
        } else if (locked != null) {
            problem = "another conquest is in progress";
        } else {
            int max = WarConfig.MAX_CONQUERED_PER_PLAYER.get();
            if (max >= 0 && Accounting.conqueredCount(core.server(), winner) >= max) {
                problem = "the capturer already holds the maximum of " + max + " conquered claims";
            }
        }
        if (problem != null) {
            Msg.send(capturer, Msg.error("Capture could not be completed: " + problem + "."));
            core.audit("Conquest of claim {} by {} rejected: {}", record.claimId, winner, problem);
            return false;
        }

        UUID oldOwner = claim.getOwner();
        ConquestTxn txn = new ConquestTxn();
        txn.txnId = UUID.randomUUID();
        txn.claimId = record.claimId;
        txn.dimension = record.dimension;
        txn.oldOwner = oldOwner;
        txn.newOwner = winner;
        txn.wasConqueredBefore = record.conquered;
        txn.previousDesign = record.designSnbt;
        txn.newDesign = core.flags().designFor(winner);
        txn.snapshotJson = claim.toJson(new JsonObject()).toString();
        txn.startedAt = System.currentTimeMillis();
        txn.phase = ConquestTxn.Phase.PENDING;

        locked = record.claimId;
        try {
            core.data().transactions().add(txn);
            core.data().markDirty();
            if (!core.saveNow()) {
                txn.phase = ConquestTxn.Phase.ABORTED;
                txn.note = "journal could not be written; nothing was changed";
                Msg.send(capturer, Msg.error("Capture could not be completed: the server could not save war data."));
                return false;
            }
            String label = FlanBridge.label(claim);
            Set<UUID> alertRecipients = core.alerts().recipients(claim);
            try {
                apply(claim, record, txn);
            } catch (RuntimeException e) {
                txn.phase = ConquestTxn.Phase.NEEDS_ADMIN;
                txn.note = "failed while applying: " + e;
                txn.finishedAt = System.currentTimeMillis();
                core.data().markDirty();
                core.saveNow();
                FlanWarfare.LOG.error("Conquest {} of claim {} failed while applying", txn.txnId, record.claimId, e);
                Msg.admins(core.server(), Msg.error("A conquest failed and needs attention: /war admin claim inspect "
                        + record.claimId));
                return false;
            }
            Guards.bypass(() -> {
                ClaimStorage.get(claim.getWorld()).save(core.server(), claim.getWorld().getDimensionKey());
                return null;
            });
            txn.phase = ConquestTxn.Phase.COMMITTED;
            txn.finishedAt = System.currentTimeMillis();
            core.data().markDirty();
            core.saveNow();
            core.audit("CONQUEST {}: claim {} ({}) transferred from {} to {}", txn.txnId, record.claimId, record.dimension,
                    oldOwner, winner);
            announce(label, oldOwner, winner);
            core.alerts().conquered(alertRecipients, oldOwner, record, label, FlanBridge.playerName(core.server(), winner));
            core.aura().conquestEffect(record);
            return true;
        } finally {
            locked = null;
        }
    }

    /**
     * Applies the conquest to Flan and the record. Idempotent: running it again on an already
     * conquered claim (crash recovery) changes nothing further.
     */
    private void apply(Claim claim, ClaimRecord record, ConquestTxn txn) {
        Guards.bypass(() -> {
            // Transfer first: if Flan refused, nothing else has been touched yet.
            if (!txn.newOwner.equals(claim.getOwner())) {
                ServerWorld world = claim.getWorld();
                FakePlayer asOldOwner = FakePlayerFactory.get(world,
                        new GameProfile(claim.getOwner(), "[FlanWarfare]"));
                boolean moved = ClaimStorage.get(world).transferOwner(claim, asOldOwner, txn.newOwner);
                if (!moved) {
                    throw new IllegalStateException("Flan refused the ownership transfer of claim " + claim.getClaimID());
                }
            }
            for (Claim sub : new ArrayList<>(claim.getAllSubclaims())) {
                claim.deleteSubClaim(sub);
            }
            ClaimAccessor access = (ClaimAccessor) (Object) claim;
            access.flanwarfare$getPlayersGroups().clear();
            access.flanwarfare$getPermissions().clear();
            access.flanwarfare$getGlobalPerm().clear();
            for (Effect effect : new ArrayList<>(claim.getPotions().keySet())) {
                claim.removePotion(effect);
            }
            claim.setClaimName("");
            claim.setEnterTitle(null, null);
            claim.setLeaveTitle(null, null);
            BlockPos flag = record.flagPos();
            if (flag != null) {
                claim.setHomePos(flag);
            }
            claim.setDirty(true);
            return null;
        });
        record.owner = txn.newOwner;
        record.conquered = true;
        record.conqueredAt = System.currentTimeMillis();
        record.conqueredFrom = txn.oldOwner;
        if (txn.phase == ConquestTxn.Phase.PENDING) {
            record.timesConquered++;
        }
        record.state = ClaimState.ACTIVE;
        record.recoveryReason = null;
        record.designSnbt = txn.newDesign;
        record.lastMovedAt = 0;
        core.flags().showDesign(record);
        core.data().markDirty();
    }

    private void announce(String label, UUID oldOwner, UUID newOwner) {
        String oldName = FlanBridge.playerName(core.server(), oldOwner);
        String newName = FlanBridge.playerName(core.server(), newOwner);
        Msg.broadcast(core.server(), Msg.error(newName + " has conquered '" + label + "' from " + oldName + "!"));
        ServerPlayerEntity winner = core.server().getPlayerList().getPlayerByUUID(newOwner);
        Msg.send(winner, Msg.ok("The claim is yours. It costs no claim blocks or claim slots, cannot be resized or "
                + "transferred, and its old members, groups and subclaims were removed. Manage it with Flan as usual."));
    }

    /** Startup reconciliation of journal entries left PENDING by a crash. */
    public void recoverPendingTransactions() {
        for (ConquestTxn txn : core.data().transactions()) {
            if (txn.phase != ConquestTxn.Phase.PENDING) {
                continue;
            }
            ClaimRecord record = core.data().claim(txn.claimId);
            Claim claim = record == null ? null : FlanBridge.find(core.server(), record);
            if (claim == null) {
                txn.phase = ConquestTxn.Phase.NEEDS_ADMIN;
                txn.note = "claim not found after restart; Flan's snapshot is kept in this entry";
                FlanWarfare.LOG.error("Conquest {} of claim {} was interrupted and the claim is missing. "
                        + "Inspect with /war admin claim inspect {}", txn.txnId, txn.claimId, txn.claimId);
            } else if (txn.newOwner.equals(claim.getOwner())) {
                apply(claim, record, txn);
                Guards.bypass(() -> {
                    ClaimStorage.get(claim.getWorld()).save(core.server(), claim.getWorld().getDimensionKey());
                    return null;
                });
                txn.phase = ConquestTxn.Phase.COMMITTED;
                txn.note = "completed during startup recovery";
                FlanWarfare.LOG.warn("Interrupted conquest {} of claim {} completed during recovery", txn.txnId, txn.claimId);
            } else if (txn.oldOwner.equals(claim.getOwner())) {
                txn.phase = ConquestTxn.Phase.ABORTED;
                txn.note = "Flan never saved the transfer; claim unchanged";
                FlanWarfare.LOG.warn("Interrupted conquest {} of claim {} aborted: Flan still has the original owner",
                        txn.txnId, txn.claimId);
            } else {
                txn.phase = ConquestTxn.Phase.NEEDS_ADMIN;
                txn.note = "claim has an unexpected owner " + claim.getOwner();
                if (record.state == ClaimState.ACTIVE) {
                    record.state = ClaimState.RECOVERY_REQUIRED;
                    record.recoveryReason = "interrupted conquest " + txn.txnId + " left an unexpected owner";
                }
                FlanWarfare.LOG.error("Conquest {} of claim {} needs an administrator: {}", txn.txnId, txn.claimId, txn.note);
            }
            txn.finishedAt = System.currentTimeMillis();
            core.data().markDirty();
        }
    }

    public ConquestTxn lastCommitted(UUID claimId) {
        List<ConquestTxn> all = core.data().transactions();
        for (int i = all.size() - 1; i >= 0; i--) {
            ConquestTxn txn = all.get(i);
            if (txn.claimId.equals(claimId) && txn.phase == ConquestTxn.Phase.COMMITTED) {
                return txn;
            }
        }
        return null;
    }

    /**
     * Administrator rollback of the most recent conquest of a claim: ownership, conquered status
     * and banner design go back. The previous members, groups and subclaims are NOT restored
     * (they are kept in the journal snapshot for reference only).
     */
    public String rollback(UUID claimId, String adminName) {
        ConquestTxn txn = lastCommitted(claimId);
        if (txn == null) {
            return "No committed conquest is recorded for this claim.";
        }
        ClaimRecord record = core.data().claim(claimId);
        Claim claim = record == null ? null : FlanBridge.find(core.server(), record);
        if (claim == null) {
            return "The claim no longer exists.";
        }
        if (!txn.newOwner.equals(claim.getOwner())) {
            return "The claim's owner changed since that conquest; refusing to roll back automatically.";
        }
        ServerWorld world = claim.getWorld();
        boolean moved = Guards.bypass(() -> ClaimStorage.get(world).transferOwner(claim,
                FakePlayerFactory.get(world, new GameProfile(claim.getOwner(), "[FlanWarfare]")), txn.oldOwner));
        if (!moved) {
            return "Flan refused the transfer.";
        }
        record.owner = txn.oldOwner;
        record.conquered = txn.wasConqueredBefore;
        record.designSnbt = txn.previousDesign;
        core.flags().showDesign(record);
        Guards.bypass(() -> {
            ClaimStorage.get(world).save(core.server(), world.getDimensionKey());
            return null;
        });
        txn.phase = ConquestTxn.Phase.ROLLED_BACK;
        txn.note = "rolled back by " + adminName;
        core.data().markDirty();
        core.saveNow();
        core.audit("Conquest {} of claim {} rolled back by {}", txn.txnId, claimId, adminName);
        return null;
    }
}
