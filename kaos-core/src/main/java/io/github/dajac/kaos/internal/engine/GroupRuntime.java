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
package io.github.dajac.kaos.internal.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.github.dajac.kaos.ConsumerGroup;
import io.github.dajac.kaos.ConsumerHandle;
import io.github.dajac.kaos.ConsumerOptions;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.GroupSnapshot;
import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.SimulationStateException;
import io.github.dajac.kaos.SimulatorSnapshot;
import io.github.dajac.kaos.TopicSpec;
import io.github.dajac.kaos.internal.kafka.GroupCleanup;
import io.github.dajac.kaos.internal.kafka.GroupConfigManager;
import io.github.dajac.kaos.internal.kafka.GroupPoller;
import io.github.dajac.kaos.internal.state.AdminView;
import io.github.dajac.kaos.internal.state.GroupLocalState;
import io.github.dajac.kaos.internal.state.SnapshotAssembler;

/**
 * A consumer group of a simulator: its consumers and what its start leaves on the cluster.
 *
 * <p>The consumers are an immutable list behind a volatile reference, replaced as a whole by
 * whoever holds the structure lock of the simulator. The publisher and the poller read it without
 * a lock. Adding and removing consumers, and removing the group, go through the
 * {@link Harness}, which takes that lock and calls back the methods documented as such.
 */
public final class GroupRuntime implements ConsumerGroup {

    /** What a group needs from its simulator at any time. */
    public interface Harness {
        /** Something the snapshot shows has changed. */
        void changed();

        /** Throws on a thread that must not block, such as the one that runs the listeners. */
        void refuseBlocking();

        /** The latest snapshot. */
        SimulatorSnapshot snapshot();

        /** Waits for a snapshot. */
        SimulatorSnapshot awaitSnapshot(Predicate<SimulatorSnapshot> condition, Duration timeout);

        /** Adds a consumer to the group, under the structure lock. */
        ConsumerHandle addConsumer(GroupRuntime group, ConsumerSpec spec);

        /** Removes a consumer from the group, under the structure lock. */
        void removeConsumer(GroupRuntime group, ConsumerRunner consumer);

        /** Removes the group from the simulator, under the structure lock. */
        void removeGroup(GroupRuntime group);
    }

    /**
     * What a group needs from its simulator to run.
     *
     * @param consumers what the consumers need
     * @param configs sets and reverts the group configs
     * @param cleanup removes the group from the cluster
     */
    public record Services(ConsumerRunner.Services consumers, GroupConfigManager configs, GroupCleanup cleanup) {
        public Services {
            Objects.requireNonNull(consumers, "consumers");
            Objects.requireNonNull(configs, "configs");
            Objects.requireNonNull(cleanup, "cleanup");
        }
    }

    /** What the consumers call back. */
    private final class ConsumerOwner implements ConsumerRunner.Owner {
        @Override
        public void changed() {
            harness.changed();
        }

        @Override
        public void refuseBlocking() {
            harness.refuseBlocking();
        }

        @Override
        public void remove(ConsumerRunner consumer) {
            harness.removeConsumer(GroupRuntime.this, consumer);
        }
    }

    private static final String ASSIGNED_ID_PREFIX = "consumer-";

    private final GroupSpec spec;
    private final List<TopicSpec> topics;
    private final Harness harness;
    private final ConsumerRunner.Owner owner = new ConsumerOwner();

    private volatile List<ConsumerRunner> consumers;
    private volatile List<String> warnings = List.of();
    private volatile boolean removed;

    // Guarded by the structure lock of the simulator.
    /** Every consumer id the group has or had. An id is never used twice in the life of the group. */
    private final Set<String> usedIds = new HashSet<>();
    /** The sub-stack of each running consumer. */
    private final Map<ConsumerRunner, Resources> consumerResources = new IdentityHashMap<>();
    /** Set while the group runs. */
    private Services services;
    /** The stack that holds the sub-stack of each consumer, while the group runs. */
    private Resources consumerStacks;

    /**
     * @param spec the description of the group; its consumers without an id get one
     * @param topics the subscribed topics of the spec, with their partition counts
     * @param harness the simulator
     */
    public GroupRuntime(GroupSpec spec, List<TopicSpec> topics, Harness harness) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.topics = List.copyOf(topics);
        this.harness = Objects.requireNonNull(harness, "harness");

        // The ids that were supplied are taken first, so that no assigned id collides with one.
        spec.consumers().stream().map(ConsumerSpec::id).filter(id -> !id.isEmpty()).forEach(usedIds::add);
        var runners = new ArrayList<ConsumerRunner>();
        for (var consumer : spec.consumers()) {
            var runner = newConsumer(consumer);
            usedIds.add(runner.id());
            runners.add(runner);
        }
        this.consumers = List.copyOf(runners);
    }

    /**
     * The first {@code consumer-N} that no consumer of the group has or had. The used ids only
     * grow, so an id is never used twice in the life of the group.
     */
    private String nextConsumerId() {
        for (var number = 0;; number++) {
            var id = ASSIGNED_ID_PREFIX + number;
            if (!usedIds.contains(id)) {
                return id;
            }
        }
    }

    @Override
    public String id() {
        return spec.id();
    }

    @Override
    public List<String> subscribedTopics() {
        return spec.subscribedTopics();
    }

    @Override
    public GroupSpec describe() {
        return withConsumers(consumers.stream().map(ConsumerRunner::spec).toList());
    }

    /** The description of the group as it would be with the given consumer added. */
    GroupSpec describeWith(ConsumerSpec added) {
        return withConsumers(append(consumers.stream().map(ConsumerRunner::spec).toList(), added));
    }

    private GroupSpec withConsumers(List<ConsumerSpec> specs) {
        return new GroupSpec(
            spec.id(),
            spec.subscribedTopics(),
            spec.sessionTimeoutMs(),
            spec.heartbeatIntervalMs(),
            spec.assignmentIntervalMs(),
            spec.consumerDefaults(),
            specs
        );
    }

    /** The runners of the consumers. */
    public List<ConsumerRunner> runners() {
        return consumers;
    }

    @Override
    public List<ConsumerHandle> consumers() {
        return List.copyOf(consumers);
    }

    @Override
    public ConsumerHandle consumer(int number) {
        return consumer(ASSIGNED_ID_PREFIX + number);
    }

    @Override
    public ConsumerHandle consumer(String id) {
        Objects.requireNonNull(id, "id");
        return consumers.stream()
            .filter(consumer -> consumer.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Group " + spec.id() + " has no consumer " + id));
    }

    @Override
    public ConsumerHandle addConsumer(Consumer<ConsumerOptions> options) {
        return addConsumer(ConsumerSpec.of(options));
    }

    @Override
    public ConsumerHandle addConsumer(ConsumerSpec consumer) {
        Objects.requireNonNull(consumer, "consumer");
        return harness.addConsumer(this, consumer);
    }

    @Override
    public void remove() {
        harness.removeGroup(this);
    }

    @Override
    public List<String> warnings() {
        return warnings;
    }

    @Override
    public GroupSnapshot snapshot() {
        // A group that is not published, because it was just declared or is already removed, is
        // shown as the simulator knows it.
        return slice(harness.snapshot()).orElseGet(() -> SnapshotAssembler.assemble(localState(), AdminView.unknown()));
    }

    @Override
    public GroupSnapshot awaitSnapshot(Predicate<GroupSnapshot> condition, Duration timeout) {
        Objects.requireNonNull(condition, "condition");
        checkNotRemoved();
        var matched = harness.awaitSnapshot(snapshot -> slice(snapshot).filter(condition).isPresent(), timeout);
        return slice(matched).orElseThrow();
    }

    private Optional<GroupSnapshot> slice(SimulatorSnapshot snapshot) {
        return snapshot.groups().stream().filter(group -> group.id().equals(spec.id())).findFirst();
    }

    /** What the poller needs to know about the group. */
    public GroupPoller.Target target() {
        return new GroupPoller.Target(spec.id(), topics);
    }

    /** The copy of the state of the group that goes into the snapshot. */
    public GroupLocalState localState() {
        return new GroupLocalState(
            spec.id(),
            topics,
            consumers.stream().map(ConsumerRunner::localState).toList()
        );
    }

    /** @throws SimulationStateException if the group is removed */
    public void checkNotRemoved() {
        if (removed) {
            throw new SimulationStateException("The group " + spec.id() + " is removed");
        }
    }

    /**
     * Called by the simulator, under the structure lock, once the group is removed: the group and
     * its consumers refuse to act from now on.
     */
    public void markRemoved() {
        removed = true;
        consumers.forEach(ConsumerRunner::markRemoved);
    }

    /** Whether the group has, or had, a consumer with the id. The caller holds the structure lock. */
    public boolean everHad(String consumerId) {
        return usedIds.contains(consumerId);
    }

    /**
     * Creates the runner of a consumer, with its id assigned, without adding it to the group: the
     * caller starts it with {@link #startConsumer} when the group runs, and then adds it with
     * {@link #commitConsumer}. A consumer that is never committed uses up no id. The caller holds
     * the structure lock.
     */
    public ConsumerRunner newConsumer(ConsumerSpec consumer) {
        var identified = consumer.id().isEmpty()
            ? new ConsumerSpec(nextConsumerId(), consumer.overrides(), consumer.effective())
            : consumer;
        return new ConsumerRunner(
            spec.id(),
            identified,
            spec.subscribedTopics(),
            ConsumerRunner.ClassicTimeouts.of(spec),
            owner
        );
    }

    /**
     * Starts a consumer of a running group, on its own sub-stack of the stack of the consumers. A
     * start that fails leaves nothing behind. Closing the sub-stack stops the consumer, which makes
     * it leave the group, and then removes its static member if the coordinator still lists it.
     * The caller holds the structure lock.
     */
    public void startConsumer(ConsumerRunner consumer) {
        var running = services;
        if (running == null) {
            throw new SimulationStateException("The group " + spec.id() + " does not run");
        }
        var child = consumerStacks.start("consumer " + consumer.clientId(), stack -> {
            var instanceId = consumer.instanceId();
            if (!instanceId.isEmpty()) {
                stack.register(
                    "static member " + instanceId,
                    () -> running.cleanup().removeStaticMembers(spec.id(), Set.of(instanceId))
                );
            }
            consumer.start(running.consumers(), stack);
        });
        consumerResources.put(consumer, child);
    }

    /** Adds a consumer created by {@link #newConsumer} to the group. The caller holds the structure lock. */
    public void commitConsumer(ConsumerRunner consumer) {
        usedIds.add(consumer.id());
        consumers = append(consumers, consumer);
        harness.changed();
    }

    /**
     * Removes a consumer: when the group runs, it closes the sub-stack of the consumer, which stops
     * it and removes a static member that could not leave, each step whatever the other does. The
     * consumer is out of the group afterwards, even when an undo action failed. The caller holds
     * the structure lock.
     */
    public void removeConsumer(ConsumerRunner consumer) {
        var child = consumerResources.remove(consumer);
        try {
            if (child != null) {
                child.close();
            }
        } finally {
            consumer.markRemoved();
            consumers = consumers.stream().filter(other -> other != consumer).toList();
            harness.changed();
        }
    }

    /**
     * Sets the group configs and starts the consumers. Closing {@code resources} asks every
     * consumer to stop, stops each consumer and removes its static member if it is left, deletes
     * the group and reverts the group configs, in that order. The consumers added later have their
     * sub-stack in the same stack of consumers, so they are undone in that order too. The caller
     * holds the structure lock.
     */
    public void start(Services services, Resources resources) {
        var groupId = spec.id();
        this.services = services;
        // Undone last: the group runs until everything it started is undone.
        resources.register("runtime of group " + groupId, () -> {
            this.services = null;
            this.consumerStacks = null;
            consumerResources.clear();
        });

        var applied = services.configs().apply(spec);
        warnings = applied.warnings();
        resources.register("configs of group " + groupId, () -> {
            warnings = List.of();
            services.configs().revert(groupId, applied.previous());
        });
        resources.register("group " + groupId, () -> services.cleanup().deleteGroup(groupId));

        consumerStacks = resources.child("consumers of group " + groupId);
        for (var consumer : consumers) {
            startConsumer(consumer);
        }
        // Undone first: the loops end together, and each consumer then only waits for its own.
        resources.register("stop requests of group " + groupId, () -> consumers.forEach(ConsumerRunner::requestStop));

        harness.changed();
    }

    private static <T> List<T> append(List<T> list, T element) {
        var appended = new ArrayList<T>(list);
        appended.add(element);
        return List.copyOf(appended);
    }
}
