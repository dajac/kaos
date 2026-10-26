/*
 * Copyright 2026 The Kaos Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dajac.kaos;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A consumer group of a simulator: a group id, group settings, subscribed topics and consumers. Every
 * method is thread-safe.
 *
 * <p>Once the group is removed, with {@link #remove()} or by the stop of the simulator, the methods
 * that act on it throw a {@link SimulationStateException}; the accessors keep answering.
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

    /**
     * Adds a consumer to the group. It gets the id {@code consumer-N} unless the options set one,
     * and inherits the {@code consumerDefaults} of the group. Before the simulator starts, the
     * consumer is declared and started with the group; while the simulator runs, the call blocks
     * until the consumer runs, and the consumer then joins the group on its own.
     *
     * @throws InvalidConfigException if the consumer is invalid, or its id or client id is taken
     * @throws SimulationStateException if the group is removed
     * @throws SimulationException if the consumer cannot be started; the group is then unchanged
     */
    ConsumerHandle addConsumer(Consumer<ConsumerOptions> options);

    /** Adds a consumer from its spec. See {@link #addConsumer(Consumer)}. */
    ConsumerHandle addConsumer(ConsumerSpec spec);

    /**
     * Removes the group from the simulator. While the simulator runs, the call blocks: it stops the
     * consumers, which leave the group, removes the static members that could not, deletes the group
     * and reverts its group configs. The handles of its consumers are removed with it.
     *
     * @throws SimulationStateException if the group is already removed
     * @throws SimulationException if something could not be undone, after everything else was
     */
    void remove();

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
