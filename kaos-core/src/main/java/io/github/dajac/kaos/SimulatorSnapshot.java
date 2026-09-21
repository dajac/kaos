package io.github.dajac.kaos;

import java.util.List;
import java.util.Objects;

/**
 * The live view of a whole simulator at one point in time. It is immutable.
 *
 * @param revision a number that grows every time the content of the snapshot changes
 * @param phase the lifecycle phase of the simulator
 * @param stale whether the coordinator-side data is older than the last poll, because that poll
 *     failed
 * @param topics the topics of the simulator
 * @param producers the load generators
 * @param groups the consumer groups
 */
public record SimulatorSnapshot(
    long revision,
    Phase phase,
    boolean stale,
    List<TopicSpec> topics,
    List<ProducerSnapshot> producers,
    List<GroupSnapshot> groups
) {
    public SimulatorSnapshot {
        Objects.requireNonNull(phase, "phase");
        topics = List.copyOf(topics);
        producers = List.copyOf(producers);
        groups = List.copyOf(groups);
    }

    /** The same snapshot with another revision. */
    public SimulatorSnapshot withRevision(long newRevision) {
        return new SimulatorSnapshot(newRevision, phase, stale, topics, producers, groups);
    }
}
