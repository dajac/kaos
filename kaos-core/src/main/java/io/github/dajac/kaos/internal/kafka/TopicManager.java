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
package io.github.dajac.kaos.internal.kafka;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.TopicSpec;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

/**
 * Creates and deletes the topics a simulator owns. Every call blocks, for at most the timeout, and
 * is interruptible.
 */
public final class TopicManager {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final Admin admin;
    private final Duration timeout;

    /**
     * @param admin the admin client; it stays owned by the caller
     * @param timeout the bound on each call
     */
    public TopicManager(Admin admin, Duration timeout) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Creates a topic with the default replication factor of the cluster. The topic exists when
     * the call returns, so its deletion is the undo of this step; {@link #awaitReady} is the
     * step that follows.
     *
     * @throws SimulationException if the topic cannot be created. A topic that already exists is
     *     refused: the simulator deletes the topics it owns, so it never adopts one.
     */
    public void create(TopicSpec topic) {
        var newTopic = new NewTopic(topic.name(), Optional.of(topic.partitions()), Optional.empty());
        try {
            get(admin.createTopics(List.of(newTopic)).all(), timeout);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                throw new SimulationException(
                    "The topic '" + topic.name() + "' already exists; the simulator only creates topics it owns",
                    e.getCause()
                );
            }
            throw new SimulationException("Could not create the topic '" + topic.name() + "': " + e.getCause(),
                e.getCause());
        } catch (TimeoutException e) {
            throw new SimulationException("Timed out creating the topic '" + topic.name() + "' after " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulationException("Interrupted while creating the topic '" + topic.name() + "'", e);
        }
    }

    /**
     * Waits until a topic that was created is ready for the producers: the cluster reports it and
     * the leader of every partition serves it.
     *
     * <p>The controller acknowledges the creation before the brokers that serve the metadata
     * requests know the topic, and a broker lists a partition in its metadata before it hosts the
     * log of the partition: a produce request sent in between gets {@code NOT_LEADER_OR_FOLLOWER}.
     * When the first batch of an idempotent producer fails that way while a later batch of the same
     * partition is accepted, the producer retries the first one with
     * {@code OUT_OF_ORDER_SEQUENCE_NUMBER} until its delivery timeout, and the partition gets
     * little or no load. The topic is ready once every partition answers a {@code ListOffsets}
     * request, which a leader serves only from a log it hosts.
     *
     * @throws SimulationException if the topic is still not ready at the timeout
     */
    public void awaitReady(String name) {
        var deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!isReady(name, deadline)) {
                LockSupport.parkNanos(this, POLL_INTERVAL.toNanos());
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
        } catch (TimeoutException e) {
            throw new SimulationException("The topic '" + name + "' is still not ready after " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulationException("Interrupted while waiting for the topic '" + name + "'", e);
        }
    }

    /**
     * Deletes a topic and waits until the cluster no longer reports it, so that a topic of the
     * same name can be created right after. A topic that does not exist is not an error.
     *
     * @throws SimulationException if the topic cannot be deleted or is still there at the timeout
     */
    public void delete(String name) {
        var deadline = System.nanoTime() + timeout.toNanos();
        try {
            try {
                get(admin.deleteTopics(List.of(name)).all(), timeout);
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof UnknownTopicOrPartitionException)) {
                    throw new SimulationException("Could not delete the topic '" + name + "': " + e.getCause(),
                        e.getCause());
                }
            }
            awaitExistence(name, false, deadline);
        } catch (TimeoutException e) {
            throw new SimulationException("Timed out deleting the topic '" + name + "' after " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulationException("Interrupted while deleting the topic '" + name + "'", e);
        }
    }

    /**
     * Whether the cluster reports the topic, every partition has a leader and every leader serves
     * the latest offset of its partition.
     */
    private boolean isReady(String name, long deadline) throws InterruptedException, TimeoutException {
        TopicDescription description;
        try {
            description = get(admin.describeTopics(List.of(name)).allTopicNames(), remaining(deadline)).get(name);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return false;
            }
            throw new SimulationException("Could not describe the topic '" + name + "': " + e.getCause(), e.getCause());
        }
        var latest = new HashMap<TopicPartition, OffsetSpec>();
        for (var partition : description.partitions()) {
            if (partition.leader() == null || partition.leader().isEmpty()) {
                return false;
            }
            latest.put(new TopicPartition(name, partition.partition()), OffsetSpec.latest());
        }
        var remaining = remaining(deadline);
        var options = new ListOffsetsOptions().timeoutMs((int) Math.min(remaining.toMillis(), Integer.MAX_VALUE));
        try {
            get(admin.listOffsets(latest, options).all(), remaining);
            return true;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RetriableException) {
                return false;
            }
            throw new SimulationException("Could not list the offsets of the topic '" + name + "': " + e.getCause(),
                e.getCause());
        }
    }

    /** Polls the cluster until it reports the topic, or no longer does. */
    private void awaitExistence(String name, boolean expected, long deadline)
        throws InterruptedException, TimeoutException {
        while (exists(name, deadline) != expected) {
            LockSupport.parkNanos(this, POLL_INTERVAL.toNanos());
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    private boolean exists(String name, long deadline) throws InterruptedException, TimeoutException {
        try {
            get(admin.describeTopics(List.of(name)).allTopicNames(), remaining(deadline));
            return true;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return false;
            }
            throw new SimulationException("Could not describe the topic '" + name + "': " + e.getCause(), e.getCause());
        }
    }

    /** The time left until the deadline. */
    private static Duration remaining(long deadline) throws TimeoutException {
        var remaining = Duration.ofNanos(deadline - System.nanoTime());
        if (remaining.isNegative() || remaining.isZero()) {
            throw new TimeoutException();
        }
        return remaining;
    }

    private static <T> T get(KafkaFuture<T> future, Duration bound)
        throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(bound.toNanos(), TimeUnit.NANOSECONDS);
    }
}
