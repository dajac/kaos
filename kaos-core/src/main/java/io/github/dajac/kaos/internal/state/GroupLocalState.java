package io.github.dajac.kaos.internal.state;

import io.github.dajac.kaos.TopicSpec;

import java.util.List;
import java.util.Objects;

/**
 * An immutable copy of what the simulator itself knows about one group.
 *
 * @param id the group id
 * @param subscribedTopics the topics the group subscribes to, with their partition counts
 * @param autopilot whether the autopilot of the group is on
 * @param consumers the local state of the consumers, in the order of the group
 */
public record GroupLocalState(
    String id,
    List<TopicSpec> subscribedTopics,
    boolean autopilot,
    List<ConsumerLocalState> consumers
) {
    public GroupLocalState {
        Objects.requireNonNull(id, "id");
        subscribedTopics = List.copyOf(subscribedTopics);
        consumers = List.copyOf(consumers);
    }
}
