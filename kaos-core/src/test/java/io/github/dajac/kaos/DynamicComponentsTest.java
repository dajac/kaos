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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.github.dajac.kaos.test.IntegrationTest;
import io.github.dajac.kaos.test.KafkaCluster;
import io.github.dajac.kaos.test.TestThreads;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Components added and removed while the simulator runs, and several groups side by side. */
@IntegrationTest
class DynamicComponentsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
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
    void twoGroupsWithDifferentProtocolsOnOneTopicReportIndependentAssignmentsAndLag() throws Exception {
        var topic = cluster.uniqueName("shared");
        var nextId = cluster.uniqueName("next");
        var legacyId = cluster.uniqueName("legacy");

        try (var simulator = newSimulator()) {
            simulator.withClientProperty("auto.commit.interval.ms", "500");
            simulator.addTopic(topic, 4);
            simulator.addProducer(p -> p.topics(topic).ratePerPartition(10));
            var next = simulator.addGroup(nextId, g -> g
                .subscribe(topic)
                .heartbeatInterval(Duration.ofSeconds(1))
                .consumerDefaults(c -> c.pollDelay(Duration.ofMillis(100)))
                .consumers(2)
            );
            simulator.start();

            // Added while the simulator runs: it starts at once.
            var legacy = simulator.addGroup(legacyId, g -> g
                .subscribe(topic)
                .consumerDefaults(c -> c.protocol(Protocol.CLASSIC).pollDelay(Duration.ofMillis(100)))
                .consumers(2)
            );

            assertThat(simulator.groups()).containsExactly(next, legacy);
            assertThat(simulator.describe().groups()).containsExactly(next.describe(), legacy.describe());
            var consumerGroup = next.awaitSnapshot(allAssigned(4, "consumer-0", "consumer-1"), TIMEOUT);
            var classicGroup = legacy.awaitSnapshot(allAssigned(4, "consumer-0", "consumer-1"), TIMEOUT);
            assertThat(consumerGroup.type()).isEqualTo(GroupType.CONSUMER);
            assertThat(classicGroup.type()).isEqualTo(GroupType.CLASSIC);
            // Each group assigns every partition of the topic to its own consumers.
            assertThat(partitions(consumerGroup)).isEqualTo(partitions(classicGroup)).hasSize(4);
            assertThat(simulatorThreads()).contains(
                "kaos-" + nextId + "-consumer-0",
                "kaos-" + legacyId + "-consumer-0"
            );

            legacy.consumer(0).leave();
            legacy.consumer(1).leave();

            // The legacy group stops committing and its lag grows, while the next group keeps up.
            var diverged = simulator.awaitSnapshot(s -> {
                var legacyLag = group(s, legacyId).lag().get(topic);
                var nextLag = group(s, nextId).lag().get(topic);
                return legacyLag.stream().allMatch(lag -> lag >= 50)
                    && nextLag.stream().allMatch(lag -> lag >= 0 && lag < 50);
            }, TIMEOUT);
            assertThat(group(diverged, nextId).state()).isEqualTo(GroupState.STABLE);
            assertThat(group(diverged, legacyId).consumers()).extracting(ConsumerSnapshot::status)
                .containsOnly(ConsumerStatus.STOPPED);
            assertThat(groupIds()).contains(nextId, legacyId);
        }

        // The stop removes the group added while running as well.
        assertThat(groupIds()).doesNotContain(nextId, legacyId);
        await().atMost(THREADS_GONE).untilAsserted(() -> assertThat(simulatorThreads()).isEmpty());
    }

    @Test
    void removingAGroupLeavesTheOtherGroupUntouched() throws Exception {
        var topic = cluster.uniqueName("groups");
        var keptId = cluster.uniqueName("kept");
        var removedId = cluster.uniqueName("removed");

        try (var simulator = newSimulator()) {
            simulator.addTopic(topic, 4);
            var kept = simulator.addGroup(keptId, g -> g
                .subscribe(topic)
                .heartbeatInterval(Duration.ofSeconds(1))
                .consumerDefaults(c -> c.pollDelay(Duration.ofMillis(100)))
                .consumers(2)
            );
            var removed = simulator.addGroup(removedId, g -> g
                .subscribe(topic)
                .sessionTimeout(Duration.ofSeconds(10))
                .heartbeatInterval(Duration.ofSeconds(1))
                .consumerDefaults(c -> c.staticMember(true).pollDelay(Duration.ofMillis(100)))
                .consumers(2)
            );
            simulator.start();
            var before = kept.awaitSnapshot(allAssigned(4, "consumer-0", "consumer-1"), TIMEOUT);
            removed.awaitSnapshot(allAssigned(4, "consumer-0", "consumer-1"), TIMEOUT);
            removed.autopilot().start(Duration.ofSeconds(30), Duration.ofSeconds(60));
            assertThat(dynamicGroupConfigs(removedId)).containsKey("consumer.session.timeout.ms");
            assertThat(simulatorThreads()).contains("kaos-" + removedId + "-autopilot");
            var removedConsumer = removed.consumer(0);

            removed.remove();

            assertThat(simulator.groups()).containsExactly(kept);
            assertThat(simulator.describe().groups()).containsExactly(kept.describe());
            assertThat(groupIds()).contains(keptId).doesNotContain(removedId);
            assertThat(dynamicGroupConfigs(removedId)).isEmpty();
            assertThat(simulatorThreads()).noneMatch(name -> name.startsWith("kaos-" + removedId));
            simulator.awaitSnapshot(s -> s.groups().size() == 1, TIMEOUT);
            assertThatThrownBy(removed::remove).isInstanceOf(SimulationStateException.class);
            assertThatThrownBy(removedConsumer::leave).isInstanceOf(SimulationStateException.class);
            assertThatThrownBy(() -> removed.autopilot().start(QUIET, QUIET))
                .isInstanceOf(SimulationStateException.class);

            // The other group keeps its members, its epoch and its assignment.
            await().during(QUIET).atMost(TIMEOUT).until(() -> {
                var now = kept.snapshot();
                return allAssigned(4, "consumer-0", "consumer-1").test(now)
                    && now.epoch() == before.epoch()
                    && memberIds(now).equals(memberIds(before));
            });
        }

        await().atMost(THREADS_GONE).untilAsserted(() -> assertThat(simulatorThreads()).isEmpty());
    }

    @Test
    void addingOrRemovingAConsumerTriggersARebalance() throws Exception {
        var topic = cluster.uniqueName("rebalance");
        var groupId = cluster.uniqueName("rebalance");

        try (var simulator = newSimulator()) {
            simulator.addTopic(topic, 4);
            var group = simulator.addGroup(groupId, g -> g
                .subscribe(topic)
                .heartbeatInterval(Duration.ofSeconds(1))
                .consumerDefaults(c -> c.pollDelay(Duration.ofMillis(100)))
                .consumers(1)
            );
            simulator.start();
            var alone = group.awaitSnapshot(allAssigned(4, "consumer-0"), TIMEOUT);

            var added = group.addConsumer(c -> c.rackId("b"));

            assertThat(added.id()).isEqualTo("consumer-1");
            assertThat(simulatorThreads()).contains("kaos-" + groupId + "-consumer-1");
            var shared = group.awaitSnapshot(allAssigned(4, "consumer-0", "consumer-1"), TIMEOUT);
            assertThat(shared.epoch()).isGreaterThan(alone.epoch());
            assertThat(group.describe().consumers()).extracting(ConsumerSpec::id)
                .containsExactly("consumer-0", "consumer-1");

            added.remove();

            assertThat(group.consumers()).extracting(ConsumerHandle::id).containsExactly("consumer-0");
            assertThat(simulatorThreads()).doesNotContain("kaos-" + groupId + "-consumer-1");
            var back = group.awaitSnapshot(
                allAssigned(4, "consumer-0").and(s -> s.consumers().size() == 1),
                TIMEOUT
            );
            assertThat(back.epoch()).isGreaterThan(shared.epoch());
            assertThatThrownBy(added::leave).isInstanceOf(SimulationStateException.class);

            // A removed consumer's id is not assigned again.
            var fresh = group.addConsumer(ConsumerSpec.inheriting());

            assertThat(fresh.id()).isEqualTo("consumer-2");
            group.awaitSnapshot(allAssigned(4, "consumer-0", "consumer-2"), TIMEOUT);
        }

        assertThat(groupIds()).doesNotContain(groupId);
        await().atMost(THREADS_GONE).untilAsserted(() -> assertThat(simulatorThreads()).isEmpty());
    }

    @Test
    void addsAndRemovesTopicsAndProducersWhileRunning() throws Exception {
        var first = cluster.uniqueName("first");
        var second = cluster.uniqueName("second");
        var groupId = cluster.uniqueName("reader");

        try (var simulator = newSimulator()) {
            simulator.addTopic(first, 1);
            simulator.start();

            var added = simulator.addTopic(second, 2);

            // The call returns once the cluster reports the topic.
            assertThat(topicNames()).contains(second);
            assertThat(simulator.describe().topics()).containsExactly(new TopicSpec(first, 1),
                new TopicSpec(second, 2));
            simulator.awaitSnapshot(s -> s.topics().size() == 2, TIMEOUT);

            var producer = simulator.addProducer(p -> p.topics(added).ratePerPartition(20));

            assertThat(producer.id()).isEqualTo("producer-0");
            assertThat(producer.state()).isEqualTo(ProducerState.RUNNING);
            assertThat(simulatorThreads()).contains("kaos-producer-producer-0");
            await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(endOffsets(second, 2)).allMatch(offset -> offset > 0));

            var group = simulator.addGroup(groupId, g -> g
                .subscribe(added)
                .heartbeatInterval(Duration.ofSeconds(1))
                .consumerDefaults(c -> c.pollDelay(Duration.ofMillis(100)))
                .consumers(1)
            );
            group.awaitSnapshot(allAssigned(2, "consumer-0"), TIMEOUT);

            assertThatThrownBy(added::remove)
                .isInstanceOf(SimulationStateException.class)
                .hasMessageContaining("producer producer-0")
                .hasMessageContaining("group " + groupId);
            assertThat(topicNames()).contains(second);
            assertThat(simulator.topics()).contains(added);

            producer.remove();

            assertThat(simulator.producers()).isEmpty();
            simulator.awaitSnapshot(s -> s.producers().isEmpty(), TIMEOUT);
            assertThat(simulatorThreads()).doesNotContain("kaos-producer-producer-0");
            var stopped = awaitSteadyOffset(second, 2);
            await().during(QUIET).atMost(TIMEOUT).until(() -> totalOffset(second, 2) == stopped);
            assertThatThrownBy(producer::pause).isInstanceOf(SimulationStateException.class);
            assertThatThrownBy(added::remove).isInstanceOf(SimulationStateException.class)
                .hasMessageContaining("group " + groupId);

            group.remove();
            added.remove();

            // The call returns once the cluster no longer reports the topic.
            assertThat(topicNames()).contains(first).doesNotContain(second);
            assertThat(simulator.describe().topics()).containsExactly(new TopicSpec(first, 1));
            simulator.awaitSnapshot(s -> s.topics().equals(List.of(new TopicSpec(first, 1))), TIMEOUT);
            assertThatThrownBy(added::remove).isInstanceOf(SimulationStateException.class);

            // The id of the removed producer is not assigned again.
            assertThat(simulator.addProducer(p -> p.topics(first)).id()).isEqualTo("producer-1");
        }

        assertThat(topicNames()).doesNotContain(first, second);
        assertThat(groupIds()).doesNotContain(groupId);
        await().atMost(THREADS_GONE).untilAsserted(() -> assertThat(simulatorThreads()).isEmpty());
    }

    @Test
    void aFailedAddWhileRunningLeavesTheSimulatorUnchanged() throws Exception {
        var topic = cluster.uniqueName("unchanged");
        var existing = cluster.uniqueName("existing");
        var groupId = cluster.uniqueName("unstartable");
        admin.createTopics(List.of(new NewTopic(existing, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        cluster.awaitTopic(admin, existing);

        // The admin client ignores both properties. The Kafka producer refuses the compression type
        // and the Kafka consumer the isolation level, so producers and consumers fail to start.
        try (
            var simulator = newSimulator()
                .withClientProperty("compression.type", "bogus")
                .withClientProperty("isolation.level", "bogus")
        ) {
            simulator.addTopic(topic, 2);
            simulator.start();
            var described = simulator.describe();
            var threads = simulatorThreads();

            // The simulator only creates the topics it owns.
            assertThatThrownBy(() -> simulator.addTopic(existing, 1))
                .isInstanceOf(SimulationException.class)
                .hasMessageContaining("already exists");
            assertThat(topicNames()).contains(existing);

            assertThatThrownBy(() -> simulator.addProducer(p -> p.topics(topic)))
                .isInstanceOf(SimulationException.class)
                .hasMessageContaining("Could not start producer producer-0")
                .hasMessageContaining("compression.type");

            var failure = catchThrowable(() -> simulator.addGroup(groupId, g -> g
                .subscribe(topic)
                .sessionTimeout(Duration.ofSeconds(10))
                .consumers(2)
            ));
            assertThat(failure)
                .isInstanceOf(SimulationException.class)
                .hasMessageContaining("Could not start group " + groupId)
                .hasMessageContaining("isolation.level");
            // The group configs that the start set are reverted.
            assertThat(dynamicGroupConfigs(groupId)).isEmpty();

            assertThatThrownBy(() -> simulator.addTopic(topic, 1)).isInstanceOf(InvalidConfigException.class);

            assertThat(simulator.describe()).isEqualTo(described);
            assertThat(simulator.producers()).isEmpty();
            assertThat(simulator.groups()).isEmpty();
            assertThat(simulator.snapshot().phase()).isEqualTo(Phase.RUNNING);
            assertThat(simulator.snapshot().lastError()).isEmpty();
            await().during(QUIET).atMost(TIMEOUT).until(() -> simulator.snapshot().topics().size() == 1
                && simulator.snapshot().producers().isEmpty() && simulator.snapshot().groups().isEmpty());
            await().atMost(THREADS_GONE).untilAsserted(() -> assertThat(simulatorThreads()).isEqualTo(threads));

            // The simulator is unharmed: it adds a component that starts.
            var added = cluster.uniqueName("added");
            simulator.addTopic(added, 1);
            assertThat(topicNames()).contains(added);
        }

        assertThat(topicNames()).contains(existing).doesNotContain(topic);
        await().atMost(THREADS_GONE).untilAsserted(() -> assertThat(simulatorThreads()).isEmpty());
    }

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
            for (var consumer : snapshot.consumers()) {
                var owns = expected.contains(consumer.id());
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
            return assigned == partitions;
        };
    }

    private static GroupSnapshot group(SimulatorSnapshot snapshot, String groupId) {
        return snapshot.groups().stream().filter(group -> group.id().equals(groupId)).findFirst().orElseThrow();
    }

    private static Set<PartitionRef> partitions(GroupSnapshot group) {
        return group.consumers().stream()
            .flatMap(consumer -> consumer.partitions().stream())
            .map(view -> new PartitionRef(view.topic(), view.partition()))
            .collect(Collectors.toSet());
    }

    private static Map<String, String> memberIds(GroupSnapshot group) {
        return group.consumers().stream().collect(Collectors.toMap(ConsumerSnapshot::id, ConsumerSnapshot::memberId));
    }

    /** Waits until the offsets stay the same for a while: the round in flight at the stop is over. */
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

    private Set<String> topicNames() throws Exception {
        return admin.listTopics().names().get(30, TimeUnit.SECONDS);
    }

    private Set<String> groupIds() throws Exception {
        return admin.listGroups().all().get(30, TimeUnit.SECONDS).stream()
            .map(GroupListing::groupId)
            .collect(Collectors.toSet());
    }

    private Map<String, String> dynamicGroupConfigs(String groupId) throws Exception {
        var resource = new ConfigResource(ConfigResource.Type.GROUP, groupId);
        var configs = new HashMap<String, String>();
        for (
            var entry : admin.describeConfigs(List.of(resource)).all().get(30, TimeUnit.SECONDS).get(resource)
                .entries()
        ) {
            if (entry.source() == ConfigEntry.ConfigSource.DYNAMIC_GROUP_CONFIG) {
                configs.put(entry.name(), entry.value());
            }
        }
        return configs;
    }

    /** The threads that the test started and that are still alive, without the test's admin client. */
    private List<String> simulatorThreads() {
        return TestThreads.startedSince(threadsBefore).stream()
            .filter(name -> !name.startsWith("kafka-") || name.contains("kaos-"))
            .toList();
    }
}
