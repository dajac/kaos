package io.github.dajac.kaos.internal.kafka;

import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.TopicSpec;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

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

    private static final Duration DELETION_POLL_INTERVAL = Duration.ofMillis(100);

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
     * Creates a topic with the default replication factor of the cluster.
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
                    "The topic '" + topic.name() + "' already exists; the simulator only creates topics it owns", e.getCause()
                );
            }
            throw new SimulationException("Could not create the topic '" + topic.name() + "': " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new SimulationException("Timed out creating the topic '" + topic.name() + "' after " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulationException("Interrupted while creating the topic '" + topic.name() + "'", e);
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
                    throw new SimulationException("Could not delete the topic '" + name + "': " + e.getCause(), e.getCause());
                }
            }
            while (exists(name, deadline)) {
                LockSupport.parkNanos(this, DELETION_POLL_INTERVAL.toNanos());
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
        } catch (TimeoutException e) {
            throw new SimulationException("Timed out deleting the topic '" + name + "' after " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulationException("Interrupted while deleting the topic '" + name + "'", e);
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
            throw new SimulationException("Could not check the deletion of the topic '" + name + "': " + e.getCause(), e.getCause());
        }
    }

    private static <T> T get(KafkaFuture<T> future, Duration bound)
        throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(bound.toNanos(), TimeUnit.NANOSECONDS);
    }
}
