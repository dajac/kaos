package io.github.dajac.kaos;

import java.util.Comparator;
import java.util.Objects;

/**
 * A partition of a topic. The natural order is by topic name, then by partition number.
 *
 * @param topic the topic name
 * @param partition the partition number
 */
public record PartitionRef(String topic, int partition) implements Comparable<PartitionRef> {
    private static final Comparator<PartitionRef> ORDER =
        Comparator.comparing(PartitionRef::topic).thenComparingInt(PartitionRef::partition);

    public PartitionRef {
        Objects.requireNonNull(topic, "topic");
    }

    @Override
    public int compareTo(PartitionRef other) {
        return ORDER.compare(this, other);
    }

    /** The {@code topic-partition} form used by Kafka. */
    @Override
    public String toString() {
        return topic + "-" + partition;
    }
}
