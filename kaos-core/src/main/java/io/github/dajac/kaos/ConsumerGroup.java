package io.github.dajac.kaos;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

/**
 * A consumer group of a simulator: a group id, group settings, subscribed topics, consumers and an
 * autopilot. Every method is thread-safe.
 */
public interface ConsumerGroup {
    /** The group id. */
    String id();

    /** The names of the topics the consumers subscribe to. */
    List<String> subscribedTopics();

    /** The description of the group, with the ids assigned to its consumers. */
    GroupSpec describe();

    /** The consumers, in the order they were added. */
    List<ConsumerHandle> consumers();

    /**
     * The consumer with the assigned id {@code consumer-<number>}. It is the same as
     * {@code consumer("consumer-" + number)}, so the number of a consumer never changes.
     *
     * @throws IllegalArgumentException if the group has no such consumer
     */
    ConsumerHandle consumer(int number);

    /**
     * The consumer with the given id.
     *
     * @throws IllegalArgumentException if the group has no such consumer
     */
    ConsumerHandle consumer(String id);

    /** The autopilot of the group. */
    Autopilot autopilot();

    /**
     * What the start of the group could not apply and went on without, such as a group config that
     * the broker refuses. It is empty while the group does not run.
     */
    List<String> warnings();

    /** The slice of the latest snapshot of the simulator that shows this group. */
    GroupSnapshot snapshot();

    /**
     * Waits until a snapshot of this group matches the condition, starting with the current one,
     * and returns it. The call blocks, and throws a {@link SimulationStateException} when a
     * {@link SnapshotListener} calls it.
     *
     * @throws SimulationException if no snapshot matches within the timeout
     */
    GroupSnapshot awaitSnapshot(Predicate<GroupSnapshot> condition, Duration timeout);
}
