package io.github.dajac.kaos.server.dto;

import java.util.Objects;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.TopicSpec;

/**
 * A topic in a request body. A missing {@code partitions} takes the default of
 * {@link ConfigMetadata#PARTITIONS}.
 *
 * @param name the topic name
 * @param partitions the number of partitions, or null for the default
 */
public record TopicRequest(String name, Integer partitions) {

    /** The spec this request describes. */
    public TopicSpec toSpec() {
        return new TopicSpec(name, Objects.requireNonNullElse(partitions, ConfigMetadata.PARTITIONS.intDefault()));
    }
}
