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
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
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
     * the call returns, so its deletion is the undo of this step; {@link #awaitCreated} is the
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
                    "The topic '" + topic.name() + "' already exists; the simulator only creates topics it owns: "
                        + "delete it or use another topic name",
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
     * Waits until the cluster reports a topic that was created. The controller acknowledges the
     * creation before the brokers that serve the metadata requests know the topic.
     *
     * @throws SimulationException if the topic is still not reported at the timeout
     */
    public void awaitCreated(String name) {
        try {
            awaitExistence(name, true, System.nanoTime() + timeout.toNanos());
        } catch (TimeoutException e) {
            throw new SimulationException("The topic '" + name + "' is still not reported after " + timeout, e);
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
        var remaining = Duration.ofNanos(deadline - System.nanoTime());
        if (remaining.isNegative() || remaining.isZero()) {
            throw new TimeoutException();
        }
        try {
            get(admin.describeTopics(List.of(name)).allTopicNames(), remaining);
            return true;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return false;
            }
            throw new SimulationException("Could not describe the topic '" + name + "': " + e.getCause(), e.getCause());
        }
    }

    private static <T> T get(KafkaFuture<T> future, Duration bound)
        throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(bound.toNanos(), TimeUnit.NANOSECONDS);
    }
}
