package io.github.dajac.kaos;

import java.util.List;

/**
 * The immutable description of a whole simulation.
 *
 * @param topics the topics created and owned by the simulator
 * @param producers the load generators
 * @param groups the consumer groups
 */
public record SimulatorSpec(List<TopicSpec> topics, List<ProducerSpec> producers, List<GroupSpec> groups) {
    public SimulatorSpec {
        topics = List.copyOf(topics);
        producers = List.copyOf(producers);
        groups = List.copyOf(groups);
    }
}
