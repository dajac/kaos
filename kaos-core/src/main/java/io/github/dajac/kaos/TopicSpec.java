package io.github.dajac.kaos;

import java.util.Objects;

/**
 * The description of a topic created and owned by the simulator.
 *
 * @param name the topic name
 * @param partitions the number of partitions
 */
public record TopicSpec(String name, int partitions) {
    public TopicSpec {
        Objects.requireNonNull(name, "name");
    }

    /** A topic with the default number of partitions. */
    public static TopicSpec of(String name) {
        return new TopicSpec(name, ConfigMetadata.PARTITIONS.intDefault());
    }
}
