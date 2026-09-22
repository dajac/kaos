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

import io.github.dajac.kaos.internal.config.ConfigValidator;
import io.github.dajac.kaos.internal.engine.SimulatorEngine;

/**
 * The simulator harness. It holds topics, producers and consumer groups, which run against a Kafka
 * cluster between {@link #start()} and {@link #stop()}.
 *
 * <pre>{@code
 * try (Simulator simulator = new Simulator("localhost:9092")) {
 *     Topic orders = simulator.addTopic("orders", 36);
 *     ProducerHandle load = simulator.addProducer(p -> p.topics(orders));
 *     ConsumerGroup group = simulator.addGroup("orders-app", g -> g.subscribe(orders).consumers(3));
 *     simulator.start();
 *     group.consumer(0).inject(Fault.freeze());
 *     load.pause();
 * }
 * }</pre>
 *
 * <p>Components are added with {@code add…} and removed with their {@code remove()}. Before
 * {@code start()}, they are declared and {@code start()} starts them; while the simulator runs, they
 * are started or stopped at once, and the call blocks until they are. A component that fails to
 * start while the simulator runs leaves the simulator as it was. Every method is thread-safe, and
 * several simulators can run in one JVM.
 *
 * <p>The simulator has one thread of its own, {@code kaos-publisher}, from the first declaration
 * (or {@code start()}) until {@code stop()} or {@code close()}. So a simulator that has declared
 * something is closed even when it never started, or failed to start.
 *
 * <p>{@code start}, {@code stop}, {@code close}, {@code with…}, {@code add…}, {@code remove()} and
 * {@code awaitSnapshot} block, and throw a {@link SimulationStateException} when a
 * {@link SnapshotListener} calls them.
 */
public final class Simulator implements AutoCloseable {

    private final SimulatorEngine engine;

    /**
     * @param bootstrapServers the {@code bootstrap.servers} of the cluster
     */
    public Simulator(String bootstrapServers) {
        this.engine = new SimulatorEngine(bootstrapServers);
    }

    /**
     * Checks a spec without any side effect. It runs the same checks as adding the components and
     * starting the simulator, and reports every violation at once.
     *
     * @throws InvalidConfigException if the spec is invalid
     */
    public static void validate(SimulatorSpec spec) {
        var violations = ConfigValidator.validate(spec);
        if (!violations.isEmpty()) {
            throw new InvalidConfigException(violations);
        }
    }

    /**
     * Sets a property of every Kafka client of the simulator, such as {@code security.protocol}.
     *
     * @throws IllegalStateException after {@link #start()}
     */
    public Simulator withClientProperty(String key, String value) {
        engine.setClientProperty(key, value);
        return this;
    }

    /**
     * Sets the preferred port of the proxy. The proxy takes the first free block of ports from
     * there: one port, plus one per broker. The default is 19092.
     *
     * @throws IllegalStateException after {@link #start()}
     */
    public Simulator withProxyPort(int port) {
        engine.setProxyPort(port);
        return this;
    }

    /**
     * Adds a listener that lives as long as the simulator. Attached before {@link #start()}, it
     * observes {@link Phase#STARTING} and, in {@link SimulatorSnapshot#lastError()}, a start
     * failure.
     *
     * @throws IllegalStateException after {@link #start()}; use {@link #addListener} then
     */
    public Simulator withListener(SnapshotListener listener) {
        engine.addInitialListener(listener);
        return this;
    }

    /**
     * Adds a listener, at any time. It receives the snapshots published from now on;
     * {@link #snapshot()} gives the current one.
     */
    public Registration addListener(SnapshotListener listener) {
        return engine.addListener(listener);
    }

    /**
     * Adds a topic. {@link #start()} creates it, or the call creates it and waits until the cluster
     * reports it when the simulator runs. {@link #stop()} and {@link Topic#remove()} delete it.
     *
     * @throws InvalidConfigException if the topic is invalid or its name is taken
     * @throws SimulationException if the simulator runs and the topic cannot be created
     */
    public Topic addTopic(String name, int partitions) {
        return addTopic(new TopicSpec(name, partitions));
    }

    /** Adds a topic from its spec. See {@link #addTopic(String, int)}. */
    public Topic addTopic(TopicSpec spec) {
        return engine.addTopic(spec);
    }

    /**
     * Adds a producer, which {@link #start()} starts, or which starts at once when the simulator
     * runs. It gets the id {@code producer-N} unless the options set one; an assigned id is never
     * used again until {@link #stop()}.
     *
     * @throws InvalidConfigException if the producer is invalid, names a topic that is not
     *     declared, or has an id that is taken
     * @throws SimulationException if the simulator runs and the producer cannot be started
     */
    public ProducerHandle addProducer(Consumer<ProducerOptions> options) {
        return addProducer(ProducerSpec.of(options));
    }

    /** Adds a producer from its spec. See {@link #addProducer(Consumer)}. */
    public ProducerHandle addProducer(ProducerSpec spec) {
        return engine.addProducer(spec);
    }

    /**
     * Adds a consumer group, which {@link #start()} starts, or which starts at once when the
     * simulator runs: it sets the group configs that the options set, and starts the consumers,
     * which connect through the proxy. A consumer gets the id {@code consumer-N} unless its spec
     * has one.
     *
     * @throws InvalidConfigException if the group is invalid, subscribes to a topic that is not
     *     declared, or has an id that is taken
     * @throws SimulationException if the simulator runs and the group cannot be started
     */
    public ConsumerGroup addGroup(String id, Consumer<GroupOptions> options) {
        return addGroup(GroupSpec.of(id, options));
    }

    /** Adds a consumer group from its spec. See {@link #addGroup(String, Consumer)}. */
    public ConsumerGroup addGroup(GroupSpec spec) {
        return engine.addGroup(spec);
    }

    /** The topics, in the order they were added. */
    public List<Topic> topics() {
        return engine.topics();
    }

    /** The producers, in the order they were added. */
    public List<ProducerHandle> producers() {
        return engine.producers();
    }

    /** The consumer groups, in the order they were added. */
    public List<ConsumerGroup> groups() {
        return engine.groups();
    }

    /**
     * Starts the proxy, creates the topics, and starts the producers and then the groups. The
     * call blocks until everything runs; the consumers then join their groups on their own.
     *
     * <p>When a step fails, the steps that succeeded are undone: no thread, port or topic is
     * left behind. The simulator is then {@link Phase#IDLE} again, with its components still
     * declared, and the snapshot carries the failure in {@link SimulatorSnapshot#lastError()}.
     *
     * @throws InvalidConfigException if the declared components are invalid
     * @throws SimulationStateException if the simulator is already started
     * @throws SimulationException if a step failed
     */
    public void start() {
        engine.start();
    }

    /**
     * Stops the groups and the producers, deletes the topics, stops the proxy and removes every
     * component. The call blocks, and is idempotent. The simulator can be used again afterwards.
     * Stopping a group stops its consumers, which leave the group, removes the static members
     * that could not, deletes the group when it is empty and reverts its group configs.
     *
     * <p>A call that is concurrent with {@link #start()} waits for the start to finish, and then
     * stops what it started. A call that finds nothing running, such as the one that removes the
     * components after a failed start, keeps {@link SimulatorSnapshot#lastError()}.
     *
     * @throws SimulationException if something could not be undone, after everything else was
     */
    public void stop() {
        engine.stop();
    }

    /** The same as {@link #stop()}. */
    @Override
    public void close() {
        stop();
    }

    /** The immutable description of the components. */
    public SimulatorSpec describe() {
        return engine.describe();
    }

    /** The latest snapshot. */
    public SimulatorSnapshot snapshot() {
        return engine.snapshot();
    }

    /**
     * Waits until a snapshot matches the condition, starting with the current one, and returns
     * it.
     *
     * @throws SimulationException if no snapshot matches within the timeout
     */
    public SimulatorSnapshot awaitSnapshot(Predicate<SimulatorSnapshot> condition, Duration timeout) {
        return engine.awaitSnapshot(condition, timeout);
    }
}
