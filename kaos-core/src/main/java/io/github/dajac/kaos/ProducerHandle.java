package io.github.dajac.kaos;

import java.util.List;

/**
 * A load generator of a simulator. It writes records to every partition of its topics at a fixed
 * rate. Every method is thread-safe.
 */
public interface ProducerHandle {
    /** The producer id. */
    String id();

    /** The names of the topics it writes to. */
    List<String> topics();

    /** The records written per second to each partition. */
    double ratePerPartition();

    /** Whether the producer runs, is paused or is stopped. */
    ProducerState state();

    /**
     * Stops writing until {@link #resume()}. The call returns immediately; the producer finishes
     * the round of records it is writing, if any. A producer paused before the simulator starts
     * starts paused.
     */
    void pause();

    /** Writes again after a {@link #pause()}. The call returns immediately. */
    void resume();
}
