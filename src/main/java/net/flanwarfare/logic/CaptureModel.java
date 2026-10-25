package net.flanwarfare.logic;

import java.util.Set;

/**
 * Server-authoritative capture progress for one flag, independent of Minecraft classes.
 *
 * <p>Progress is measured in ticks of uncontested control. A full bar equals
 * {@code captureTicks}. Decay removes a full bar in {@code decayTicks}.
 *
 * <p>Rules, applied on every {@link #step}:
 * <ol>
 *   <li>If progress is not allowed (raid closed, or this session belongs to an earlier raid
 *       window) progress only decays, whoever is standing at the flag.</li>
 *   <li>No eligible attackers present: progress decays.</li>
 *   <li>A defender present (and defender contesting enabled): progress pauses (CONTESTED).</li>
 *   <li>Several attacking sides present (and rival contesting enabled): progress pauses.</li>
 *   <li>Exactly one side present: if it owns the progress (or progress is zero) it gains;
 *       otherwise the previous side's progress decays and the newcomer only takes over once
 *       progress reaches zero. Progress is never transferred between sides.</li>
 * </ol>
 */
public final class CaptureModel {

    public enum Status {
        IDLE, CAPTURING, CONTESTED, DECAYING, COMPLETE
    }

    private final long captureTicks;
    private final long decayTicks;
    private double progress;
    private String side;
    private Status status = Status.IDLE;

    public CaptureModel(long captureTicks, long decayTicks) {
        if (captureTicks <= 0 || decayTicks <= 0) {
            throw new IllegalArgumentException("capture and decay durations must be positive");
        }
        this.captureTicks = captureTicks;
        this.decayTicks = decayTicks;
    }

    public Status step(long dt, boolean progressAllowed, Set<String> attackerSides,
                       boolean defenderPresent, boolean defendersContest, boolean rivalsContest) {
        if (status == Status.COMPLETE) {
            return status;
        }
        if (dt <= 0) {
            return status;
        }
        if (!progressAllowed || attackerSides.isEmpty()) {
            decay(dt);
            return status;
        }
        if (defenderPresent && defendersContest) {
            status = Status.CONTESTED;
            return status;
        }
        String acting;
        if (attackerSides.size() > 1) {
            if (rivalsContest || side == null || !attackerSides.contains(side)) {
                status = Status.CONTESTED;
                return status;
            }
            acting = side;
        } else {
            acting = attackerSides.iterator().next();
        }

        if (side == null || progress <= 0) {
            side = acting;
        }
        if (acting.equals(side)) {
            progress = Math.min(captureTicks, progress + dt);
            status = progress >= captureTicks ? Status.COMPLETE : Status.CAPTURING;
        } else {
            // A rival holds the flag alone: the old side's progress erodes; no transfer.
            decay(dt);
            if (progress <= 0) {
                side = acting;
                status = Status.CAPTURING;
            }
        }
        return status;
    }

    private void decay(long dt) {
        progress = Math.max(0.0, progress - dt * ((double) captureTicks / (double) decayTicks));
        if (progress <= 0) {
            progress = 0;
            side = null;
            status = Status.IDLE;
        } else {
            status = Status.DECAYING;
        }
    }

    public double fraction() {
        return progress / captureTicks;
    }

    public double progressTicks() {
        return progress;
    }

    public String side() {
        return side;
    }

    public Status status() {
        return status;
    }

    public boolean isFinished() {
        return status == Status.IDLE && progress <= 0;
    }
}
