package io.github.dajac.kaos.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import io.github.dajac.kaos.ConsumerGroup;
import io.github.dajac.kaos.ConsumerHandle;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.Phase;
import io.github.dajac.kaos.ProducerHandle;
import io.github.dajac.kaos.ProducerSpec;
import io.github.dajac.kaos.Protocol;
import io.github.dajac.kaos.SimulationStateException;
import io.github.dajac.kaos.Simulator;
import io.github.dajac.kaos.SimulatorSnapshot;
import io.github.dajac.kaos.SimulatorSpec;
import io.github.dajac.kaos.Topic;
import io.github.dajac.kaos.TopicSpec;
import io.github.dajac.kaos.Violation;
import io.github.dajac.kaos.server.dto.AutopilotRequest;
import io.github.dajac.kaos.server.dto.SimulatorResponse;
import io.github.dajac.kaos.server.dto.StatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the one {@link Simulator} of the server and maps the operations of the HTTP API onto it.
 *
 * <p>The blocking operations (start, stop, every add and remove, and the protocol switch) run one at a
 * time on a single {@code kaos-lifecycle} thread: the request validates what it can, submits the
 * operation and returns at once. A failed operation surfaces as the {@code lastError} of the
 * {@link StatusResponse}, which an operation clears when it begins. The other operations (faults,
 * join and leave, pause and resume, the autopilot) only flip flags, so they run on the request
 * thread.
 *
 * <p>The requests are checked before they are accepted: a spec with a violation is a 400, an unknown
 * component a 404, and the wrong phase or a topic in use a 409. The dynamic operations need a
 * running simulator. The phase of the status is {@code STARTING} as soon as a start is accepted and
 * {@code STOPPING} as soon as a stop is, so that a client sees the accepted operation at once.
 */
final class SimulationService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SimulationService.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private enum Lifecycle { START, STOP }

    /** A start or a stop that is accepted and not finished yet. */
    private record Pending(Lifecycle lifecycle) {}

    private final Simulator simulator;
    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final ThreadPoolExecutor lifecycle;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Pending pending;
    private volatile String lastError = "";
    private volatile Runnable onChange = () -> {};

    SimulationService(Simulator simulator) {
        this.simulator = simulator;
        this.lifecycle = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue, runnable -> {
            var thread = new Thread(runnable, "kaos-lifecycle");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Calls {@code listener} whenever the snapshot or the status may have changed. The listener must
     * return quickly; it runs on the publisher thread of the simulator too.
     */
    void onChange(Runnable listener) {
        onChange = listener;
        simulator.addListener(snapshot -> listener.run());
    }

    SimulatorSnapshot snapshot() {
        return simulator.snapshot();
    }

    StatusResponse status() {
        return new StatusResponse(phase(), lastError, warnings());
    }

    SimulatorResponse describe() {
        return new SimulatorResponse(phase(), simulator.describe(), lastError, warnings());
    }

    private Phase phase() {
        var current = pending;
        var phase = simulator.snapshot().phase();
        if (current != null && current.lifecycle() == Lifecycle.START && phase == Phase.IDLE) {
            return Phase.STARTING;
        }
        if (current != null && current.lifecycle() == Lifecycle.STOP && phase != Phase.IDLE) {
            return Phase.STOPPING;
        }
        return phase;
    }

    private List<String> warnings() {
        return simulator.groups().stream().flatMap(group -> group.warnings().stream()).toList();
    }

    // Lifecycle

    /**
     * Accepts the start of a whole simulation: its components are declared and started on the
     * lifecycle thread. A start that fails removes them again.
     *
     * @throws io.github.dajac.kaos.InvalidConfigException if the spec is invalid
     * @throws ApiException 409 if the simulator is not idle
     */
    StatusResponse start(SimulatorSpec spec) {
        Simulator.validate(spec);
        var token = new Pending(Lifecycle.START);
        lock.lock();
        try {
            if (pending != null || simulator.snapshot().phase() != Phase.IDLE) {
                throw ApiException.conflict("The simulator is not idle; stop it first");
            }
            pending = token;
        } finally {
            lock.unlock();
        }
        submit(token, "Start", () -> {
            try {
                spec.topics().forEach(simulator::addTopic);
                spec.producers().forEach(simulator::addProducer);
                spec.groups().forEach(simulator::addGroup);
                simulator.start();
            } catch (RuntimeException e) {
                // The failed start keeps its components declared; they are removed so that the next
                // start begins from an empty simulator. The stop keeps the lastError of the core.
                try {
                    simulator.stop();
                } catch (RuntimeException stopFailure) {
                    e.addSuppressed(stopFailure);
                }
                throw e;
            }
        });
        return status();
    }

    /**
     * Accepts the stop of the simulation, which removes every component.
     *
     * @return false if there is nothing to stop
     */
    boolean stop() {
        var token = new Pending(Lifecycle.STOP);
        lock.lock();
        try {
            var current = pending;
            if (current != null && current.lifecycle() == Lifecycle.STOP) {
                return true;
            }
            if (current == null && simulator.snapshot().phase() == Phase.IDLE && isEmpty(simulator.describe())) {
                return false;
            }
            pending = token;
        } finally {
            lock.unlock();
        }
        submit(token, "Stop", simulator::stop);
        return true;
    }

    // Topics

    void addTopic(TopicSpec spec) {
        requireRunning();
        var current = simulator.describe();
        Simulator.validate(new SimulatorSpec(append(current.topics(), spec), current.producers(), current.groups()));
        submit("Add topic " + spec.name(), () -> simulator.addTopic(spec));
    }

    void removeTopic(String name) {
        requireRunning();
        var topic = topic(name);
        var current = simulator.describe();
        var users = new ArrayList<String>();
        current.producers().stream().filter(p -> p.topics().contains(name))
            .forEach(p -> users.add("producer " + p.id()));
        current.groups().stream().filter(g -> g.subscribedTopics().contains(name))
            .forEach(g -> users.add("group " + g.id()));
        if (!users.isEmpty()) {
            throw ApiException.conflict("The topic " + name + " is in use by " + String.join(", ", users));
        }
        submit("Remove topic " + name, topic::remove);
    }

    // Producers

    void addProducer(ProducerSpec spec) {
        requireRunning();
        var current = simulator.describe();
        Simulator.validate(new SimulatorSpec(current.topics(), append(current.producers(), spec), current.groups()));
        submit("Add producer " + spec.id(), () -> simulator.addProducer(spec));
    }

    void removeProducer(String id) {
        requireRunning();
        var producer = producer(id);
        submit("Remove producer " + id, producer::remove);
    }

    void setProducerPaused(String id, boolean paused) {
        requireRunning();
        var producer = producer(id);
        if (paused) {
            producer.pause();
        } else {
            producer.resume();
        }
    }

    // Groups

    void addGroup(GroupSpec spec) {
        requireRunning();
        var current = simulator.describe();
        Simulator.validate(new SimulatorSpec(current.topics(), current.producers(), append(current.groups(), spec)));
        submit("Add group " + spec.id(), () -> simulator.addGroup(spec));
    }

    void removeGroup(String groupId) {
        requireRunning();
        var group = group(groupId);
        submit("Remove group " + groupId, group::remove);
    }

    void setAutopilot(String groupId, AutopilotRequest request) {
        requireRunning();
        var group = group(groupId);
        if (request.enabled() == null) {
            throw ApiException.badRequest("enabled", "is required");
        }
        if (!request.enabled()) {
            group.autopilot().stop();
            return;
        }
        var current = group.snapshot().autopilot();
        var min = request.minIntervalMs() == null ? current.minIntervalMs() : request.minIntervalMs();
        var max = request.maxIntervalMs() == null ? current.maxIntervalMs() : request.maxIntervalMs();
        var violations = new ArrayList<Violation>();
        if (min < 1) {
            violations.add(new Violation("minIntervalMs", "must be at least 1"));
        }
        if (max < min) {
            violations.add(new Violation("maxIntervalMs", "must be at least minIntervalMs (" + min + ")"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.badRequest("Invalid autopilot intervals", violations);
        }
        group.autopilot().start(Duration.ofMillis(min), Duration.ofMillis(max));
    }

    // Consumers

    void addConsumer(String groupId, ConsumerSpec spec) {
        requireRunning();
        var group = group(groupId);
        var current = simulator.describe();
        var groups = current.groups().stream()
            .map(g -> g.id().equals(groupId) ? withConsumer(g, spec) : g)
            .toList();
        Simulator.validate(new SimulatorSpec(current.topics(), current.producers(), groups));
        submit("Add consumer to group " + groupId, () -> group.addConsumer(spec));
    }

    void removeConsumer(String groupId, String id) {
        requireRunning();
        var consumer = consumer(groupId, id);
        submit("Remove consumer " + consumer.clientId(), consumer::remove);
    }

    void inject(String groupId, String id, String faultId) {
        requireRunning();
        consumer(groupId, id).inject(fault(faultId));
    }

    void clear(String groupId, String id, String faultId) {
        requireRunning();
        consumer(groupId, id).clear(fault(faultId));
    }

    void clearFaults(String groupId, String id) {
        requireRunning();
        consumer(groupId, id).clearFaults();
    }

    void join(String groupId, String id) {
        requireRunning();
        consumer(groupId, id).join();
    }

    void leave(String groupId, String id) {
        requireRunning();
        consumer(groupId, id).leave();
    }

    void switchProtocol(String groupId, String id, Protocol protocol) {
        requireRunning();
        var consumer = consumer(groupId, id);
        submit("Switch " + consumer.clientId() + " to " + protocol.id(), () -> consumer.switchProtocol(protocol));
    }

    /**
     * Drops the operations that have not begun, then closes the simulator, which waits for a start in
     * progress and stops what it started.
     */
    @Override
    public void close() {
        lifecycle.shutdown();
        queue.clear();
        try {
            simulator.close();
        } finally {
            try {
                if (!lifecycle.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    LOG.warn("The lifecycle thread did not end within {}", CLOSE_TIMEOUT);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Helpers

    private void requireRunning() {
        var current = pending;
        if (current != null || simulator.snapshot().phase() != Phase.RUNNING) {
            throw ApiException.conflict("The simulator is not running");
        }
    }

    private Topic topic(String name) {
        return find(simulator.topics(), Topic::name, name, "topic");
    }

    private ProducerHandle producer(String id) {
        return find(simulator.producers(), ProducerHandle::id, id, "producer");
    }

    private ConsumerGroup group(String groupId) {
        return find(simulator.groups(), ConsumerGroup::id, groupId, "group");
    }

    private ConsumerHandle consumer(String groupId, String id) {
        var consumers = group(groupId).consumers();
        return find(consumers, ConsumerHandle::id, id, "consumer in group " + groupId + " with the id");
    }

    private static Fault fault(String id) {
        return Fault.all().stream()
            .filter(fault -> fault.id().equals(id))
            .findFirst()
            .orElseThrow(() -> ApiException.notFound("There is no fault " + id));
    }

    private static <T> T find(List<T> items, Function<T, String> key, String value, String kind) {
        return items.stream()
            .filter(item -> key.apply(item).equals(value))
            .findFirst()
            .orElseThrow(() -> ApiException.notFound("There is no " + kind + " " + value));
    }

    private static GroupSpec withConsumer(GroupSpec group, ConsumerSpec consumer) {
        return new GroupSpec(
            group.id(),
            group.subscribedTopics(),
            group.sessionTimeoutMs(),
            group.heartbeatIntervalMs(),
            group.assignmentIntervalMs(),
            group.consumerDefaults(),
            append(group.consumers(), consumer)
        );
    }

    private static <T> List<T> append(List<T> list, T item) {
        var copy = new ArrayList<T>(list);
        copy.add(item);
        return copy;
    }

    private static boolean isEmpty(SimulatorSpec spec) {
        return spec.topics().isEmpty() && spec.producers().isEmpty() && spec.groups().isEmpty();
    }

    /**
     * Submits a dynamic operation. The phase check and the submission happen under {@code lock}, like
     * the acceptance of a start or a stop, so the queue order matches the order in which the requests
     * were accepted. The operation also checks the phase again when it begins, and does nothing but
     * set the lastError if the simulator no longer runs: the core would otherwise declare the
     * component on an idle simulator.
     */
    void submit(String description, Runnable operation) {
        lock.lock();
        try {
            requireRunning();
            submit(null, description, () -> {
                if (simulator.snapshot().phase() != Phase.RUNNING) {
                    throw new SimulationStateException(description + " did not run: the simulator is not running");
                }
                operation.run();
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs the operation on the lifecycle thread. It clears the lastError when it begins and sets it
     * if it fails; a start or a stop also clears its pending token when it ends.
     */
    private void submit(Pending token, String description, Runnable operation) {
        lifecycle.execute(() -> {
            lastError = "";
            onChange.run();
            try {
                operation.run();
            } catch (RuntimeException e) {
                LOG.warn("{} failed", description, e);
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (token != null) {
                    lock.lock();
                    try {
                        if (pending == token) {
                            pending = null;
                        }
                    } finally {
                        lock.unlock();
                    }
                }
                onChange.run();
            }
        });
        onChange.run();
    }
}
