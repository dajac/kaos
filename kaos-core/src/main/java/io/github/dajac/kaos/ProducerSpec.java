package io.github.dajac.kaos;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The description of a load generator. A {@code null} id or list is read as empty, so that a request
 * body can leave it out.
 *
 * @param id the producer id; empty when the simulator has not assigned it yet
 * @param topics the names of the topics it writes to
 * @param ratePerPartition the records written per second to each partition
 */
public record ProducerSpec(String id, List<String> topics, double ratePerPartition) {
    public ProducerSpec {
        id = Objects.requireNonNullElse(id, "");
        topics = topics == null ? List.of() : List.copyOf(topics);
    }

    /** A producer described by an options lambda. */
    public static ProducerSpec of(Consumer<ProducerOptions> options) {
        return ProducerOptions.spec(options);
    }
}
