package io.github.dajac.kaos.server.dto;

import java.util.List;
import java.util.Objects;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.ProducerSpec;

/**
 * A producer in a request body. A missing {@code ratePerPartition} takes the default of
 * {@link ConfigMetadata#RATE_PER_PARTITION}.
 *
 * @param id the producer id, or null or empty to have one assigned
 * @param topics the topics the producer writes to
 * @param ratePerPartition the records per second and partition, or null for the default
 */
public record ProducerRequest(String id, List<String> topics, Double ratePerPartition) {

    /** The spec this request describes. */
    public ProducerSpec toSpec() {
        return new ProducerSpec(
            id, topics, Objects.requireNonNullElse(ratePerPartition, ConfigMetadata.RATE_PER_PARTITION.doubleDefault())
        );
    }
}
