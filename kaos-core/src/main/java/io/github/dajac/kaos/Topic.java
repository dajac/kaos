package io.github.dajac.kaos;

/**
 * A topic created and owned by a simulator. The simulator creates it when it starts, or at once when
 * it is added to a running simulator, and deletes it when it stops or when the topic is removed.
 */
public interface Topic {
    /** The topic name. */
    String name();

    /** The number of partitions. */
    int partitions();

    /**
     * Removes the topic from the simulator. While the simulator runs, the call blocks until the
     * topic is deleted from the cluster.
     *
     * @throws SimulationStateException if a producer or a group still uses the topic, or if the
     *     topic is already removed
     * @throws SimulationException if the topic cannot be deleted
     */
    void remove();
}
