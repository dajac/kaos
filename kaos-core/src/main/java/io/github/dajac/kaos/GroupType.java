package io.github.dajac.kaos;

/** The type of a consumer group, as the group coordinator reports it. */
public enum GroupType {
    /** The coordinator has not reported the group yet, or reports a type a simulated group never has. */
    UNKNOWN,
    /** A group of the consumer rebalance protocol of KIP-848. It can hold classic members too. */
    CONSUMER,
    /** A group of the classic rebalance protocol. */
    CLASSIC
}
