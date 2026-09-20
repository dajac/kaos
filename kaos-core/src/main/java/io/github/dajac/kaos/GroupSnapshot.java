package io.github.dajac.kaos;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The live view of one consumer group. It is immutable. An epoch or a generation that is not known
 * is {@code -1}.
 *
 * @param id the group id
 * @param type the group type reported by the coordinator
 * @param state the group state reported by the coordinator
 * @param epoch the group epoch of a consumer group
 * @param targetAssignmentEpoch the epoch of the target assignment of a consumer group
 * @param generation the highest generation reported by the consumers of the group; the classic
 *     protocol counterpart of the epoch
 * @param subscribedTopics the names of the topics the group subscribes to
 * @param autopilot whether the autopilot of the group is on
 * @param lag the lag of each subscribed topic, in the order of {@code subscribedTopics}; the list
 *     of a topic has one entry per partition, indexed by partition number, and an unknown lag is 0
 * @param consumers the consumers of the group
 */
public record GroupSnapshot(
    String id,
    GroupType type,
    GroupState state,
    int epoch,
    int targetAssignmentEpoch,
    int generation,
    List<String> subscribedTopics,
    boolean autopilot,
    Map<String, List<Long>> lag,
    List<ConsumerSnapshot> consumers
) {
    public GroupSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        subscribedTopics = List.copyOf(subscribedTopics);
        lag = copyInOrder(lag);
        consumers = List.copyOf(consumers);
    }

    private static Map<String, List<Long>> copyInOrder(Map<String, List<Long>> lag) {
        var copy = new LinkedHashMap<String, List<Long>>();
        lag.forEach((topic, values) -> copy.put(Objects.requireNonNull(topic, "topic"), List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }
}
