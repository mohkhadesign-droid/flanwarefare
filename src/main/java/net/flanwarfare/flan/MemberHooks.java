package net.flanwarfare.flan;

import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.claims.MemberNotices;
import net.flanwarfare.core.WarCore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entry points called by {@code ClaimMixin} around Flan's membership mutators. They only
 * observe; they never change what Flan does. "after" hooks act only when Flan reports success,
 * so only committed changes reach {@link MemberNotices}.
 */
public final class MemberHooks {
    /** Members of the group being edited, captured before the call (stack for safety). */
    private static final ThreadLocal<Deque<List<UUID>>> GROUP_MEMBERS = ThreadLocal.withInitial(ArrayDeque::new);

    private MemberHooks() {
    }

    private static MemberNotices notices() {
        WarCore core = WarCore.ready();
        return core == null || Guards.bypassed() ? null : core.members();
    }

    public static void beforeSetGroup(Claim claim, UUID player) {
        MemberNotices notices = notices();
        if (notices != null && player != null) {
            notices.before(claim, player);
        }
    }

    public static void afterSetGroup(Claim claim, UUID player, boolean success) {
        MemberNotices notices = notices();
        if (notices != null && player != null && success) {
            notices.committed(claim, player, false);
        }
    }

    /** Before removePermGroup / editPerms on a group: remember that group's members. */
    public static void beforeGroupEdit(Claim claim, String group) {
        Deque<List<UUID>> stack = GROUP_MEMBERS.get();
        if (stack.size() > 16) {
            stack.clear(); // an exception skipped matching "after" calls; never grow unbounded
        }
        List<UUID> members = new ArrayList<>();
        MemberNotices notices = notices();
        if (notices != null && group != null) {
            for (Map.Entry<UUID, String> entry : FlanBridge.members(claim).entrySet()) {
                if (group.equals(entry.getValue())) {
                    members.add(entry.getKey());
                    notices.before(claim, entry.getKey());
                }
            }
        }
        stack.push(members);
    }

    /**
     * After the group call. {@code permissionsEdited}: the group's permissions changed
     * (editPerms) rather than the group being deleted with its members (removePermGroup).
     */
    public static void afterGroupEdit(Claim claim, boolean success, boolean permissionsEdited) {
        Deque<List<UUID>> stack = GROUP_MEMBERS.get();
        List<UUID> members = stack.isEmpty() ? new ArrayList<UUID>() : stack.pop();
        MemberNotices notices = notices();
        if (notices == null || !success) {
            return;
        }
        for (UUID id : members) {
            notices.committed(claim, id, permissionsEdited);
        }
    }
}
