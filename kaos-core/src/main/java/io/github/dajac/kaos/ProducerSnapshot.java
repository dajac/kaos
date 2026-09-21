package io.github.dajac.kaos;

import java.util.List;
import java.util.Objects;

/**
 * The live view of a load generator. It is immutable.
 *
 * @param id the producer id
 * @param topics the names of the topics it writes to
 * @param state whether it runs, is paused or is stopped
 * @param ratePerPartition the records written per second to each partition
 */
public record ProducerSnapshot(String id, List<String> topics, ProducerState state, double ratePerPartition) {
    public ProducerSnapshot {
        Objects.requireNonNull(id, "id");
        topics = List.copyOf(topics);
        Objects.requireNonNull(state, "state");
    }
}
