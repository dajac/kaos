package io.github.dajac.kaos;

/** The state of a partition from the point of view of one consumer. */
public enum PartitionState {
    /** The consumer owns the partition and the coordinator wants it to. */
    ASSIGNED,
    /** The coordinator wants the consumer to own the partition and the consumer does not yet. */
    PENDING,
    /** The consumer still owns the partition and the coordinator wants it to give it up. */
    REVOKING,
    /** The partition has nothing to do with the consumer. */
    EMPTY
}
