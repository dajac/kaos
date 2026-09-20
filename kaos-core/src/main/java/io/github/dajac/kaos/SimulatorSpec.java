package io.github.dajac.kaos;

import java.util.List;

/**
 * The immutable description of a whole simulation. A {@code null} list is read as empty, so that a
 * request body can leave it out.
 *
 * @param topics the topics created and owned by the simulator
 * @param producers the load generators
 * @param groups the consumer groups
 */
public record SimulatorSpec(List<TopicSpec> topics, List<ProducerSpec> producers, List<GroupSpec> groups) {
    public SimulatorSpec {
        topics = topics == null ? List.of() : List.copyOf(topics);
        producers = producers == null ? List.of() : List.copyOf(producers);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }
}
