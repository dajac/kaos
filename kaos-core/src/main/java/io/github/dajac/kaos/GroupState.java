package io.github.dajac.kaos;

/** The state of a consumer group, as the group coordinator reports it. */
public enum GroupState {
    /** The coordinator has not reported the group yet. */
    UNKNOWN,
    /** The group has no members. */
    EMPTY,
    /** A consumer group computes a new target assignment. */
    ASSIGNING,
    /** A consumer group has members that have not reached the target assignment yet. */
    RECONCILING,
    /** Every member has its assignment. */
    STABLE,
    /** A classic group waits for its members to rejoin. */
    PREPARING_REBALANCE,
    /** A classic group waits for the assignment of its leader. */
    COMPLETING_REBALANCE,
    /** The group is being removed. */
    DEAD
}
