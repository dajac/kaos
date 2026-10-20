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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.github.dajac.kaos.test.IntegrationTest;
import io.github.dajac.kaos.test.KafkaCluster;
import io.github.dajac.kaos.test.TestThreads;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Consumer groups and their consumers, driven through the public API against a real broker. */
@IntegrationTest
class ConsumerGroupLifecycleTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** Netty's global executor thread outlives the proxy's event loops by about a second. */
    private static final Duration THREADS_GONE = Duration.ofSeconds(15);
    private static final Duration QUIET = Duration.ofSeconds(2);

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
    void runsTheFullLifecycleOfOneGroup() throws Exception {
        var topic = cluster.uniqueName("orders");
        var groupId = cluster.uniqueName("lifecycle");

        try (var simulator = newSimulator()) {
            simulator.withClientProperty("auto.commit.interval.ms", "500");
            simulator.addTopic(topic, 6);
            simulator.addProducer(p -> p.topics(topic).ratePerPartition(10));
            var group = simulator.addGroup(groupId, g -> g
                .subscribe(topic)
                .heartbeatInterval(Duration.ofSeconds(1))
                .consumerDefaults(c -> c.pollDelay(Duration.ofMillis(100)))
                .consumers(3)
            );

            assertThat(group.snapshot().consumers()).extracting(ConsumerSnapshot::status)
                .containsOnly(ConsumerStatus.JOINING);
            assertThat(simulator.describe().groups()).containsExactly(group.describe());

            simulator.start();

            var stable = group.awaitSnapshot(allAssigned(6, "consumer-0", "consumer-1", "consumer-2"), TIMEOUT);
            assertThat(stable.type()).isEqualTo(GroupType.CONSUMER);
            assertThat(stable.state()).isEqualTo(GroupState.STABLE);
            assertThat(stable.epoch()).isPositive();
            assertThat(stable.consumers()).allSatisfy(consumer -> {
                assertThat(consumer.clientId()).isEqualTo(groupId + "-" + consumer.id());
                assertThat(consumer.memberId()).isNotEmpty();
                assertThat(consumer.status()).isEqualTo(ConsumerStatus.STABLE);
                assertThat(consumer.partitions()).hasSize(2);
            });
            assertThat(simulator.snapshot().stale()).isFalse();
            assertThat(simulatorThreads()).contains(
                "kaos-poller",
                "kaos-" + groupId + "-consumer-0",
                "kaos-" + groupId + "-consumer-1",
                "kaos-" + groupId + "-consumer-2"
            );
            // The lag of every partition becomes known once the poller has its end offset.
            group.awaitSnapshot(s -> s.lag().get(topic).stream().allMatch(lag -> lag >= 0), TIMEOUT);

            group.consumer(1).leave();

            var without = group.awaitSnapshot(allAssigned(6, "consumer-0", "consumer-2"), TIMEOUT);
            assertThat(consumer(without, "consumer-1").status()).isEqualTo(ConsumerStatus.STOPPED);
            assertThat(consumer(without, "consumer-1").partitions()).isEmpty();

            group.consumer("consumer-1").join();

            group.awaitSnapshot(allAssigned(6, "consumer-0", "consumer-1", "consumer-2"), TIMEOUT);
            assertThat(groupIds()).contains(groupId);
        }

        assertThat(groupIds()).doesNotContain(groupId);
        await().atMost(THREADS_GONE).untilAsserted(() -> assertThat(simulatorThreads()).isEmpty());
    }

    @Test
    void switchesTheProtocolInBothDirections() {
        var topic = cluster.uniqueName("switch");
        var groupId = cluster.uniqueName("switch");

        try (var simulator = newSimulator()) {
            simulator.addTopic(topic, 4);
            var group = simulator.addGroup(groupId, g -> g
                .subscribe(topic)
                .heartbeatInterval(Duration.ofSeconds(1))
                .consumerDefaults(c -> c.protocol(Protocol.CLASSIC).pollDelay(Duration.ofMillis(100)))
                .consumers(2)
            );
            simulator.start();
            // The generation is reported by the consumer after its poll returns, so it can lag the assignment.
            var classic = group.awaitSnapshot(
                allAssigned(4, "consumer-0", "consumer-1").and(s -> s.generation() > 0),
                TIMEOUT
            );
            assertThat(classic.type()).isEqualTo(GroupType.CLASSIC);

            group.consumer(0).switchProtocol(Protocol.CONSUMER);

            assertThat(group.consumer(0).protocol()).isEqualTo(Protocol.CONSUMER);
            var upgraded = group.awaitSnapshot(
                allAssigned(4, "consumer-0", "consumer-1").and(s -> s.type() == GroupType.CONSUMER),
                TIMEOUT
            );
            assertThat(consumer(upgraded, "consumer-0").protocol()).isEqualTo(Protocol.CONSUMER);
            assertThat(consumer(upgraded, "consumer-1").protocol()).isEqualTo(Protocol.CLASSIC);

            group.consumer(0).switchProtocol(Protocol.CLASSIC);

            var downgraded = group.awaitSnapshot(
                allAssigned(4, "consumer-0", "consumer-1").and(s -> s.type() == GroupType.CLASSIC),
                TIMEOUT
            );
            assertThat(consumer(downgraded, "consumer-0").protocol()).isEqualTo(Protocol.CLASSIC);
        }
    }

    @Test
    void aFrozenConsumerIsEvictedByTheMaxPollInterval() {
        var topic = cluster.uniqueName("freeze");
        var groupId = cluster.uniqueName("freeze");

        try (var simulator = newSimulator()) {
            simulator.addTopic(topic, 4);
            var group = simulator.addGroup(groupId, g -> g
                .subscribe(topic)
                .heartbeatInterval(Duration.ofSeconds(1))
                .consumerDefaults(c -> c.pollDelay(Duration.ofMillis(100)).maxPollInterval(Duration.ofSeconds(3)))
                .consumers(2)
            );
            simulator.start();
            group.awaitSnapshot(allAssigned(4, "consumer-0", "consumer-1"), TIMEOUT);
            var frozen = group.consumer(0);

            frozen.inject(Fault.freeze());

            assertThat(frozen.faults()).containsExactly(Fault.freeze());
            // The session timeout is the default of 45 s and the heartbeats go on, so an eviction
            // within 15 s comes from the max poll interval of 3 s.
            var evicted = group.awaitSnapshot(
                allAssigned(4, "consumer-1").and(s -> consumer(s, "consumer-0").memberId().isEmpty()),
                Duration.ofSeconds(15)
            );
            assertThat(consumer(evicted, "consumer-0").status()).isEqualTo(ConsumerStatus.FROZEN);
            assertThat(consumer(evicted, "consumer-0").faults()).containsExactly(Fault.freeze());

            frozen.clear(Fault.freeze());

            group.awaitSnapshot(allAssigned(4, "consumer-0", "consumer-1"), TIMEOUT);
            assertThat(frozen.faults()).isEmpty();
        }
    }

    @Test
    void anIsolatedConsumerIsFencedAndRejoinsOnceHealed() {
        var topic = cluster.uniqueName("isolation");
        var groupId = cluster.uniqueName("isolation");

        try (var simulator = newSimulator()) {
            simulator.addTopic(topic, 4);
            var group = simulator.addGroup(groupId, g -> g
                .subscribe(topic)
                .sessionTimeout(Duration.ofSeconds(6))
                .heartbeatInterval(Duration.ofSeconds(1))
                .consumerDefaults(c -> c.pollDelay(Duration.ofMillis(100)))
                .consumers(2)
            );
            simulator.start();
            assertThat(group.warnings()).isEmpty();
            group.awaitSnapshot(allAssigned(4, "consumer-0", "consumer-1"), TIMEOUT);

            group.consumer(0).inject(Fault.networkIsolation());

            var fenced = group.awaitSnapshot(
                allAssigned(4, "consumer-1").and(s -> consumer(s, "consumer-0").memberId().isEmpty()),
                TIMEOUT
            );
            assertThat(consumer(fenced, "consumer-0").status()).isEqualTo(ConsumerStatus.ISOLATED);

            group.consumer(0).clearFaults();

            group.awaitSnapshot(allAssigned(4, "consumer-0", "consumer-1"), TIMEOUT);
        }
    }

    @Test
    void anIsolatedClassicStaticMemberLeavesAfterTheSessionTimeoutOfTheGroup() {
        var topic = cluster.uniqueName("classic-session");
        var groupId = cluster.uniqueName("classic-session");

        try (var simulator = newSimulator()) {
            simulator.addTopic(topic, 4);
            var group = simulator.addGroup(groupId, g -> g
                .subscribe(topic)
                .sessionTimeout(Duration.ofSeconds(10))
                .heartbeatInterval(Duration.ofSeconds(1))
                .consumerDefaults(
                    c -> c.protocol(Protocol.CLASSIC).staticMember(true).pollDelay(Duration.ofMillis(100)))
                .consumers(2)
            );
            simulator.start();
            var stable = group.awaitSnapshot(allAssigned(4, "consumer-0", "consumer-1"), TIMEOUT);
            assertThat(stable.type()).isEqualTo(GroupType.CLASSIC);

            group.consumer(0).inject(Fault.networkIsolation());

            // A static member that stops heartbeating stays until its session times out: 10 s
            // from the group, not the 45 s of the client default.
            var evicted = group.awaitSnapshot(
                allAssigned(4, "consumer-1").and(s -> consumer(s, "consumer-0").memberId().isEmpty()),
                Duration.ofSeconds(30)
            );
            assertThat(consumer(evicted, "consumer-0").status()).isEqualTo(ConsumerStatus.ISOLATED);
        }
    }

    @Test
    void anIsolatedClassicMemberLeavesAfterItsOwnSessionTimeout() {
        var topic = cluster.uniqueName("classic-override");
        var groupId = cluster.uniqueName("classic-override");

        try (var simulator = newSimulator()) {
            simulator.addTopic(topic, 4);
            var group = simulator.addGroup(groupId, g -> g
                .subscribe(topic)
                .consumerDefaults(
                    c -> c.protocol(Protocol.CLASSIC).staticMember(true).pollDelay(Duration.ofMillis(100)))
                .consumer(c -> c.sessionTimeout(Duration.ofSeconds(6)).heartbeatInterval(Duration.ofSeconds(1)))
                .consumers(1)
            );
            simulator.start();
            group.awaitSnapshot(allAssigned(4, "consumer-0", "consumer-1"), TIMEOUT);

            group.consumer(0).inject(Fault.networkIsolation());
            group.consumer(1).inject(Fault.networkIsolation());

            // consumer-0 times out after its own 6 s; consumer-1 keeps the 45 s of the group.
            var evicted =
                group.awaitSnapshot(s -> consumer(s, "consumer-0").memberId().isEmpty(), Duration.ofSeconds(30));
            assertThat(consumer(evicted, "consumer-1").memberId()).isNotEmpty();
        }
    }

    @Test
    void aClassicSessionTimeoutUnderTheBrokerMinimumFailsTheConsumer() {
        var topic = cluster.uniqueName("classic-min");
        var groupId = cluster.uniqueName("classic-min");

        try (var simulator = newSimulator()) {
            simulator.addTopic(topic, 2);
            // 5.5 s is within the consumer protocol bounds of the broker, and under the 6 s of its
            // group.min.session.timeout.ms, which bounds the classic members.
            var group = simulator.addGroup(groupId, g -> g
                .subscribe(topic)
                .sessionTimeout(Duration.ofMillis(5_500))
                .heartbeatInterval(Duration.ofSeconds(1))
                .consumerDefaults(c -> c.pollDelay(Duration.ofMillis(100)))
                .consumer(c -> c.protocol(Protocol.CLASSIC))
                .consumers(1)
            );
            simulator.start();

            var failed = group.awaitSnapshot(
                allAssigned(2, "consumer-1").and(s -> consumer(s, "consumer-0").status() == ConsumerStatus.FAILED),
                TIMEOUT
            );
            assertThat(consumer(failed, "consumer-0").failure()).contains("InvalidSessionTimeoutException");
            assertThat(group.warnings()).isEmpty();
        }
    }

    @Test
    void setsOnlyTheExplicitGroupConfigsAndRevertsThem() throws Exception {
        var topic = cluster.uniqueName("configs");
        var groupId = cluster.uniqueName("configs");

        try (var simulator = newSimulator()) {
            simulator.addTopic(topic, 2);
            var group = simulator.addGroup(groupId, g -> g
                .subscribe(topic)
                .sessionTimeout(Duration.ofSeconds(10))
                .assignmentInterval(Duration.ofMillis(500))
                .consumers(1)
            );
            simulator.start();

            assertThat(group.warnings()).isEmpty();
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(dynamicGroupConfigs(groupId)).containsOnly(
                Map.entry("consumer.session.timeout.ms", "10000"),
                Map.entry("consumer.assignment.interval.ms", "500")
            ));
            group.awaitSnapshot(allAssigned(2, "consumer-0"), TIMEOUT);
        }

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(dynamicGroupConfigs(groupId)).isEmpty());
    }

    @Test
    void aGroupConfigTheBrokerRefusesIsAWarning() throws Exception {
        var topic = cluster.uniqueName("refused");
        var groupId = cluster.uniqueName("refused");

        try (var simulator = newSimulator()) {
            simulator.addTopic(topic, 2);
            // The session timeout is over the group.consumer.max.session.timeout.ms of the broker.
            var group = simulator.addGroup(groupId, g -> g
                .subscribe(topic)
                .sessionTimeout(Duration.ofSeconds(120))
                .heartbeatInterval(Duration.ofSeconds(2))
                .consumers(1)
            );
            assertThat(group.warnings()).isEmpty();

            simulator.start();

            assertThat(group.warnings()).singleElement().asString().contains("consumer.session.timeout.ms=120000");
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(dynamicGroupConfigs(groupId))
                .containsOnly(Map.entry("consumer.heartbeat.interval.ms", "2000")));
            group.awaitSnapshot(allAssigned(2, "consumer-0"), TIMEOUT);
        }

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(dynamicGroupConfigs(groupId)).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void aQuickStopAndStartWithStaticMembersWorks(Protocol protocol) {
        var topic = cluster.uniqueName("static");
        var groupId = cluster.uniqueName("static");

        try (var simulator = newSimulator()) {
            for (int run = 0; run < 2; run++) {
                simulator.addTopic(topic, 4);
                var group = simulator.addGroup(groupId, g -> g
                    .subscribe(topic)
                    .consumerDefaults(c -> c.protocol(protocol).staticMember(true).pollDelay(Duration.ofMillis(100)))
                    .consumers(2)
                );
                simulator.start();

                // Well under the session timeout of 45 s, after which lingering members would go.
                var stable = group.awaitSnapshot(allAssigned(4, "consumer-0", "consumer-1"), Duration.ofSeconds(30));
                assertThat(stable.consumers()).extracting(ConsumerSnapshot::instanceId)
                    .containsExactly(groupId + "-consumer-0", groupId + "-consumer-1");

                simulator.stop();
            }
        }
    }

    @Test
    void listenersFireOnlyOnChange() {
        var topic = cluster.uniqueName("quiet");
        var groupId = cluster.uniqueName("quiet");
        var snapshots = new CopyOnWriteArrayList<SimulatorSnapshot>();

        try (var simulator = newSimulator()) {
            simulator.addTopic(topic, 2);
            var group = simulator.addGroup(groupId, g -> g
                .subscribe(topic)
                .heartbeatInterval(Duration.ofSeconds(1))
                .consumerDefaults(c -> c.pollDelay(Duration.ofMillis(50)))
                .consumers(1)
            );
            simulator.start();
            group.awaitSnapshot(
                allAssigned(2, "consumer-0").and(s -> s.lag().get(topic).stream().allMatch(lag -> lag == 0)),
                TIMEOUT
            );
            simulator.addListener(snapshots::add);

            // The poller and the consumer keep running, and nothing they see changes.
            await().during(QUIET).atMost(TIMEOUT).until(snapshots::isEmpty);

            group.consumer(0).inject(Fault.freeze());

            await().atMost(TIMEOUT).until(() -> !snapshots.isEmpty());
            var first = consumer(snapshots.get(0).groups().get(0), "consumer-0");
            assertThat(first.faults()).containsExactly(Fault.freeze());
        }

        assertThat(snapshots).extracting(SimulatorSnapshot::revision).isSorted().doesNotHaveDuplicates();
        for (int i = 1; i < snapshots.size(); i++) {
            assertThat(snapshots.get(i).withRevision(0)).isNotEqualTo(snapshots.get(i - 1).withRevision(0));
        }
    }

    @Test
    void aConsumerThatCannotRecoverIsFailed() {
        var topic = cluster.uniqueName("failed");
        var groupId = cluster.uniqueName("failed");

        try (var simulator = newSimulator()) {
            simulator.addTopic(topic, 2);
            // The consumer protocol accepts any assignor name, and the broker only knows its own.
            var group = simulator.addGroup(groupId, g -> g
                .subscribe(topic)
                .heartbeatInterval(Duration.ofSeconds(1))
                .consumerDefaults(c -> c.pollDelay(Duration.ofMillis(100)))
                .consumer(c -> c.assignor("no-such-assignor"))
                .consumers(1)
            );
            simulator.start();

            var failed = group.awaitSnapshot(
                allAssigned(2, "consumer-1").and(s -> consumer(s, "consumer-0").status() == ConsumerStatus.FAILED),
                TIMEOUT
            );
            assertThat(consumer(failed, "consumer-0").failure()).contains("UnsupportedAssignorException");
            assertThat(simulator.snapshot().phase()).isEqualTo(Phase.RUNNING);
        }
    }

    @Test
    void theAutopilotFlipsSubscriptions() {
        var topic = cluster.uniqueName("autopilot");
        var groupId = cluster.uniqueName("autopilot");

        try (var simulator = newSimulator()) {
            simulator.addTopic(topic, 2);
            var group = simulator.addGroup(groupId, g -> g.subscribe(topic).consumers(1));
            simulator.start();

            group.autopilot().start(Duration.ofMillis(100), Duration.ofMillis(200));

            assertThat(group.autopilot().enabled()).isTrue();
            assertThat(simulatorThreads()).contains("kaos-" + groupId + "-autopilot");
            group.awaitSnapshot(s -> s.autopilot().equals(new AutopilotSnapshot(true, 100, 200)), TIMEOUT);
            group.awaitSnapshot(s -> !consumer(s, "consumer-0").subscribed(), TIMEOUT);
            group.awaitSnapshot(s -> consumer(s, "consumer-0").subscribed(), TIMEOUT);

            group.autopilot().stop();

            assertThat(simulatorThreads()).doesNotContain("kaos-" + groupId + "-autopilot");
            group.awaitSnapshot(s -> s.autopilot().equals(new AutopilotSnapshot(false, 100, 200)), TIMEOUT);
        }
    }

    /** A simulator whose proxy requests the port block of this test JVM, away from the default one. */
    private Simulator newSimulator() {
        return new Simulator(cluster.bootstrapServers()).withProxyPort(cluster.proxyPort());
    }

    /**
     * The group is stable, exactly the given consumers hold partitions, all of them are stable and
     * together they hold every partition, as assigned.
     */
    private static Predicate<GroupSnapshot> allAssigned(int partitions, String... owners) {
        var expected = Set.of(owners);
        return snapshot -> {
            if (snapshot.state() != GroupState.STABLE) {
                return false;
            }
            var assigned = 0;
            var holders = 0;
            for (var consumer : snapshot.consumers()) {
                var owns = expected.contains(consumer.id());
                if (owns) {
                    holders++;
                }
                if (owns != !consumer.partitions().isEmpty()) {
                    return false;
                }
                if (owns && consumer.status() != ConsumerStatus.STABLE) {
                    return false;
                }
                if (!consumer.partitions().stream().allMatch(view -> view.state() == PartitionState.ASSIGNED)) {
                    return false;
                }
                assigned += consumer.partitions().size();
            }
            // A snapshot published before a consumer was added does not list it yet.
            return holders == expected.size() && assigned == partitions;
        };
    }

    private static ConsumerSnapshot consumer(GroupSnapshot group, String id) {
        return group.consumers().stream().filter(consumer -> consumer.id().equals(id)).findFirst().orElseThrow();
    }

    private Set<String> groupIds() throws Exception {
        return admin.listGroups().all().get().stream().map(GroupListing::groupId).collect(Collectors.toSet());
    }

    /**
     * The dynamic configs of the group as the broker reports them. The broker applies a config change
     * to its metadata cache after the alter call returns, so a check on them is awaited.
     */
    private Map<String, String> dynamicGroupConfigs(String groupId) throws Exception {
        var resource = new ConfigResource(ConfigResource.Type.GROUP, groupId);
        var configs = new HashMap<String, String>();
        for (var entry : admin.describeConfigs(List.of(resource)).all().get().get(resource).entries()) {
            if (entry.source() == ConfigEntry.ConfigSource.DYNAMIC_GROUP_CONFIG) {
                configs.put(entry.name(), entry.value());
            }
        }
        return configs;
    }

    /**
     * The threads started since the beginning of the test that belong to a simulator: its own
     * threads, its Kafka clients and its proxy. The threads of the test are left out: its admin
     * client, and the ones {@link TestThreads} ignores.
     */
    private List<String> simulatorThreads() {
        return TestThreads.startedSince(threadsBefore).stream()
            .filter(name -> !name.startsWith("kafka-") || name.contains("kaos-"))
            .toList();
    }
}
