package io.github.dajac.kaos;

import java.util.Objects;

/**
 * A partition as one consumer sees it.
 *
 * @param topic the topic name
 * @param partition the partition number
 * @param state the state of the partition for this consumer
 */
public record PartitionView(String topic, int partition, PartitionState state) {
    public PartitionView {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(state, "state");
    }
}
