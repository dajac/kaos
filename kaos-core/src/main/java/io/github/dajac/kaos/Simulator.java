package io.github.dajac.kaos;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.github.dajac.kaos.internal.config.ConfigValidator;
import io.github.dajac.kaos.internal.engine.SimulatorEngine;

/**
 * The simulator harness. It holds topics and producers, which run against a Kafka cluster between
 * {@link #start()} and {@link #stop()}.
 *
 * <pre>{@code
 * try (Simulator simulator = new Simulator("localhost:9092")) {
 *     Topic orders = simulator.addTopic("orders", 36);
 *     ProducerHandle load = simulator.addProducer(p -> p.topics(orders));
 *     simulator.start();
 *     load.pause();
 * }
 * }</pre>
 *
 * <p>Components are declared with {@code add…} before {@code start()}. Every method is
 * thread-safe, and several simulators can run in one JVM.
 *
 * <p>The simulator has one thread of its own, {@code kaos-publisher}, from the first declaration
 * (or {@code start()}) until {@code stop()} or {@code close()}. So a simulator that has declared
 * something is closed even when it never started, or failed to start.
 *
 * <p>{@code start}, {@code stop}, {@code close}, {@code with…}, {@code add…} and
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
     * Declares a topic. {@link #start()} creates it and {@link #stop()} deletes it.
     *
     * @throws InvalidConfigException if the topic is invalid or its name is taken
     * @throws SimulationStateException if the simulator is started
     */
    public Topic addTopic(String name, int partitions) {
        return addTopic(new TopicSpec(name, partitions));
    }

    /** Declares a topic from its spec. See {@link #addTopic(String, int)}. */
    public Topic addTopic(TopicSpec spec) {
        return engine.addTopic(spec);
    }

    /**
     * Declares a producer, which {@link #start()} starts. It gets the id {@code producer-N}
     * unless the options set one.
     *
     * @throws InvalidConfigException if the producer is invalid, names a topic that is not
     *     declared, or has an id that is taken
     * @throws SimulationStateException if the simulator is started
     */
    public ProducerHandle addProducer(Consumer<ProducerOptions> options) {
        return addProducer(ProducerSpec.of(options));
    }

    /** Declares a producer from its spec. See {@link #addProducer(Consumer)}. */
    public ProducerHandle addProducer(ProducerSpec spec) {
        return engine.addProducer(spec);
    }

    /** The topics, in the order they were added. */
    public List<Topic> topics() {
        return engine.topics();
    }

    /** The producers, in the order they were added. */
    public List<ProducerHandle> producers() {
        return engine.producers();
    }

    /**
     * Starts the proxy, creates the topics and starts the producers, in that order. The call
     * blocks until everything runs.
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
     * Stops the producers, deletes the topics, stops the proxy and removes every component. The
     * call blocks, and is idempotent. The simulator can be used again afterwards.
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
