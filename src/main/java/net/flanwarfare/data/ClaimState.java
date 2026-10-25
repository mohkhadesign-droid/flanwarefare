package net.flanwarfare.data;

/** Activation state of an ordinary player claim, as managed by this add-on. */
public enum ClaimState {
    /** Created but no flag placed yet: no protection against players, no management. */
    PENDING_FLAG,
    /** Flag placed and registered: normal Flan behaviour plus raid rules. */
    ACTIVE,
    /**
     * A previously placed flag could not be restored automatically, or claim data disagree.
     * Behaves like ACTIVE for protection, raids and capture (destroying a flag never grants
     * immunity), but management is locked until an administrator repairs it.
     */
    RECOVERY_REQUIRED
}
