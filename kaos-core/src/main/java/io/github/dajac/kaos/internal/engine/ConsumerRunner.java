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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.ConsumerHandle;
import io.github.dajac.kaos.ConsumerSettings;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.Protocol;
import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.SimulationStateException;
import io.github.dajac.kaos.internal.config.Assignors;
import io.github.dajac.kaos.internal.proxy.NetworkFaults;
import io.github.dajac.kaos.internal.state.ConsumerLocalState;
import io.github.dajac.kaos.internal.state.EventLog;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.FencedInstanceIdException;
import org.apache.kafka.common.errors.GroupMaxSizeReachedException;
import org.apache.kafka.common.errors.InconsistentGroupProtocolException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.InvalidGroupIdException;
import org.apache.kafka.common.errors.InvalidSessionTimeoutException;
import org.apache.kafka.common.errors.UnreleasedInstanceIdException;
import org.apache.kafka.common.errors.UnsupportedAssignorException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simulated consumer: one thread, {@code kaos-<groupId>-<id>}, which is the only one to use its
 * Kafka consumer. The consumer connects through the proxy with the client id
 * {@code <groupId>-<id>}.
 *
 * <p>The handle methods only flip flags that the loop reads, and signal the loop: they unpark its
 * thread and call {@code wakeup()} on its consumer. The loop waits with {@code park}, so a flag and
 * a stop take effect at once. {@link #switchProtocol} is the exception: it stops the loop and
 * starts a new one, with a new Kafka consumer, under the {@code switchLock}, which the start and
 * the stop of the runner take as well. {@link #remove()} goes through the group, which stops the
 * runner under the structure lock of the simulator.
 *
 * <p>A poll that fails is logged to {@code kaos.events} and the loop goes on. An error that the
 * Kafka consumer does not recover from ends the loop and becomes the {@code failure} of the
 * consumer.
 */
public final class ConsumerRunner implements ConsumerHandle {

    /** What a consumer needs from its group at any time. */
    public interface Owner {
        /** Something the snapshot shows has changed. */
        void changed();

        /** Throws on a thread that must not block, such as the one that runs the listeners. */
        void refuseBlocking();

        /** Removes the consumer from the group. */
        void remove(ConsumerRunner consumer);
    }

    /**
     * What a running consumer needs from its simulator.
     *
     * @param clients creates the Kafka consumer for the given settings
     * @param threads creates the loop thread
     * @param network the faults of the proxy the consumers connect through
     */
    public record Services(
        Function<Map<String, Object>, Consumer<String, String>> clients,
        Threads threads,
        NetworkFaults network
    ) {
        public Services {
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(threads, "threads");
            Objects.requireNonNull(network, "network");
        }
    }

    /**
     * The session timeout and heartbeat interval of a member that speaks the classic protocol. Such
     * a member takes them from its client, not from the group configs, so the runner sets those of
     * its group on the client, their defaults included: a classic client gets the same values as a
     * member of the consumer protocol, 45 s and 5 s unless the group sets others. The client
     * validates them, as the broker validates the group configs.
     *
     * @param sessionTimeoutMs the session timeout of the group
     * @param heartbeatIntervalMs the heartbeat interval of the group
     */
    public record ClassicTimeouts(int sessionTimeoutMs, int heartbeatIntervalMs) {
        /** The settings of a group. */
        public static ClassicTimeouts of(GroupSpec group) {
            return new ClassicTimeouts(group.sessionTimeoutMs(), group.heartbeatIntervalMs());
        }
    }

    /** One run of the loop: a thread and the Kafka consumer it owns. */
    private static final class Loop {
        private final Consumer<String, String> consumer;
        private volatile Thread thread;
        private volatile boolean running = true;

        private Loop(Consumer<String, String> consumer) {
            this.consumer = consumer;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerRunner.class);
    private static final Logger EVENTS = LoggerFactory.getLogger(EventLog.LOGGER_NAME);

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STOP_TIMEOUT = CLOSE_TIMEOUT.plusSeconds(5);
    /** The shortest pause after a poll that failed, so that a failing consumer does not spin. */
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(1);

    private final String groupId;
    private final ConsumerSpec spec;
    private final List<String> topics;
    private final ClassicTimeouts timeouts;
    private final Owner owner;
    private final String clientId;

    private final ReentrantLock switchLock = new ReentrantLock();
    private final AtomicBoolean subscribed = new AtomicBoolean(true);
    private final Set<Fault> faults = ConcurrentHashMap.newKeySet();
    private final AtomicLong signals = new AtomicLong();
    private final AtomicInteger generation = new AtomicInteger(-1);
    private final Set<PartitionRef> localAssignment = ConcurrentHashMap.newKeySet();

    private volatile Protocol protocol;
    private volatile String failure = "";
    private volatile boolean removed;
    private volatile Loop current;
    /** Set while the runner is started; it is how a fault reaches the proxy. */
    private volatile NetworkFaults network;

    // Guarded by the switchLock.
    private Services services;

    /**
     * @param groupId the group of the consumer
     * @param spec the description of the consumer, with its id assigned and its effective settings
     * @param topics the names of the topics the consumer subscribes to
     * @param timeouts the settings of the group the consumer sets on its client with the classic
     *     protocol
     * @param owner the group; it is told, from any thread, when what the snapshot shows of the
     *     consumer changed
     */
    public ConsumerRunner(
        String groupId,
        ConsumerSpec spec,
        List<String> topics,
        ClassicTimeouts timeouts,
        Owner owner
    ) {
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.topics = List.copyOf(topics);
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.clientId = clientId(groupId, spec.id());
        this.protocol = spec.effective().protocol();
    }

    /** The client id of a consumer. The network faults match on it. */
    public static String clientId(String groupId, String consumerId) {
        return groupId + "-" + consumerId;
    }

    public ConsumerSpec spec() {
        return spec;
    }

    @Override
    public String id() {
        return spec.id();
    }

    @Override
    public String clientId() {
        return clientId;
    }

    @Override
    public Protocol protocol() {
        return protocol;
    }

    @Override
    public boolean subscribed() {
        return subscribed.get();
    }

    /** The {@code group.instance.id} of the consumer; empty for a dynamic member. */
    public String instanceId() {
        return spec.effective().staticMember() ? clientId : "";
    }

    @Override
    public void inject(Fault fault) {
        Objects.requireNonNull(fault, "fault");
        checkNotRemoved();
        if (faults.add(fault)) {
            faultsChanged();
        }
    }

    @Override
    public void clear(Fault fault) {
        Objects.requireNonNull(fault, "fault");
        checkNotRemoved();
        if (faults.remove(fault)) {
            faultsChanged();
        }
    }

    @Override
    public void clearFaults() {
        checkNotRemoved();
        var changed = false;
        for (var fault : Fault.all()) {
            changed |= faults.remove(fault);
        }
        if (changed) {
            faultsChanged();
        }
    }

    @Override
    public Set<Fault> faults() {
        var active = new LinkedHashSet<Fault>();
        for (var fault : Fault.all()) {
            if (faults.contains(fault)) {
                active.add(fault);
            }
        }
        return Collections.unmodifiableSet(active);
    }

    @Override
    public void join() {
        checkNotRemoved();
        if (subscribed.compareAndSet(false, true)) {
            signal();
            owner.changed();
        }
    }

    @Override
    public void leave() {
        checkNotRemoved();
        if (subscribed.compareAndSet(true, false)) {
            signal();
            owner.changed();
        }
    }

    private void faultsChanged() {
        applyNetworkFaults();
        signal();
        owner.changed();
    }

    /**
     * Brings the proxy in line with the network faults of the consumer. The isolation wins over
     * the connection reset. The short monitor orders the concurrent calls, so that the last change
     * of the fault set is the one the proxy ends up with.
     */
    private synchronized void applyNetworkFaults() {
        var target = network;
        if (target == null) {
            return;
        }
        if (faults.contains(Fault.networkIsolation())) {
            target.set(clientId, NetworkFaults.Mode.BLACKHOLE);
        } else if (faults.contains(Fault.connectionReset())) {
            target.set(clientId, NetworkFaults.Mode.RESET);
        } else {
            target.clear(clientId);
        }
    }

    /**
     * Detaches the runner from the proxy and clears its fault there, under the monitor of
     * {@link #applyNetworkFaults()}: a fault injected concurrently either comes before and is
     * cleared, or comes after and finds no proxy.
     */
    private synchronized void forgetNetworkFaults(NetworkFaults proxy) {
        network = null;
        proxy.clear(clientId);
    }

    /** Makes the loop read the flags again, wherever it waits. */
    private void signal() {
        signals.incrementAndGet();
        var loop = current;
        if (loop != null) {
            LockSupport.unpark(loop.thread);
            loop.consumer.wakeup();
        }
    }

    /**
     * Creates the Kafka consumer and starts the loop. Closing {@code resources} stops the loop,
     * which closes the Kafka consumer and so leaves the group.
     */
    public void start(Services services, Resources resources) {
        Objects.requireNonNull(services, "services");
        switchLock.lock();
        try {
            this.services = services;
            this.network = services.network();
            try {
                startLoop();
            } catch (Throwable e) {
                this.services = null;
                this.network = null;
                throw e;
            }
            resources.register("consumer " + clientId, this::stop);
        } finally {
            switchLock.unlock();
        }
    }

    /** Asks the loop to end, without waiting for it. {@link #stop()} waits. */
    public void requestStop() {
        var loop = current;
        if (loop != null) {
            loop.running = false;
            signal();
        }
    }

    /** Stops the loop and waits until its Kafka consumer is closed. Idempotent. */
    public void stop() throws InterruptedException {
        switchLock.lockInterruptibly();
        try {
            if (services == null) {
                return;
            }
            try {
                stopLoop();
            } finally {
                forgetNetworkFaults(services.network());
                services = null;
                owner.changed();
            }
        } finally {
            switchLock.unlock();
        }
    }

    @Override
    public void switchProtocol(Protocol newProtocol) {
        Objects.requireNonNull(newProtocol, "newProtocol");
        owner.refuseBlocking();
        switchLock.lock();
        try {
            checkNotRemoved();
            if (newProtocol == protocol) {
                return;
            }
            if (services == null) {
                protocol = newProtocol;
                owner.changed();
                return;
            }
            try {
                stopLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SimulationException("Interrupted while stopping consumer " + clientId, e);
            }
            protocol = newProtocol;
            try {
                startLoop();
            } catch (RuntimeException e) {
                failure = describe(e);
                owner.changed();
                throw new SimulationException(
                    "Consumer " + clientId + " could not switch to the " + newProtocol.id() + " protocol: " + e,
                    e
                );
            }
        } finally {
            switchLock.unlock();
        }
    }

    @Override
    public void remove() {
        owner.remove(this);
    }

    /** Called by the group once the consumer is removed. */
    public void markRemoved() {
        removed = true;
    }

    /** @throws SimulationStateException if the consumer is removed */
    public void checkNotRemoved() {
        if (removed) {
            throw new SimulationStateException("The consumer " + clientId + " is removed");
        }
    }

    /** The caller holds the switchLock. */
    private void startLoop() {
        var properties = clientProperties(groupId, clientId, spec.effective(), timeouts, protocol);
        var consumer = services.clients().apply(properties);
        var loop = new Loop(consumer);
        try {
            var thread = services.threads().newThread(groupId + "-" + spec.id(), () -> run(loop));
            loop.thread = thread;
            failure = "";
            current = loop;
            applyNetworkFaults();
            thread.start();
        } catch (Throwable e) {
            current = null;
            closeQuietly(consumer);
            throw e;
        }
        owner.changed();
    }

    /** The caller holds the switchLock. */
    private void stopLoop() throws InterruptedException {
        var loop = current;
        if (loop == null) {
            return;
        }
        loop.running = false;
        signal();
        var thread = loop.thread;
        try {
            if (!thread.join(STOP_TIMEOUT)) {
                // The loop is blocked in the Kafka consumer, whose blocking calls are interruptible.
                thread.interrupt();
                if (!thread.join(STOP_TIMEOUT)) {
                    throw new SimulationException(
                        "The thread " + thread.getName() + " did not stop within " + STOP_TIMEOUT
                    );
                }
            }
        } finally {
            current = null;
        }
    }

    /** The copy of the state of the consumer that goes into the snapshot. */
    public ConsumerLocalState localState() {
        return new ConsumerLocalState(
            spec.id(),
            clientId,
            protocol,
            assignorFor(spec.effective(), protocol),
            subscribed.get(),
            Set.copyOf(faults),
            failure,
            generation.get(),
            Set.copyOf(localAssignment)
        );
    }

    /**
     * The assignor a consumer uses with a protocol. Its assignor is set for the protocol of its
     * settings; with the other protocol it is kept only when that protocol lists the same name.
     */
    static String assignorFor(ConsumerSettings settings, Protocol protocol) {
        if (protocol == settings.protocol()) {
            return settings.assignor();
        }
        var listed = ConfigMetadata.get().assignors(protocol).names();
        return listed.contains(settings.assignor()) ? settings.assignor() : "";
    }

    /**
     * The settings of the Kafka consumer, without those every client of the simulator shares. The
     * session timeout and the heartbeat interval are set with the classic protocol only: the
     * consumer protocol client refuses them, and its member takes them from the group configs.
     */
    static Map<String, Object> clientProperties(
        String groupId,
        String clientId,
        ConsumerSettings settings,
        ClassicTimeouts timeouts,
        Protocol protocol
    ) {
        var properties = new HashMap<String, Object>();
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, settings.maxPollIntervalMs());
        properties.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, protocol.id());
        var assignor = assignorFor(settings, protocol);
        switch (protocol) {
            case CLASSIC -> {
                Assignors.classicAssignorClass(assignor).ifPresent(
                    name -> properties.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, name)
                );
                properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, timeouts.sessionTimeoutMs());
                properties.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, timeouts.heartbeatIntervalMs());
            }
            case CONSUMER -> Assignors.remoteAssignor(assignor).ifPresent(
                name -> properties.put(ConsumerConfig.GROUP_REMOTE_ASSIGNOR_CONFIG, name)
            );
        }
        if (settings.staticMember()) {
            properties.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, clientId);
        }
        if (!settings.rackId().isEmpty()) {
            properties.put(ConsumerConfig.CLIENT_RACK_CONFIG, settings.rackId());
        }
        return properties;
    }

    private void run(Loop loop) {
        try {
            loop(loop);
        } catch (Throwable e) {
            // What a stop makes the Kafka consumer throw is not a failure of the consumer.
            if (loop.running) {
                fail(e);
            }
        } finally {
            // A stop that had to interrupt the loop still lets the consumer leave the group.
            Thread.interrupted();
            closeQuietly(loop.consumer);
            localAssignment.clear();
            generation.set(-1);
            owner.changed();
        }
    }

    private void fail(Throwable e) {
        // The change log reports the failure from the snapshot.
        failure = describe(e);
        LOG.error("Consumer {} stopped on an unrecoverable error", clientId, e);
    }

    private static String describe(Throwable e) {
        var message = e.getMessage();
        var name = e.getClass().getSimpleName();
        return message == null || message.isBlank() ? name : name + ": " + message;
    }

    private void loop(Loop loop) {
        var consumer = loop.consumer;
        var listener = new AssignmentListener();
        var settings = spec.effective();
        var pollTimeout = Duration.ofMillis(settings.pollTimeoutMs());
        var pollDelay = Duration.ofMillis(settings.pollDelayMs());
        var inGroup = false;

        while (loop.running) {
            var seen = signals.get();
            var desired = subscribed.get();
            if (desired && !inGroup) {
                consumer.subscribe(topics, listener);
                inGroup = true;
            } else if (!desired && inGroup) {
                inGroup = !unsubscribe(consumer);
            }

            if (faults.contains(Fault.freeze()) || !inGroup) {
                await(loop, seen, Long.MAX_VALUE);
                continue;
            }

            var pause = pollDelay;
            try {
                consumer.poll(pollTimeout);
            } catch (WakeupException e) {
                // A flag changed; it is read again at once.
                continue;
            } catch (KafkaException e) {
                if (!loop.running || isUnrecoverable(e)) {
                    throw e;
                }
                EVENTS.warn("[{}] {} poll failed: {}", groupId, spec.id(), describe(e));
                pause = pollDelay.compareTo(ERROR_BACKOFF) < 0 ? ERROR_BACKOFF : pollDelay;
            }
            reportGeneration(consumer);
            await(loop, seen, pause.toNanos());
        }
    }

    /** Returns whether the consumer is unsubscribed; when it is not, the next iteration retries. */
    private boolean unsubscribe(Consumer<String, String> consumer) {
        try {
            consumer.unsubscribe();
        } catch (WakeupException e) {
            return false;
        } catch (InterruptException e) {
            throw e;
        } catch (KafkaException e) {
            EVENTS.warn("[{}] {} could not unsubscribe: {}", groupId, spec.id(), describe(e));
            return false;
        }
        localAssignment.clear();
        generation.set(-1);
        owner.changed();
        return true;
    }

    private void reportGeneration(Consumer<String, String> consumer) {
        var reported = consumer.groupMetadata().generationId();
        if (generation.getAndSet(reported) != reported) {
            owner.changed();
        }
    }

    /** Waits until the loop is signalled after {@code seen}, is stopped, or the time is over. */
    private void await(Loop loop, long seen, long nanos) {
        var deadline = nanos == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + nanos;
        while (loop.running && signals.get() == seen && !Thread.currentThread().isInterrupted()) {
            if (deadline == Long.MAX_VALUE) {
                LockSupport.park(this);
            } else {
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                LockSupport.parkNanos(this, remaining);
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptException("The loop of " + clientId + " was interrupted");
        }
    }

    /** The errors after which a Kafka consumer stays out of its group whatever is polled. */
    static boolean isUnrecoverable(KafkaException e) {
        return e instanceof FencedInstanceIdException
            || e instanceof UnreleasedInstanceIdException
            || e instanceof InconsistentGroupProtocolException
            || e instanceof UnsupportedAssignorException
            || e instanceof UnsupportedVersionException
            || e instanceof GroupMaxSizeReachedException
            || e instanceof InvalidGroupIdException
            || e instanceof InvalidSessionTimeoutException
            || e instanceof AuthenticationException
            || e instanceof AuthorizationException
            || e instanceof InterruptException;
    }

    private void closeQuietly(Consumer<String, String> consumer) {
        try {
            consumer.close(
                CloseOptions.timeout(CLOSE_TIMEOUT)
                    .withGroupMembershipOperation(CloseOptions.GroupMembershipOperation.LEAVE_GROUP)
            );
        } catch (RuntimeException e) {
            LOG.warn("Could not close consumer {}", clientId, e);
        }
    }

    private final class AssignmentListener implements ConsumerRebalanceListener {
        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            partitions.forEach(partition -> localAssignment.add(ref(partition)));
            owner.changed();
        }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            partitions.forEach(partition -> localAssignment.remove(ref(partition)));
            owner.changed();
        }

        @Override
        public void onPartitionsLost(Collection<TopicPartition> partitions) {
            onPartitionsRevoked(partitions);
        }

        private static PartitionRef ref(TopicPartition partition) {
            return new PartitionRef(partition.topic(), partition.partition());
        }
    }
}
