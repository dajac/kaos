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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.dajac.kaos.GroupState;
import io.github.dajac.kaos.GroupType;
import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.TopicSpec;
import io.github.dajac.kaos.internal.state.AdminView;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.MemberAssignment;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the cluster for all the groups of a simulator from one thread. A poll is one describe call
 * for all the groups, one call for their committed offsets and one for the end offsets of their
 * topics. It produces an {@link AdminView} per group.
 *
 * <p>A poll that fails keeps the last views and marks them {@link #stale()}; the next poll that
 * succeeds clears the mark. The views and the mark are read from any thread.
 */
public final class GroupPoller {

    /**
     * A group to poll.
     *
     * @param groupId the group id
     * @param topics the topics the group subscribes to, whose lag is computed
     */
    public record Target(String groupId, List<TopicSpec> topics) {
        public Target {
            Objects.requireNonNull(groupId, "groupId");
            topics = List.copyOf(topics);
        }
    }

    /** The default time between two polls. */
    public static final Duration INTERVAL = Duration.ofMillis(200);

    private static final Logger LOG = LoggerFactory.getLogger(GroupPoller.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final Admin admin;
    private final Supplier<List<Target>> targets;
    private final Runnable onChange;
    private final Duration interval;
    private final Duration timeout;

    private volatile Map<String, AdminView> views = Map.of();
    private volatile boolean stale;
    private volatile boolean running;
    private volatile Thread thread;

    /**
     * @param admin the admin client; the poller does not close it
     * @param targets the groups to poll, read at every poll
     * @param onChange called after a poll changed the views or the stale mark
     * @param interval the time between two polls
     * @param timeout the bound of each admin call
     */
    public GroupPoller(
        Admin admin,
        Supplier<List<Target>> targets,
        Runnable onChange,
        Duration interval,
        Duration timeout
    ) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.onChange = Objects.requireNonNull(onChange, "onChange");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** The last view of a group; {@link AdminView#unknown()} while there is none. */
    public AdminView view(String groupId) {
        return views.getOrDefault(groupId, AdminView.unknown());
    }

    /** Whether the last poll failed, so that the views are older than they should be. */
    public boolean stale() {
        return stale;
    }

    /** Starts the poller thread, created by {@code threads}. The owner serializes start and stop. */
    public void start(ThreadFactory threads) {
        running = true;
        var started = threads.newThread(this::run);
        thread = started;
        started.start();
    }

    /** Stops the poller thread and waits for it. Idempotent. */
    public void stop() throws InterruptedException {
        var stopped = thread;
        if (stopped == null) {
            return;
        }
        running = false;
        LockSupport.unpark(stopped);
        if (!stopped.join(STOP_TIMEOUT)) {
            // The thread waits for the cluster; the waits of the admin futures are interruptible.
            stopped.interrupt();
            if (!stopped.join(STOP_TIMEOUT)) {
                throw new SimulationException(
                    "The thread " + stopped.getName() + " did not stop within " + STOP_TIMEOUT
                );
            }
        }
        thread = null;
    }

    private void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            pollOnce();
            var deadline = System.nanoTime() + interval.toNanos();
            var remaining = interval.toNanos();
            while (running && remaining > 0 && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(this, remaining);
                remaining = deadline - System.nanoTime();
            }
        }
    }

    /** Runs one poll and records its outcome. */
    void pollOnce() {
        var polled = targets.get();
        try {
            var fresh = polled.isEmpty() ? Map.<String, AdminView>of() : poll(polled);
            var changed = stale || !fresh.equals(views);
            views = fresh;
            stale = false;
            if (changed) {
                onChange.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            if (!running) {
                return;
            }
            var cause = e instanceof ExecutionException ? e.getCause() : e;
            LOG.warn("Could not poll the groups, keeping the last view: {}", String.valueOf(cause));
            if (!stale) {
                stale = true;
                onChange.run();
            }
        }
    }

    private Map<String, AdminView> poll(List<Target> polled)
        throws InterruptedException, ExecutionException, TimeoutException {
        var groupIds = polled.stream().map(Target::groupId).toList();
        var descriptions = admin.describeConsumerGroups(groupIds).describedGroups();
        var offsetSpecs = groupIds.stream()
            .collect(Collectors.toMap(id -> id, id -> new ListConsumerGroupOffsetsSpec()));
        var committed = admin.listConsumerGroupOffsets(offsetSpecs);
        var partitions = new HashMap<TopicPartition, OffsetSpec>();
        for (var target : polled) {
            for (var topic : target.topics()) {
                for (var partition = 0; partition < topic.partitions(); partition++) {
                    partitions.put(new TopicPartition(topic.name(), partition), OffsetSpec.latest());
                }
            }
        }
        var endOffsets = new HashMap<TopicPartition, Long>();
        await(admin.listOffsets(partitions).all())
            .forEach((partition, info) -> endOffsets.put(partition, info.offset()));

        var fresh = new HashMap<String, AdminView>();
        for (var target : polled) {
            var groupId = target.groupId();
            Optional<ConsumerGroupDescription> description;
            try {
                description = Optional.of(await(descriptions.get(groupId)));
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof GroupIdNotFoundException)) {
                    throw e;
                }
                description = Optional.empty();
            }
            var lag = lag(target.topics(), await(committed.partitionsToOffsetAndMetadata(groupId)), endOffsets);
            fresh.put(groupId, toAdminView(description, lag));
        }
        return Map.copyOf(fresh);
    }

    private <T> T await(KafkaFuture<T> future) throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * The lag of the partitions of the topics: the end offset minus the committed offset, never
     * negative. A partition without a committed offset or without an end offset has no entry: its
     * lag is not known.
     */
    static Map<PartitionRef, Long> lag(
        List<TopicSpec> topics,
        Map<TopicPartition, OffsetAndMetadata> committed,
        Map<TopicPartition, Long> endOffsets
    ) {
        var lag = new HashMap<PartitionRef, Long>();
        for (var topic : topics) {
            for (var partition = 0; partition < topic.partitions(); partition++) {
                var key = new TopicPartition(topic.name(), partition);
                var offset = committed.get(key);
                var end = endOffsets.get(key);
                if (offset != null && end != null) {
                    lag.put(new PartitionRef(topic.name(), partition), Math.max(0, end - offset.offset()));
                }
            }
        }
        return lag;
    }

    /** Translates a group description. A group that the coordinator does not know is unknown. */
    static AdminView toAdminView(Optional<ConsumerGroupDescription> description, Map<PartitionRef, Long> lag) {
        if (description.isEmpty()) {
            var unknown = AdminView.unknown();
            return new AdminView(
                unknown.type(),
                unknown.state(),
                unknown.epoch(),
                unknown.targetAssignmentEpoch(),
                unknown.members(),
                lag
            );
        }
        var group = description.get();
        var members = new ArrayList<AdminView.Member>();
        for (var member : group.members()) {
            members.add(toMember(member));
        }
        return new AdminView(
            enumOf(GroupType.class, group.type().name(), GroupType.UNKNOWN),
            enumOf(GroupState.class, group.groupState().name(), GroupState.UNKNOWN),
            group.groupEpoch().orElse(-1),
            group.targetAssignmentEpoch().orElse(-1),
            members,
            lag
        );
    }

    private static AdminView.Member toMember(MemberDescription member) {
        return new AdminView.Member(
            member.clientId(),
            member.consumerId(),
            member.groupInstanceId().orElse(""),
            member.memberEpoch().orElse(-1),
            refs(member.assignment()),
            member.targetAssignment().map(GroupPoller::refs)
        );
    }

    private static Set<PartitionRef> refs(MemberAssignment assignment) {
        return assignment.topicPartitions().stream()
            .map(partition -> new PartitionRef(partition.topic(), partition.partition()))
            .collect(Collectors.toSet());
    }

    /** The constant with the same name, or the fallback for a name this version does not have. */
    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
        for (var constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        return fallback;
    }
}
