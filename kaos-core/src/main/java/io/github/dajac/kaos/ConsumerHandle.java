package io.github.dajac.kaos;

import java.util.Set;

/**
 * A simulated consumer of a group. Every method is thread-safe.
 *
 * <p>{@link #inject}, {@link #clear}, {@link #clearFaults}, {@link #join} and {@link #leave}
 * return immediately: the consumer reacts on its own thread, and the snapshot shows the outcome.
 * They can be called before the simulator starts; the consumer then starts in that state.
 */
public interface ConsumerHandle {
    /** The consumer id, unique within the group. */
    String id();

    /** The {@code client.id} of the consumer, {@code <groupId>-<id>}, unique in the simulator. */
    String clientId();

    /** The group protocol the consumer speaks at the moment. */
    Protocol protocol();

    /** Whether the consumer is asked to be in the group. */
    boolean subscribed();

    /** Injects a fault. Injecting a fault that is active changes nothing. */
    void inject(Fault fault);

    /** Clears a fault. Clearing a fault that is not active changes nothing. */
    void clear(Fault fault);

    /** Clears every fault. */
    void clearFaults();

    /** The active faults, in the order of {@link Fault#all()}. */
    Set<Fault> faults();

    /** Asks the consumer to subscribe to the topics of the group, and so to join the group. */
    void join();

    /** Asks the consumer to unsubscribe, and so to leave the group. */
    void leave();

    /**
     * Makes the consumer leave the group and come back with the other group protocol. The call
     * blocks until the new Kafka consumer runs, and throws a {@link SimulationStateException} when
     * a {@link SnapshotListener} calls it. The subscription and the faults are kept.
     *
     * <p>The assignor of the consumer applies to the protocol it was set for. With the other
     * protocol the consumer keeps it only when that protocol lists the same name, such as
     * {@code range}, and uses the default of that protocol otherwise.
     *
     * @throws SimulationException if the new Kafka consumer cannot be created; the consumer is then
     *     {@link ConsumerStatus#FAILED}
     */
    void switchProtocol(Protocol protocol);
}
