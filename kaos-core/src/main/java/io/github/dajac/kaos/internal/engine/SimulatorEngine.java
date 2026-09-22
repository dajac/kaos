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
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.github.dajac.kaos.ConsumerGroup;
import io.github.dajac.kaos.ConsumerHandle;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.InvalidConfigException;
import io.github.dajac.kaos.Phase;
import io.github.dajac.kaos.ProducerHandle;
import io.github.dajac.kaos.ProducerSpec;
import io.github.dajac.kaos.Registration;
import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.SimulationStateException;
import io.github.dajac.kaos.SimulatorSnapshot;
import io.github.dajac.kaos.SimulatorSpec;
import io.github.dajac.kaos.SnapshotListener;
import io.github.dajac.kaos.Topic;
import io.github.dajac.kaos.TopicSpec;
import io.github.dajac.kaos.Violation;
import io.github.dajac.kaos.internal.config.ConfigValidator;
import io.github.dajac.kaos.internal.kafka.ClientFactory;
import io.github.dajac.kaos.internal.kafka.GroupCleanup;
import io.github.dajac.kaos.internal.kafka.GroupConfigManager;
import io.github.dajac.kaos.internal.kafka.GroupPoller;
import io.github.dajac.kaos.internal.kafka.TopicManager;
import io.github.dajac.kaos.internal.proxy.NetworkFaults;
import io.github.dajac.kaos.internal.proxy.NetworkProxy;
import io.github.dajac.kaos.internal.proxy.ProxyLauncher;
import io.github.dajac.kaos.internal.state.AdminView;
import io.github.dajac.kaos.internal.state.EventLog;
import io.github.dajac.kaos.internal.state.SnapshotAssembler;
import io.github.dajac.kaos.internal.state.SnapshotPublisher;
import org.apache.kafka.clients.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The engine behind a {@code Simulator}: its settings, its components, its lifecycle and its
 * snapshot publisher.
 *
 * <p>The {@code structureLock} serializes every call that changes the settings, the components
 * or the phase. The phase and the component lists live in one immutable {@link State} that is
 * swapped under the lock, so the publisher reads a consistent structure without a lock. Flag
 * flips, such as pausing a producer, take no lock and poke the publisher.
 *
 * <p>Components are added and removed the same way whether the simulator is idle or runs. While it
 * runs, a component is started on its own sub-stack of the {@link Resources} of its kind before it
 * joins the state, so a failed start unwinds that sub-stack and leaves the state as it was. A
 * removal closes the sub-stack, which undoes that component only, and takes the component out of
 * the state even when an undo action failed.
 *
 * <p>The publisher thread runs from the first declaration, or the start, until the stop. It is
 * the only thread of an idle simulator.
 *
 * <p>Phase changes are flushed while the lock is held, that is the lifecycle thread waits for the
 * publisher thread. A listener that took the lock would then wait for it forever, so every method
 * that takes the lock, or waits for a snapshot, refuses to run on the publisher thread.
 */
public final class SimulatorEngine {

    /** The phase and the components. The lists are immutable and replaced as a whole. */
    private record State(
        Phase phase,
        String lastError,
        List<TopicComponent> topics,
        List<ProducerRunner> producers,
        List<GroupRuntime> groups
    ) {
        private static final State EMPTY = new State(Phase.IDLE, "", List.of(), List.of(), List.of());

        State withPhase(Phase newPhase, String newLastError) {
            return new State(newPhase, newLastError, topics, producers, groups);
        }

        State withTopics(List<TopicComponent> newTopics) {
            return new State(phase, lastError, newTopics, producers, groups);
        }

        State withProducers(List<ProducerRunner> newProducers) {
            return new State(phase, lastError, topics, newProducers, groups);
        }

        State withGroups(List<GroupRuntime> newGroups) {
            return new State(phase, lastError, topics, producers, newGroups);
        }
    }

    /**
     * What a started simulator holds. The components of each kind have a stack of their own, in
     * the order of the start, so that the stop undoes the groups first and the topics last,
     * whenever they were added.
     */
    private record Running(
        Resources all,
        Resources topics,
        Resources producers,
        Resources groups,
        ClientFactory clients,
        TopicManager topicManager,
        GroupRuntime.Services groupServices
    ) {}

    /** What the groups and their consumers call back. */
    private final class GroupHarness implements GroupRuntime.Harness {
        @Override
        public void changed() {
            publisher.poke();
        }

        @Override
        public void refuseBlocking() {
            refuseListenerThread();
        }

        @Override
        public SimulatorSnapshot snapshot() {
            return publisher.current();
        }

        @Override
        public SimulatorSnapshot awaitSnapshot(Predicate<SimulatorSnapshot> condition, Duration timeout) {
            return SimulatorEngine.this.awaitSnapshot(condition, timeout);
        }

        @Override
        public ConsumerHandle addConsumer(GroupRuntime group, ConsumerSpec spec) {
            return SimulatorEngine.this.addConsumer(group, spec);
        }

        @Override
        public void removeConsumer(GroupRuntime group, ConsumerRunner consumer) {
            SimulatorEngine.this.removeConsumer(group, consumer);
        }

        @Override
        public void removeGroup(GroupRuntime group) {
            SimulatorEngine.this.removeGroup(group);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(SimulatorEngine.class);

    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROXY_LAUNCH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration ADMIN_CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration GROUP_ADMIN_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);

    private final ReentrantLock structureLock = new ReentrantLock();
    private final Threads threads = new Threads();
    private final SnapshotPublisher publisher;
    private final String bootstrapServers;
    private final GroupRuntime.Harness groupHarness = new GroupHarness();

    private volatile State state = State.EMPTY;
    /** The poller of a started simulator. The publisher thread reads the views of the groups from it. */
    private volatile GroupPoller poller;

    // Guarded by the structureLock.
    private final Map<String, String> clientProperties = new HashMap<>();
    private int proxyPort = ProxyLauncher.DEFAULT_PORT;
    /** Every producer id used since the simulator was created, or last stopped. */
    private final Set<String> usedProducerIds = new HashSet<>();
    /** The sub-stack of each topic, producer and group, while the simulator runs. */
    private final Map<Object, Resources> componentResources = new IdentityHashMap<>();
    private Running running;

    public SimulatorEngine(String bootstrapServers) {
        this.bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        if (bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("The bootstrap servers must not be blank");
        }
        this.publisher = new SnapshotPublisher(this::assemble, task -> threads.newThread("publisher", task));
        this.publisher.addListener(new EventLog());
    }

    /** @throws IllegalStateException once the simulator is started */
    public void setClientProperty(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        configure(() -> clientProperties.put(key, value));
    }

    /** @throws IllegalStateException once the simulator is started */
    public void setProxyPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid proxy port: " + port);
        }
        configure(() -> proxyPort = port);
    }

    /** @throws IllegalStateException once the simulator is started */
    public void addInitialListener(SnapshotListener listener) {
        Objects.requireNonNull(listener, "listener");
        configure(() -> publisher.addListener(listener));
    }

    private void configure(Runnable change) {
        refuseListenerThread();
        structureLock.lock();
        try {
            if (state.phase() != Phase.IDLE) {
                throw new IllegalStateException("The simulator is started; it is configured before start()");
            }
            change.run();
        } finally {
            structureLock.unlock();
        }
    }

    public Registration addListener(SnapshotListener listener) {
        return publisher.addListener(listener);
    }

    public Topic addTopic(TopicSpec spec) {
        Objects.requireNonNull(spec, "spec");
        refuseListenerThread();
        structureLock.lock();
        try {
            var current = changeableState();
            var description = describe(current);
            validate(
                new SimulatorSpec(append(description.topics(), spec), description.producers(), description.groups()));

            var topic = new TopicComponent(spec, this::removeTopic);
            var started = running;
            if (started != null) {
                startComponent(
                    started.topics(),
                    topic,
                    "topic " + topic.name(),
                    resources -> startTopic(started.topicManager(), topic, resources)
                );
            }
            change(current.withTopics(append(current.topics(), topic)));
            return topic;
        } finally {
            structureLock.unlock();
        }
    }

    public ProducerHandle addProducer(ProducerSpec spec) {
        Objects.requireNonNull(spec, "spec");
        refuseListenerThread();
        structureLock.lock();
        try {
            var current = changeableState();
            var description = describe(current);
            validate(
                new SimulatorSpec(description.topics(), append(description.producers(), spec), description.groups()));

            var identified = spec.id().isEmpty()
                ? new ProducerSpec(nextProducerId(), spec.topics(), spec.ratePerPartition())
                : spec;
            var targets = current.topics().stream()
                .map(TopicComponent::spec)
                .filter(topic -> identified.topics().contains(topic.name()))
                .toList();
            var producer = new ProducerRunner(identified, targets, publisher::poke, this::removeProducer);
            var started = running;
            if (started != null) {
                startComponent(
                    started.producers(),
                    producer,
                    "producer " + producer.id(),
                    resources -> startProducer(started.clients(), producer, resources)
                );
            }
            usedProducerIds.add(producer.id());
            change(current.withProducers(append(current.producers(), producer)));
            return producer;
        } finally {
            structureLock.unlock();
        }
    }

    public ConsumerGroup addGroup(GroupSpec spec) {
        Objects.requireNonNull(spec, "spec");
        refuseListenerThread();
        structureLock.lock();
        try {
            var current = changeableState();
            var description = describe(current);
            validate(
                new SimulatorSpec(description.topics(), description.producers(), append(description.groups(), spec))
            );

            var subscribed = current.topics().stream()
                .map(TopicComponent::spec)
                .filter(topic -> spec.subscribedTopics().contains(topic.name()))
                .toList();
            var group = new GroupRuntime(spec, subscribed, groupHarness);
            var index = current.groups().size();
            var runners = group.runners();
            for (int i = 0; i < runners.size(); i++) {
                var field = "groups[" + index + "].consumers[" + i + "].id";
                checkClientId(current, null, runners.get(i).clientId(), field);
            }
            var started = running;
            if (started != null) {
                startComponent(
                    started.groups(),
                    group,
                    "group " + group.id(),
                    resources -> group.start(started.groupServices(), resources)
                );
            }
            change(current.withGroups(append(current.groups(), group)));
            return group;
        } finally {
            structureLock.unlock();
        }
    }

    /**
     * Adds a consumer to a group. The whole spec is validated with the consumer appended to its
     * group, so the violations have the paths of the whole spec.
     */
    private ConsumerHandle addConsumer(GroupRuntime group, ConsumerSpec spec) {
        refuseListenerThread();
        structureLock.lock();
        try {
            group.checkNotRemoved();
            var current = changeableState();
            var description = describe(current);
            var index = current.groups().indexOf(group);
            var candidate = group.describeWith(spec);
            var groups = new ArrayList<>(description.groups());
            groups.set(index, candidate);
            validate(new SimulatorSpec(description.topics(), description.producers(), groups));

            // The group spec resolved the effective settings of the consumer against its defaults.
            var position = candidate.consumers().size() - 1;
            var consumer = group.newConsumer(candidate.consumers().get(position));
            checkClientId(current, group, consumer.clientId(), "groups[" + index + "].consumers[" + position + "].id");
            if (running != null) {
                wrapFailure("consumer " + consumer.clientId(), () -> group.startConsumer(consumer));
            }
            group.commitConsumer(consumer);
            return consumer;
        } finally {
            structureLock.unlock();
        }
    }

    /**
     * A client id is {@code <groupId>-<consumerId>}, and both parts may contain a dash, so two
     * groups can end up with the same one. The network faults match on it, so it must be unique.
     *
     * @param owner the group of the consumer when it is in the state already, or {@code null}
     */
    private static void checkClientId(State current, GroupRuntime owner, String clientId, String field) {
        for (var group : current.groups()) {
            if (group != owner && group.runners().stream().anyMatch(runner -> runner.clientId().equals(clientId))) {
                throw new InvalidConfigException(List.of(new Violation(
                    field,
                    "the client id '" + clientId + "' is already used by a consumer of group '" + group.id() + "'"
                )));
            }
        }
    }

    /**
     * Removes a topic that no producer and no group uses. While the simulator runs, the topic is
     * deleted and the call waits until the cluster no longer reports it.
     */
    private void removeTopic(TopicComponent topic) {
        refuseListenerThread();
        structureLock.lock();
        try {
            topic.checkNotRemoved();
            var current = changeableState();
            var users = new ArrayList<String>();
            current.producers().stream()
                .filter(producer -> producer.topics().contains(topic.name()))
                .forEach(producer -> users.add("producer " + producer.id()));
            current.groups().stream()
                .filter(group -> group.subscribedTopics().contains(topic.name()))
                .forEach(group -> users.add("group " + group.id()));
            if (!users.isEmpty()) {
                throw new SimulationStateException(
                    "The topic " + topic.name() + " is still used by " + String.join(", ", users)
                );
            }
            try {
                closeComponent(topic);
            } finally {
                topic.markRemoved();
                change(current.withTopics(without(current.topics(), topic)));
            }
        } finally {
            structureLock.unlock();
        }
    }

    private void removeProducer(ProducerRunner producer) {
        refuseListenerThread();
        structureLock.lock();
        try {
            producer.checkNotRemoved();
            var current = changeableState();
            try {
                closeComponent(producer);
            } finally {
                producer.markRemoved();
                change(current.withProducers(without(current.producers(), producer)));
            }
        } finally {
            structureLock.unlock();
        }
    }

    private void removeGroup(GroupRuntime group) {
        refuseListenerThread();
        structureLock.lock();
        try {
            group.checkNotRemoved();
            var current = changeableState();
            try {
                closeComponent(group);
            } finally {
                group.markRemoved();
                change(current.withGroups(without(current.groups(), group)));
            }
        } finally {
            structureLock.unlock();
        }
    }

    private void removeConsumer(GroupRuntime group, ConsumerRunner consumer) {
        refuseListenerThread();
        structureLock.lock();
        try {
            consumer.checkNotRemoved();
            changeableState();
            group.removeConsumer(consumer);
        } finally {
            structureLock.unlock();
        }
    }

    /**
     * Starts a component added while the simulator runs, on its own sub-stack of the stack of its
     * kind. A failure unwinds that sub-stack, and leaves the stack of its kind as it was.
     */
    private void startComponent(Resources kind, Object component, String name, Resources.Step step) {
        wrapFailure(name, () -> componentResources.put(component, kind.start(name, step)));
    }

    /** Runs the start of a component, and reports its failure as a {@link SimulationException}. */
    private static void wrapFailure(String name, Runnable start) {
        try {
            start.run();
        } catch (SimulationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SimulationException("Could not start " + name + ": " + e, e);
        }
    }

    /** Undoes the start of a component, when it runs. */
    private void closeComponent(Object component) {
        var stack = componentResources.remove(component);
        if (stack != null) {
            stack.close();
        }
    }

    /**
     * The state to add components to or remove them from. The caller holds the lock, and a start or
     * a stop holds it from its first phase change to its last, so the phase is idle or running.
     */
    private State changeableState() {
        var current = state;
        if (current.phase() != Phase.IDLE && current.phase() != Phase.RUNNING) {
            throw new SimulationStateException(
                "Components cannot be changed while the simulator is " + current.phase());
        }
        return current;
    }

    private void change(State newState) {
        state = newState;
        publisher.start();
        publisher.poke();
    }

    /**
     * The first {@code producer-N} that no producer has or had since the simulator was created, or
     * last stopped.
     */
    private String nextProducerId() {
        for (var number = 0;; number++) {
            var id = "producer-" + number;
            if (!usedProducerIds.contains(id)) {
                return id;
            }
        }
    }

    /**
     * Starts the proxy, then the topics, the producers and the groups, and returns when all of
     * them run. A
     * failure undoes the steps that succeeded, brings the simulator back to {@link Phase#IDLE}
     * with its components still declared, and is published as the {@code lastError} of the
     * snapshot before it is thrown.
     */
    public void start() {
        refuseListenerThread();
        structureLock.lock();
        try {
            var current = state;
            if (current.phase() != Phase.IDLE) {
                throw new SimulationStateException("The simulator is already started");
            }
            validate(describe(current));

            state = current.withPhase(Phase.STARTING, "");
            publisher.start();
            publisher.flush();

            var starting = new Resources("the simulator");
            Running started;
            try {
                started = startComponents(current, starting);
            } catch (Throwable e) {
                var failure = e instanceof SimulationException known
                    ? known
                    : new SimulationException("The simulator failed to start: " + e, e);
                LOG.warn("The start failed, undoing it: {}", failure.getMessage());
                starting.close(failure);
                componentResources.clear();
                state = current.withPhase(Phase.IDLE, failure.getMessage());
                publisher.flush();
                throw failure;
            }
            running = started;
            state = current.withPhase(Phase.RUNNING, "");
            publisher.flush();
        } finally {
            structureLock.unlock();
        }
    }

    private Running startComponents(State current, Resources starting) {
        var clients = new ClientFactory(bootstrapServers, clientProperties);

        var admin = clients.createAdmin(Threads.PREFIX + "admin");
        starting.register("admin client", () -> admin.close(ADMIN_CLOSE_TIMEOUT));

        NetworkProxy proxy;
        try {
            var launcher = new ProxyLauncher(bootstrapServers, proxyPort, PROXY_LAUNCH_TIMEOUT);
            proxy = launcher.launch(admin, new NetworkFaults());
            starting.register("proxy", proxy::close);
        } catch (IllegalStateException e) {
            throw new SimulationException("The proxy failed to start: " + e.getMessage(), e);
        }

        var topicManager = new TopicManager(admin, ADMIN_TIMEOUT);
        var topics = starting.child("topics");
        for (var topic : current.topics()) {
            var stack = topics.start("topic " + topic.name(), resources -> startTopic(topicManager, topic, resources));
            componentResources.put(topic, stack);
        }

        var producers = starting.child("producers");
        for (var producer : current.producers()) {
            var stack = producers.start(
                "producer " + producer.id(),
                resources -> startProducer(clients, producer, resources)
            );
            componentResources.put(producer, stack);
        }

        // The poller reads the groups from the state at every poll. It runs before them, so that
        // the snapshot shows their members as they join, and stops after them.
        var polling = new GroupPoller(
            admin,
            () -> state.groups().stream().map(GroupRuntime::target).toList(),
            publisher::poke,
            GroupPoller.INTERVAL,
            POLL_TIMEOUT
        );
        polling.start(task -> threads.newThread("poller", task));
        poller = polling;
        starting.register("poller", () -> {
            try {
                polling.stop();
            } finally {
                poller = null;
            }
        });

        var proxyBootstrapServers = proxy.bootstrapServers();
        var services = new GroupRuntime.Services(
            new ConsumerRunner.Services(
                settings -> clients.createConsumer(proxyBootstrapServers, settings),
                threads,
                proxy.faults()
            ),
            new GroupConfigManager(admin, GROUP_ADMIN_TIMEOUT),
            new GroupCleanup(admin, GROUP_ADMIN_TIMEOUT)
        );
        var groups = starting.child("groups");
        for (var group : current.groups()) {
            var stack = groups.start("group " + group.id(), resources -> group.start(services, resources));
            componentResources.put(group, stack);
        }
        return new Running(starting, topics, producers, groups, clients, topicManager, services);
    }

    /** Creates a topic and waits until the cluster reports it. Its undo deletes it. */
    private static void startTopic(TopicManager topicManager, TopicComponent topic, Resources resources) {
        topicManager.create(topic.spec());
        resources.register("topic " + topic.name(), () -> topicManager.delete(topic.name()));
        topicManager.awaitCreated(topic.name());
    }

    private void startProducer(ClientFactory clients, ProducerRunner producer, Resources resources) {
        Supplier<Producer<String, String>> client =
            () -> clients.createProducer(Threads.PREFIX + producer.threadName());
        producer.start(client, threads, resources);
    }

    /**
     * Stops everything, removes every component and returns to {@link Phase#IDLE}. Idempotent. A
     * stop that has nothing running keeps the {@code lastError}: it is how the failure of a start
     * survives the removal of the components it declared.
     *
     * @throws SimulationException if undo actions failed, after all of them have run
     */
    public void stop() {
        refuseListenerThread();
        structureLock.lock();
        try {
            SimulationException failure = null;
            var lastError = state.lastError();
            var started = running;
            if (started != null) {
                lastError = "";
                state = state.withPhase(Phase.STOPPING, "");
                publisher.flush();
                try {
                    started.all().close();
                } catch (SimulationException e) {
                    failure = e;
                }
                running = null;
                componentResources.clear();
            }
            // The handles of the removed components refuse to act from now on.
            var removed = state;
            removed.topics().forEach(TopicComponent::markRemoved);
            removed.producers().forEach(ProducerRunner::markRemoved);
            removed.groups().forEach(GroupRuntime::markRemoved);
            state = State.EMPTY.withPhase(Phase.IDLE, failure == null ? lastError : failure.getMessage());
            usedProducerIds.clear();
            // Publishes the final snapshot before its thread ends.
            publisher.stop();
            if (failure != null) {
                throw failure;
            }
        } finally {
            structureLock.unlock();
        }
    }

    public SimulatorSpec describe() {
        return describe(state);
    }

    public List<Topic> topics() {
        return List.copyOf(state.topics());
    }

    public List<ProducerHandle> producers() {
        return List.copyOf(state.producers());
    }

    public List<ConsumerGroup> groups() {
        return List.copyOf(state.groups());
    }

    public SimulatorSnapshot snapshot() {
        return publisher.current();
    }

    public SimulatorSnapshot awaitSnapshot(Predicate<SimulatorSnapshot> condition, Duration timeout) {
        refuseListenerThread();
        return publisher.await(condition, timeout);
    }

    /** Called before the lock is taken, or a snapshot awaited. */
    private void refuseListenerThread() {
        if (publisher.onPublisherThread()) {
            throw new SimulationStateException(
                "This method blocks and must not be called from a snapshot listener; call it from another thread"
            );
        }
    }

    private static SimulatorSpec describe(State current) {
        return new SimulatorSpec(
            current.topics().stream().map(TopicComponent::spec).toList(),
            current.producers().stream().map(ProducerRunner::spec).toList(),
            current.groups().stream().map(GroupRuntime::describe).toList()
        );
    }

    /** Called by the publisher thread. */
    private SimulatorSnapshot assemble() {
        var current = state;
        var polling = poller;
        return new SimulatorSnapshot(
            0,
            current.phase(),
            polling != null && polling.stale(),
            current.lastError(),
            current.topics().stream().map(TopicComponent::spec).toList(),
            current.producers().stream().map(ProducerRunner::snapshot).toList(),
            current.groups().stream()
                .map(group -> SnapshotAssembler.assemble(
                    group.localState(),
                    polling == null ? AdminView.unknown() : polling.view(group.id())
                ))
                .toList()
        );
    }

    private static void validate(SimulatorSpec spec) {
        var violations = ConfigValidator.validate(spec);
        if (!violations.isEmpty()) {
            throw new InvalidConfigException(violations);
        }
    }

    private static <T> List<T> without(List<T> list, T element) {
        return list.stream().filter(other -> other != element).toList();
    }

    private static <T> List<T> append(List<? extends T> list, T element) {
        var appended = new ArrayList<T>(list);
        appended.add(element);
        return List.copyOf(appended);
    }
}
