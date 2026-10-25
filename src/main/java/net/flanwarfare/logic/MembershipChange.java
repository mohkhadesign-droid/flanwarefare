package net.flanwarfare.logic;

/**
 * Net effect of Flan membership edits on one player in one claim, comparing the player's
 * permission group before the first edit with the group after the last one. Only real,
 * committed differences produce a notice, so pressing buttons that change nothing (or adding
 * and removing someone within a few seconds) says nothing.
 */
public final class MembershipChange {

    public enum Type { NONE, ADDED, REMOVED, CHANGED }

    private MembershipChange() {
    }

    /**
     * @param before        group before the edits (null = not a member)
     * @param after         group after the edits (null = not a member)
     * @param groupPermsChanged the player's group had its permissions edited meanwhile
     */
    public static Type classify(String before, String after, boolean groupPermsChanged) {
        if (before == null && after == null) {
            return Type.NONE;
        }
        if (before == null) {
            return Type.ADDED;
        }
        if (after == null) {
            return Type.REMOVED;
        }
        return !before.equals(after) || groupPermsChanged ? Type.CHANGED : Type.NONE;
    }

    /**
     * Merges a new notice into one still waiting for an offline player, so they read the net
     * result: added-then-removed cancels out; removed-then-added means they are still a member
     * whose permissions may differ; added-then-changed is still "added".
     */
    public static Type combine(Type queued, Type next) {
        if (queued == null || queued == Type.NONE) {
            return next;
        }
        if (next == Type.NONE) {
            return queued;
        }
        if (queued == Type.ADDED) {
            return next == Type.REMOVED ? Type.NONE : Type.ADDED;
        }
        if (queued == Type.REMOVED) {
            return next == Type.ADDED ? Type.CHANGED : Type.REMOVED;
        }
        return next == Type.REMOVED ? Type.REMOVED : Type.CHANGED;
    }
}
