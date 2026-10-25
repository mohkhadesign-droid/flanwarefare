package net.flanwarfare.data;

import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/** Add-on state for one Flan parent claim. The Flan claim UUID is the identity. */
public final class ClaimRecord {
    public UUID claimId;
    public String dimension;
    /** Last owner seen in Flan. Flan stays authoritative; this is reconciled continuously. */
    public UUID owner;
    public ClaimState state = ClaimState.PENDING_FLAG;
    public long createdAt;
    public long graceEndsAt;
    /** 0 = never expires. */
    public long expiresAt;
    public boolean expiryWarned;
    public long lastReminderAt;

    public boolean hasFlag;
    public int flagX;
    public int flagY;
    public int flagZ;
    public int flagRotation;
    /** Design currently shown on the flag, as SNBT of a banner ItemStack. */
    public String designSnbt;
    /** The design changed while the flag's chunk was unloaded; apply when it loads. */
    public boolean designPending;
    public boolean flagEverPlaced;
    public long flagPlacedAt;
    public long lastMovedAt;
    public int restoreFailures;

    public boolean conquered;
    public long conqueredAt;
    public UUID conqueredFrom;
    public int timesConquered;

    public String recoveryReason;

    public BlockPos flagPos() {
        return hasFlag ? new BlockPos(flagX, flagY, flagZ) : null;
    }

    public void setFlagPos(BlockPos pos) {
        if (pos == null) {
            hasFlag = false;
            return;
        }
        hasFlag = true;
        flagX = pos.getX();
        flagY = pos.getY();
        flagZ = pos.getZ();
    }

    /** Raid rules and capture apply to ACTIVE and RECOVERY_REQUIRED claims. */
    public boolean isWarEnabled() {
        return state == ClaimState.ACTIVE || state == ClaimState.RECOVERY_REQUIRED;
    }
}
