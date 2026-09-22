package io.github.dajac.kaos.internal.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
import io.github.dajac.kaos.internal.config.ConfigValidator;
import io.github.dajac.kaos.internal.kafka.ClientFactory;
import io.github.dajac.kaos.internal.kafka.TopicManager;
import io.github.dajac.kaos.internal.proxy.NetworkFaults;
import io.github.dajac.kaos.internal.proxy.ProxyLauncher;
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
 * <p>The publisher thread runs from the first declaration, or the start, until the stop. It is
 * the only thread of an idle simulator.
 *
 * <p>Phase changes are flushed while the lock is held, that is the lifecycle thread waits for the
 * publisher thread. A listener that took the lock would then wait for it forever, so every method
 * that takes the lock, or waits for a snapshot, refuses to run on the publisher thread.
 */
public final class SimulatorEngine {

    /** The phase and the components. The lists are immutable and replaced as a whole. */
    private record State(Phase phase, String lastError, List<TopicComponent> topics, List<ProducerRunner> producers) {
        private static final State EMPTY = new State(Phase.IDLE, "", List.of(), List.of());

        State withPhase(Phase newPhase, String newLastError) {
            return new State(newPhase, newLastError, topics, producers);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(SimulatorEngine.class);

    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROXY_LAUNCH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration ADMIN_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final ReentrantLock structureLock = new ReentrantLock();
    private final Threads threads = new Threads();
    private final SnapshotPublisher publisher;
    private final String bootstrapServers;

    private volatile State state = State.EMPTY;

    // Guarded by the structureLock.
    private final Map<String, String> clientProperties = new HashMap<>();
    private int proxyPort = ProxyLauncher.DEFAULT_PORT;
    private int nextProducerId;
    private Resources resources;

    public SimulatorEngine(String bootstrapServers) {
        this.bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        if (bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("The bootstrap servers must not be blank");
        }
        this.publisher = new SnapshotPublisher(this::assemble, task -> threads.newThread("publisher", task));
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
            var current = declarableState();
            var description = describe(current);
            validate(
                new SimulatorSpec(append(description.topics(), spec), description.producers(), description.groups()));

            var topic = new TopicComponent(spec);
            declare(
                new State(current.phase(), current.lastError(), append(current.topics(), topic), current.producers()));
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
            var current = declarableState();
            var description = describe(current);
            validate(
                new SimulatorSpec(description.topics(), append(description.producers(), spec), description.groups()));

            var identified = spec.id().isEmpty()
                ? new ProducerSpec(nextProducerId(current), spec.topics(), spec.ratePerPartition())
                : spec;
            var targets = current.topics().stream()
                .map(TopicComponent::spec)
                .filter(topic -> identified.topics().contains(topic.name()))
                .toList();
            var producer = new ProducerRunner(identified, targets, publisher::poke);
            declare(new State(current.phase(), current.lastError(), current.topics(),
                append(current.producers(), producer)));
            return producer;
        } finally {
            structureLock.unlock();
        }
    }

    /** Components are only declared while the simulator is idle. */
    private State declarableState() {
        var current = state;
        if (current.phase() != Phase.IDLE) {
            throw new SimulationStateException("Components cannot be added while the simulator is " + current.phase());
        }
        return current;
    }

    private void declare(State newState) {
        state = newState;
        publisher.start();
        publisher.poke();
    }

    /** The first {@code producer-N} that no producer has. The numbers only grow until the stop. */
    private String nextProducerId(State current) {
        while (true) {
            var id = "producer-" + nextProducerId++;
            if (current.producers().stream().noneMatch(producer -> producer.id().equals(id))) {
                return id;
            }
        }
    }

    /**
     * Starts the proxy, then the topics, then the producers, and returns when all of them run. A
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
            try {
                startComponents(current, starting);
            } catch (Throwable e) {
                var failure = e instanceof SimulationException known
                    ? known
                    : new SimulationException("The simulator failed to start: " + e, e);
                LOG.warn("The start failed, undoing it: {}", failure.getMessage());
                starting.close(failure);
                state = current.withPhase(Phase.IDLE, failure.getMessage());
                publisher.flush();
                throw failure;
            }
            resources = starting;
            state = current.withPhase(Phase.RUNNING, "");
            publisher.flush();
        } finally {
            structureLock.unlock();
        }
    }

    private void startComponents(State current, Resources starting) {
        var clients = new ClientFactory(bootstrapServers, clientProperties);

        var admin = clients.createAdmin(Threads.PREFIX + "admin");
        starting.register("admin client", () -> admin.close(ADMIN_CLOSE_TIMEOUT));

        try {
            var proxy =
                new ProxyLauncher(bootstrapServers, proxyPort, PROXY_LAUNCH_TIMEOUT).launch(admin, new NetworkFaults());
            starting.register("proxy", proxy::close);
        } catch (IllegalStateException e) {
            throw new SimulationException("The proxy failed to start: " + e.getMessage(), e);
        }

        var topicManager = new TopicManager(admin, ADMIN_TIMEOUT);
        for (var topic : current.topics()) {
            var component = starting.child("topic " + topic.name());
            topicManager.create(topic.spec());
            component.register("topic " + topic.name(), () -> topicManager.delete(topic.name()));
            topicManager.awaitCreated(topic.name());
        }

        for (var producer : current.producers()) {
            var component = starting.child("producer " + producer.id());
            Supplier<Producer<String, String>> client =
                () -> clients.createProducer(Threads.PREFIX + producer.threadName());
            producer.start(client, threads, component);
        }
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
            var running = resources;
            if (running != null) {
                lastError = "";
                state = state.withPhase(Phase.STOPPING, "");
                publisher.flush();
                try {
                    running.close();
                } catch (SimulationException e) {
                    failure = e;
                }
                resources = null;
            }
            state = State.EMPTY.withPhase(Phase.IDLE, failure == null ? lastError : failure.getMessage());
            nextProducerId = 0;
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
            List.of()
        );
    }

    /** Called by the publisher thread. */
    private SimulatorSnapshot assemble() {
        var current = state;
        return new SimulatorSnapshot(
            0,
            current.phase(),
            false,
            current.lastError(),
            current.topics().stream().map(TopicComponent::spec).toList(),
            current.producers().stream().map(ProducerRunner::snapshot).toList(),
            List.of()
        );
    }

    private static void validate(SimulatorSpec spec) {
        var violations = ConfigValidator.validate(spec);
        if (!violations.isEmpty()) {
            throw new InvalidConfigException(violations);
        }
    }

    private static <T> List<T> append(List<? extends T> list, T element) {
        var appended = new ArrayList<T>(list);
        appended.add(element);
        return List.copyOf(appended);
    }
}
