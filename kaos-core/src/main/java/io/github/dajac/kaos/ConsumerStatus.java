package io.github.dajac.kaos;

/**
 * The state of a simulated consumer. {@link ConsumerSnapshot#status()} holds the single state a
 * frontend shows for the consumer; when several apply, the first one in the order of this enum
 * wins. {@link ConsumerSnapshot#coordinatorState()} uses the membership subset: {@link #STOPPED},
 * {@link #STOPPING}, {@link #JOINING}, {@link #RECONCILING} and {@link #STABLE}.
 */
public enum ConsumerStatus {
    /** The consumer hit an unrecoverable error and no longer runs. */
    FAILED,
    /** The consumer was asked to leave the group, or has left it. */
    STOPPED,
    /** The consumer does not poll: {@link Fault#freeze()} is active. */
    FROZEN,
    /**
     * The consumer is cut off from the cluster: {@link Fault#networkIsolation()} or
     * {@link Fault#connectionReset()} is active.
     */
    ISOLATED,
    /** The consumer was asked to leave the group and the coordinator still lists it as a member. */
    STOPPING,
    /** The consumer wants to be in the group and the coordinator does not list it as a member yet. */
    JOINING,
    /** The consumer is a member and has not reached its target assignment yet. */
    RECONCILING,
    /** The consumer is a member and owns exactly its target assignment. */
    STABLE
}
