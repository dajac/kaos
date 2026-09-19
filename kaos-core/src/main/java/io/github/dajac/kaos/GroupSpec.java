package io.github.dajac.kaos;

import io.github.dajac.kaos.internal.config.EffectiveConfigResolver;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The description of a consumer group.
 *
 * <p>The {@code effective} settings of each consumer are recomputed from {@code consumerDefaults}
 * and the consumer's {@code overrides}, so they are always consistent with the group.
 *
 * @param id the group id
 * @param subscribedTopics the names of the topics the consumers subscribe to
 * @param sessionTimeoutMs the group config {@code consumer.session.timeout.ms}
 * @param heartbeatIntervalMs the group config {@code consumer.heartbeat.interval.ms}
 * @param assignmentIntervalMs the group config {@code consumer.assignment.interval.ms}
 * @param consumerDefaults the settings the consumers inherit
 * @param consumers the consumers
 */
public record GroupSpec(
    String id,
    List<String> subscribedTopics,
    int sessionTimeoutMs,
    int heartbeatIntervalMs,
    int assignmentIntervalMs,
    ConsumerSettings consumerDefaults,
    List<ConsumerSpec> consumers
) {
    public GroupSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(consumerDefaults, "consumerDefaults");
        subscribedTopics = List.copyOf(subscribedTopics);
        consumers = EffectiveConfigResolver.resolve(consumerDefaults, consumers);
    }

    /** A group described by an options lambda. */
    public static GroupSpec of(String id, Consumer<GroupOptions> options) {
        return GroupOptions.spec(id, options);
    }
}
