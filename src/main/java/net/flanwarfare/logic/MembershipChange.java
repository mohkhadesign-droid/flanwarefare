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
     * @param before           group before the edits (null = not a member)
     * @param after            group after the edits (null = not a member)
     * @param effectiveChanged the player's effective permissions differ before/after
     *                         (a group switch or group edit with no effective difference is
     *                         not a meaningful change)
     */
    public static Type classify(String before, String after, boolean effectiveChanged) {
        if (before == null && after == null) {
            return Type.NONE;
        }
        if (before == null) {
            return Type.ADDED;
        }
        if (after == null) {
            return Type.REMOVED;
        }
        return effectiveChanged ? Type.CHANGED : Type.NONE;
    }

    /**
     * Readable lines describing what changed between two effective permission sets (Flan
     * permission ids). Entering (CANSTAY) gets its own sentence; others are listed.
     */
    public static java.util.List<String> describeDiff(java.util.Set<String> before, java.util.Set<String> after) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.List<String> gained = new java.util.ArrayList<>();
        java.util.List<String> lost = new java.util.ArrayList<>();
        for (String id : after) {
            if (!before.contains(id)) {
                gained.add(id);
            }
        }
        for (String id : before) {
            if (!after.contains(id)) {
                lost.add(id);
            }
        }
        java.util.Collections.sort(gained);
        java.util.Collections.sort(lost);
        if (gained.remove("CANSTAY")) {
            lines.add("You may now enter this claim.");
        }
        if (lost.remove("CANSTAY")) {
            lines.add("You no longer have permission to enter.");
        }
        if (!gained.isEmpty()) {
            lines.add("Now allowed: " + join(gained));
        }
        if (!lost.isEmpty()) {
            lines.add("No longer allowed: " + join(lost));
        }
        return lines;
    }

    private static String join(java.util.List<String> ids) {
        int max = 8;
        String text = String.join(", ", ids.subList(0, Math.min(max, ids.size())));
        return ids.size() > max ? text + " and " + (ids.size() - max) + " more" : text;
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
