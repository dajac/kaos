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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.github.dajac.kaos.test.IntegrationTest;
import io.github.dajac.kaos.test.KafkaCluster;
import io.github.dajac.kaos.test.TestThreads;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The lifecycle of a simulator with topics and producers, against a real broker. */
@IntegrationTest
class SimulatorLifecycleTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** Netty's global executor thread outlives the proxy's event loops by about a second. */
    private static final Duration THREADS_GONE = Duration.ofSeconds(15);
    private static final Duration QUIET = Duration.ofSeconds(1);

    private KafkaCluster cluster;
    private Admin admin;
    private Set<Thread> threadsBefore;

    @BeforeEach
    void setUp(KafkaCluster cluster) {
        this.cluster = cluster;
        this.admin = cluster.createAdmin();
        this.threadsBefore = Thread.getAllStackTraces().keySet();
    }

    @AfterEach
    void tearDown() {
        admin.close();
    }

    @Test
    void startsAndClosesWithTopicsAndAProducer() throws Exception {
        var orders = cluster.uniqueName("orders");
        var payments = cluster.uniqueName("payments");
        var proxyPort = freePortBlock();
        var snapshots = new CopyOnWriteArrayList<SimulatorSnapshot>();

        try (
            var simulator =
                new Simulator(cluster.bootstrapServers()).withProxyPort(proxyPort).withListener(snapshots::add)
        ) {
            simulator.addTopic(orders, 3);
            simulator.addTopic(payments, 2);
            var producer = simulator.addProducer(p -> p.topics(orders, payments).ratePerPartition(20));

            simulator.start();

            assertThat(simulator.snapshot().phase()).isEqualTo(Phase.RUNNING);
            assertThat(simulator.snapshot().lastError()).isEmpty();
            assertThat(simulator.snapshot().topics()).containsExactly(new TopicSpec(orders, 3),
                new TopicSpec(payments, 2));
            assertThat(simulator.snapshot().producers())
                .containsExactly(
                    new ProducerSnapshot("producer-0", List.of(orders, payments), ProducerState.RUNNING, 20));
            assertThat(producer.state()).isEqualTo(ProducerState.RUNNING);
            assertThat(partitionCount(orders)).isEqualTo(3);
            assertThat(partitionCount(payments)).isEqualTo(2);
            // Every partition of every topic receives records.
            await().atMost(TIMEOUT).untilAsserted(() -> {
                assertThat(endOffsets(orders, 3)).allMatch(offset -> offset > 0);
                assertThat(endOffsets(payments, 2)).allMatch(offset -> offset > 0);
            });
            assertThat(isFree(proxyPort)).isFalse();
            assertThat(simulatorThreads()).contains("kaos-publisher", "kaos-producer-producer-0");
        }

        assertThat(topicNames()).doesNotContain(orders, payments);
        assertThat(isFree(proxyPort)).isTrue();
        assertThat(isFree(proxyPort + 1)).isTrue();
        await().atMost(THREADS_GONE).untilAsserted(() -> assertThat(simulatorThreads()).isEmpty());
        assertThat(snapshots).extracting(SimulatorSnapshot::phase)
            .containsSubsequence(Phase.STARTING, Phase.RUNNING, Phase.STOPPING, Phase.IDLE);
        assertThat(snapshots).extracting(SimulatorSnapshot::revision).isSorted().doesNotHaveDuplicates();
        var last = snapshots.get(snapshots.size() - 1);
        assertThat(last.topics()).isEmpty();
        assertThat(last.producers()).isEmpty();
    }

    @Test
    void pausingAProducerStopsTheOffsetsFromAdvancing() {
        var topic = cluster.uniqueName("paused");

        try (var simulator = new Simulator(cluster.bootstrapServers()).withProxyPort(freePortBlock())) {
            simulator.addTopic(topic, 2);
            var producer = simulator.addProducer(p -> p.topics(topic).ratePerPartition(50));
            simulator.start();
            await().atMost(TIMEOUT).until(() -> totalOffset(topic, 2) > 0);

            producer.pause();

            simulator.awaitSnapshot(s -> s.producers().get(0).state() == ProducerState.PAUSED, TIMEOUT);
            var paused = awaitSteadyOffset(topic, 2);
            // At 50 records per second and per partition, a producer that runs is seen at once.
            await().during(QUIET.multipliedBy(2)).atMost(TIMEOUT).until(() -> totalOffset(topic, 2) == paused);

            producer.resume();

            simulator.awaitSnapshot(s -> s.producers().get(0).state() == ProducerState.RUNNING, TIMEOUT);
            await().atMost(TIMEOUT).until(() -> totalOffset(topic, 2) > paused);
        }
    }

    @Test
    void aFailedStartLeaksNothingAndIsSeenByAListenerAttachedBefore() throws Exception {
        var created = cluster.uniqueName("created");
        var conflicting = cluster.uniqueName("conflicting");
        admin.createTopics(List.of(new NewTopic(conflicting, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        cluster.awaitTopic(admin, conflicting);
        var proxyPort = freePortBlock();
        var snapshots = new CopyOnWriteArrayList<SimulatorSnapshot>();

        try (
            var simulator =
                new Simulator(cluster.bootstrapServers()).withProxyPort(proxyPort).withListener(snapshots::add)
        ) {
            simulator.addTopic(created, 3);
            // The simulator only creates the topics it owns, so this one fails the start after the
            // proxy and the first topic succeeded.
            simulator.addTopic(conflicting, 3);
            simulator.addProducer(p -> p.topics(created, conflicting));

            var failure = catchThrowable(simulator::start);

            assertThat(failure).isInstanceOf(SimulationException.class).hasMessageContaining(conflicting)
                .hasMessageContaining("already exists");
            assertThat(failure.getSuppressed()).isEmpty();
            // No topic: the one that was created is deleted, the one that was not is left alone.
            assertThat(topicNames()).doesNotContain(created).contains(conflicting);
            assertThat(partitionCount(conflicting)).isEqualTo(1);
            // No port.
            assertThat(isFree(proxyPort)).isTrue();
            assertThat(isFree(proxyPort + 1)).isTrue();
            // No thread: only the publisher of the simulator, which is still open.
            await().atMost(THREADS_GONE)
                .untilAsserted(() -> assertThat(simulatorThreads()).containsExactly("kaos-publisher"));

            // The listener saw STARTING, then IDLE with the failure.
            var last = snapshots.get(snapshots.size() - 1);
            assertThat(snapshots).extracting(SimulatorSnapshot::phase).containsSubsequence(Phase.STARTING, Phase.IDLE);
            assertThat(snapshots).extracting(SimulatorSnapshot::phase).doesNotContain(Phase.RUNNING);
            assertThat(last.phase()).isEqualTo(Phase.IDLE);
            assertThat(last.lastError()).isEqualTo(failure.getMessage());
            assertThat(last.producers()).extracting(ProducerSnapshot::state).containsExactly(ProducerState.STOPPED);
            assertThat(simulator.snapshot()).isEqualTo(last);

            // The components are still declared, so the start can be tried again.
            admin.deleteTopics(List.of(conflicting)).all().get(30, TimeUnit.SECONDS);
            await().atMost(TIMEOUT).until(() -> !topicNames().contains(conflicting));
            simulator.start();

            assertThat(simulator.snapshot().phase()).isEqualTo(Phase.RUNNING);
            assertThat(simulator.snapshot().lastError()).isEmpty();
            assertThat(topicNames()).contains(created, conflicting);
        }

        assertThat(topicNames()).doesNotContain(created, conflicting);
        await().atMost(THREADS_GONE).untilAsserted(() -> assertThat(simulatorThreads()).isEmpty());
    }

    @Test
    void aFailedProducerStartUndoesTheProxyTheTopicsAndTheOtherProducers() throws Exception {
        var orders = cluster.uniqueName("orders");
        var payments = cluster.uniqueName("payments");
        var proxyPort = freePortBlock();
        var snapshots = new CopyOnWriteArrayList<SimulatorSnapshot>();

        // The admin client ignores the property and the Kafka producer refuses it, so the start
        // fails at its last step, with the proxy and both topics to undo.
        try (
            var simulator = new Simulator(cluster.bootstrapServers())
                .withClientProperty("compression.type", "bogus")
                .withProxyPort(proxyPort)
                .withListener(snapshots::add)
        ) {
            simulator.addTopic(orders, 2);
            simulator.addTopic(payments, 2);
            simulator.addProducer(p -> p.topics(orders));
            simulator.addProducer(p -> p.topics(payments));

            var failure = catchThrowable(simulator::start);

            assertThat(failure)
                .isInstanceOf(SimulationException.class)
                .hasMessageContaining("The simulator failed to start")
                .hasMessageContaining("compression.type")
                .hasCauseInstanceOf(KafkaException.class);
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(topicNames()).doesNotContain(orders, payments);
            assertThat(isFree(proxyPort)).isTrue();
            assertThat(isFree(proxyPort + 1)).isTrue();
            await().atMost(THREADS_GONE)
                .untilAsserted(() -> assertThat(simulatorThreads()).containsExactly("kaos-publisher"));
            assertThat(snapshots).extracting(SimulatorSnapshot::phase).containsSubsequence(Phase.STARTING, Phase.IDLE);
            assertThat(snapshots).extracting(SimulatorSnapshot::phase).doesNotContain(Phase.RUNNING);
            assertThat(simulator.snapshot().lastError()).isEqualTo(failure.getMessage());
            assertThat(simulator.snapshot().producers())
                .extracting(ProducerSnapshot::state)
                .containsExactly(ProducerState.STOPPED, ProducerState.STOPPED);

            // Removing the declared components keeps the failure; the next start resets it.
            simulator.stop();
            assertThat(simulator.snapshot().topics()).isEmpty();
            assertThat(simulator.snapshot().lastError()).isEqualTo(failure.getMessage());
            assertThat(snapshots.get(snapshots.size() - 1)).isEqualTo(simulator.snapshot());

            simulator.withClientProperty("compression.type", "none");
            simulator.addTopic(orders, 2);
            simulator.addProducer(p -> p.topics(orders));
            simulator.start();
            assertThat(simulator.snapshot().phase()).isEqualTo(Phase.RUNNING);
            assertThat(simulator.snapshot().lastError()).isEmpty();
        }

        assertThat(topicNames()).doesNotContain(orders, payments);
        await().atMost(THREADS_GONE).untilAsserted(() -> assertThat(simulatorThreads()).isEmpty());
    }

    @Test
    void aStartAgainstAnUnreachableClusterFailsAndLeaksNothing() {
        var proxyPort = freePortBlock();
        var snapshots = new CopyOnWriteArrayList<SimulatorSnapshot>();

        try (var simulator = new Simulator("localhost:1").withProxyPort(proxyPort).withListener(snapshots::add)) {
            simulator.addTopic(cluster.uniqueName("unreachable"), 1);

            assertThatThrownBy(simulator::start)
                .isInstanceOf(SimulationException.class)
                .hasMessageContaining("The proxy failed to start")
                .hasCauseInstanceOf(IllegalStateException.class);

            assertThat(isFree(proxyPort)).isTrue();
            await().atMost(THREADS_GONE)
                .untilAsserted(() -> assertThat(simulatorThreads()).containsExactly("kaos-publisher"));
            assertThat(snapshots).extracting(SimulatorSnapshot::phase).containsSubsequence(Phase.STARTING, Phase.IDLE);
            assertThat(simulator.snapshot().lastError()).contains("The proxy failed to start");
        }

        await().atMost(THREADS_GONE).untilAsserted(() -> assertThat(simulatorThreads()).isEmpty());
    }

    @Test
    void theHarnessIsConfiguredAndDeclaredBeforeTheStart() {
        var topic = cluster.uniqueName("started");

        try (var simulator = new Simulator(cluster.bootstrapServers()).withProxyPort(freePortBlock())) {
            simulator.addTopic(topic, 1);
            simulator.start();

            assertThatThrownBy(() -> simulator.withClientProperty("client.dns.lookup", "use_all_dns_ips"))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> simulator.withProxyPort(19092)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> simulator.withListener(snapshot -> {})).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(simulator::start).isInstanceOf(SimulationStateException.class);

            // A listener can still be added, and removed, while the simulator runs.
            var snapshots = new CopyOnWriteArrayList<SimulatorSnapshot>();
            try (var registration = simulator.addListener(snapshots::add)) {
                assertThat(registration).isNotNull();
                simulator.stop();
            }
            assertThat(snapshots).extracting(SimulatorSnapshot::phase).containsExactly(Phase.STOPPING, Phase.IDLE);

            // Once stopped, the simulator is configured again.
            simulator.withClientProperty("client.dns.lookup", "use_all_dns_ips");
        }
    }

    @Test
    void twoSimulatorsRunInOneJvm() throws Exception {
        var firstTopic = cluster.uniqueName("first");
        var secondTopic = cluster.uniqueName("second");
        var proxyPort = freePortBlock();

        // Both ask for the same proxy port; the second proxy moves to the next free block.
        try (
            var first = new Simulator(cluster.bootstrapServers()).withProxyPort(proxyPort);
            var second = new Simulator(cluster.bootstrapServers()).withProxyPort(proxyPort)
        ) {
            first.addTopic(firstTopic, 1);
            first.addProducer(p -> p.topics(firstTopic).ratePerPartition(20));
            second.addTopic(secondTopic, 1);
            var secondProducer = second.addProducer(p -> p.topics(secondTopic).ratePerPartition(20));
            first.start();
            second.start();
            await().atMost(TIMEOUT).until(() -> totalOffset(firstTopic, 1) > 0 && totalOffset(secondTopic, 1) > 0);

            // Both assigned the same producer id; pausing one leaves the other alone.
            secondProducer.pause();
            second.awaitSnapshot(s -> s.producers().get(0).state() == ProducerState.PAUSED, TIMEOUT);
            assertThat(first.snapshot().producers().get(0).state()).isEqualTo(ProducerState.RUNNING);

            second.close();

            assertThat(topicNames()).contains(firstTopic).doesNotContain(secondTopic);
            assertThat(first.snapshot().phase()).isEqualTo(Phase.RUNNING);
            var offset = totalOffset(firstTopic, 1);
            await().atMost(TIMEOUT).until(() -> totalOffset(firstTopic, 1) > offset);
        }

        assertThat(topicNames()).doesNotContain(firstTopic, secondTopic);
        await().atMost(THREADS_GONE).untilAsserted(() -> assertThat(simulatorThreads()).isEmpty());
    }

    /** Waits until the offsets stay the same for a while: the round in flight at the pause is over. */
    private long awaitSteadyOffset(String topic, int partitions) {
        var last = new AtomicLong(-1);
        await().atMost(TIMEOUT).pollInterval(QUIET).until(() -> {
            var offset = totalOffset(topic, partitions);
            return last.getAndSet(offset) == offset;
        });
        return last.get();
    }

    private long totalOffset(String topic, int partitions) throws Exception {
        return endOffsets(topic, partitions).stream().mapToLong(Long::longValue).sum();
    }

    private List<Long> endOffsets(String topic, int partitions) throws Exception {
        var request = new HashMap<TopicPartition, OffsetSpec>();
        for (int partition = 0; partition < partitions; partition++) {
            request.put(new TopicPartition(topic, partition), OffsetSpec.latest());
        }
        var offsets = admin.listOffsets(request).all().get(30, TimeUnit.SECONDS);
        return offsets.values().stream().map(info -> info.offset()).toList();
    }

    private int partitionCount(String topic) throws Exception {
        return admin.describeTopics(List.of(topic)).allTopicNames().get(30, TimeUnit.SECONDS).get(topic).partitions()
            .size();
    }

    private Set<String> topicNames() throws Exception {
        return admin.listTopics().names().get(30, TimeUnit.SECONDS);
    }

    /**
     * The threads started since the beginning of the test that belong to a simulator: its own
     * threads, its Kafka clients, whose client ids start with {@code kaos-}, and its proxy. The
     * threads of the test are left out: its admin client, and the ones {@link TestThreads} ignores.
     */
    private List<String> simulatorThreads() {
        return TestThreads.startedSince(threadsBefore).stream()
            .filter(name -> !name.startsWith("kafka-") || name.contains("kaos-"))
            .toList();
    }

    /**
     * A port from which two ports are free: the proxy takes one port plus one per broker. It is
     * taken below the ephemeral ranges, away from the outgoing connections of the clients.
     */
    private static int freePortBlock() {
        for (int attempt = 0; attempt < 100; attempt++) {
            var port = ThreadLocalRandom.current().nextInt(20000, 30000);
            if (isFree(port) && isFree(port + 1)) {
                return port;
            }
        }
        throw new IllegalStateException("No free port block found");
    }

    /** Probes a port the way the proxy binds it, on the wildcard and the loopback addresses. */
    private static boolean isFree(int port) {
        return canBind(new InetSocketAddress(port))
            && canBind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    }

    private static boolean canBind(InetSocketAddress address) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(address);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
