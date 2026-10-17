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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.dajac.kaos.ConsumerSettings;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.ConsumerStatus;
import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.Protocol;
import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.SimulationStateException;
import io.github.dajac.kaos.internal.proxy.NetworkFaults;
import io.github.dajac.kaos.internal.state.AdminView;
import io.github.dajac.kaos.internal.state.StatusDeriver;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.FencedInstanceIdException;
import org.apache.kafka.common.errors.InvalidSessionTimeoutException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Drives the consumer loop against mock Kafka consumers. */
class ConsumerRunnerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration QUIET = Duration.ofMillis(300);
    private static final TopicPartition ORDERS_0 = new TopicPartition("orders", 0);
    private static final PartitionRef ORDERS_0_REF = new PartitionRef("orders", 0);

    private final List<MockConsumer<String, String>> clients = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> settings = new CopyOnWriteArrayList<>();
    private final AtomicInteger changes = new AtomicInteger();
    private final AtomicReference<Runnable> guard = new AtomicReference<>(() -> {});
    private final NetworkFaults network = new NetworkFaults();
    private final Threads threads = new Threads();
    private final Resources resources = new Resources("test");
    private final ConsumerRunner.Services services = new ConsumerRunner.Services(this::newClient, threads, network);

    private MockConsumer<String, String> newClient(Map<String, Object> given) {
        settings.add(given);
        var client = new MockConsumer<String, String>("latest");
        client.updateEndOffsets(Map.of(ORDERS_0, 0L));
        // A subscribe of the mock only takes effect at the next poll, as with a real consumer.
        client.schedulePollTask(() -> client.rebalance(List.of(ORDERS_0)));
        clients.add(client);
        return client;
    }

    private ConsumerRunner runner(ConsumerSettings settings) {
        return runner(settings, ConsumerRunner.ClassicTimeouts.NONE);
    }

    private ConsumerRunner runner(ConsumerSettings settings, ConsumerRunner.ClassicTimeouts timeouts) {
        return new ConsumerRunner(
            "g",
            new ConsumerSpec("consumer-0", null, settings),
            List.of("orders"),
            timeouts,
            new ConsumerRunner.Owner() {
                @Override
                public void changed() {
                    changes.incrementAndGet();
                }

                @Override
                public void refuseBlocking() {
                    guard.get().run();
                }

                @Override
                public void remove(ConsumerRunner consumer) {
                    consumer.markRemoved();
                }
            }
        );
    }

    private ConsumerRunner runner() {
        return runner(new ConsumerSettings(Protocol.CONSUMER, "", false, "", 0, 20, 300_000));
    }

    @AfterEach
    void close() {
        resources.close();
    }

    @Test
    void isSubscribedAndFaultlessBeforeItStarts() {
        var runner = runner();

        assertThat(runner.id()).isEqualTo("consumer-0");
        assertThat(runner.clientId()).isEqualTo("g-consumer-0");
        assertThat(runner.protocol()).isEqualTo(Protocol.CONSUMER);
        assertThat(runner.subscribed()).isTrue();
        assertThat(runner.faults()).isEmpty();
        assertThat(runner.localState().failed()).isFalse();
        assertThat(runner.localState().generation()).isEqualTo(-1);
        assertThat(clients).isEmpty();
    }

    @Test
    void flagsFlippedBeforeTheStartApplyWhenItStarts() {
        var runner = runner();
        runner.leave();
        runner.inject(Fault.networkIsolation());

        runner.start(services, resources);

        assertThat(network.modeOf("g-consumer-0")).isEqualTo(NetworkFaults.Mode.BLACKHOLE);
        await().during(QUIET).atMost(TIMEOUT).until(() -> clients.get(0).subscription().isEmpty());
    }

    @Test
    void subscribesAndPollsUntilItLeaves() {
        var runner = runner();
        runner.start(services, resources);
        var client = clients.get(0);

        await().atMost(TIMEOUT).until(() -> runner.localState().localAssignment().contains(ORDERS_0_REF));
        assertThat(client.subscription()).containsExactly("orders");
        assertThat(runner.localState().generation()).isEqualTo(client.groupMetadata().generationId());

        runner.leave();

        await().atMost(TIMEOUT).until(() -> client.subscription().isEmpty());
        assertThat(runner.subscribed()).isFalse();
        assertThat(runner.localState().localAssignment()).isEmpty();
        assertThat(runner.localState().generation()).isEqualTo(-1);

        runner.join();

        await().atMost(TIMEOUT).until(() -> client.subscription().contains("orders"));
    }

    @Test
    void aFreezeStopsThePollsAndKeepsTheSubscription() {
        var runner = runner();
        runner.start(services, resources);
        var client = clients.get(0);
        await().atMost(TIMEOUT).until(() -> !runner.localState().localAssignment().isEmpty());

        runner.inject(Fault.freeze());

        var polls = new AtomicInteger();
        client.schedulePollTask(polls::incrementAndGet);
        await().during(QUIET).atMost(TIMEOUT).until(() -> polls.get() == 0);
        assertThat(client.subscription()).containsExactly("orders");
        assertThat(network.snapshot()).isEmpty();

        runner.clear(Fault.freeze());

        await().atMost(TIMEOUT).until(() -> polls.get() > 0);
    }

    @Test
    void theNetworkFaultsGoThroughTheProxy() {
        var runner = runner();
        runner.start(services, resources);

        runner.inject(Fault.connectionReset());
        assertThat(network.modeOf("g-consumer-0")).isEqualTo(NetworkFaults.Mode.RESET);

        // The isolation wins while both are active.
        runner.inject(Fault.networkIsolation());
        assertThat(network.modeOf("g-consumer-0")).isEqualTo(NetworkFaults.Mode.BLACKHOLE);
        assertThat(runner.faults()).containsExactly(Fault.networkIsolation(), Fault.connectionReset());

        runner.clear(Fault.networkIsolation());
        assertThat(network.modeOf("g-consumer-0")).isEqualTo(NetworkFaults.Mode.RESET);

        runner.clearFaults();
        assertThat(network.snapshot()).isEmpty();
        assertThat(runner.faults()).isEmpty();
    }

    @Test
    void aPollErrorIsLoggedAndTheLoopGoesOn() {
        var runner = runner();
        runner.start(services, resources);
        var client = clients.get(0);
        await().atMost(TIMEOUT).until(() -> !runner.localState().localAssignment().isEmpty());

        client.setPollException(new TimeoutException("slow"));

        var polls = new AtomicInteger();
        client.schedulePollTask(polls::incrementAndGet);
        await().atMost(TIMEOUT).until(() -> polls.get() > 0);
        assertThat(runner.localState().failed()).isFalse();
    }

    @Test
    void anUnrecoverableErrorFailsTheConsumerAndClosesItsClient() {
        var runner = runner();
        runner.start(services, resources);
        var client = clients.get(0);

        client.setPollException(new FencedInstanceIdException("fenced"));

        await().atMost(TIMEOUT).until(() -> runner.localState().failed());
        var state = runner.localState();
        assertThat(state.failure()).isEqualTo("FencedInstanceIdException: fenced");
        assertThat(state.localAssignment()).isEmpty();
        assertThat(client.closed()).isTrue();
        assertThat(StatusDeriver.status(state, ConsumerStatus.JOINING, Set.of(), Set.of()))
            .isEqualTo(ConsumerStatus.FAILED);
        await().atMost(TIMEOUT).until(() -> threads().isEmpty());
    }

    @Test
    void stopClosesTheClientAndForgetsTheNetworkFault() throws InterruptedException {
        var runner = runner();
        runner.start(services, resources);
        runner.inject(Fault.networkIsolation());
        var client = clients.get(0);

        runner.stop();

        assertThat(client.closed()).isTrue();
        assertThat(network.snapshot()).isEmpty();
        assertThat(runner.localState().failed()).isFalse();
        assertThat(runner.faults()).containsExactly(Fault.networkIsolation());
        await().atMost(TIMEOUT).until(() -> threads().isEmpty());
        runner.stop();
    }

    @Test
    void anIsolationRacingAStopLeavesNoFaultOnTheProxy() throws Exception {
        for (int round = 0; round < 20; round++) {
            var runner = runner();
            runner.start(services, new Resources("round " + round));
            var injector = threads.newThread("injector", () -> {
                for (int i = 0; i < 200; i++) {
                    runner.inject(Fault.networkIsolation());
                    runner.clear(Fault.networkIsolation());
                }
                runner.inject(Fault.networkIsolation());
            });
            injector.start();

            runner.stop();
            injector.join();

            assertThat(network.snapshot()).as("round %d", round).isEmpty();
        }
    }

    @Test
    void switchingTheProtocolReplacesTheClientAndKeepsTheFlags() {
        var runner = runner();
        runner.start(services, resources);
        runner.inject(Fault.networkIsolation());
        runner.leave();
        var first = clients.get(0);

        runner.switchProtocol(Protocol.CLASSIC);

        assertThat(first.closed()).isTrue();
        assertThat(clients).hasSize(2);
        assertThat(runner.protocol()).isEqualTo(Protocol.CLASSIC);
        assertThat(settings.get(1)).containsEntry(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "classic");
        assertThat(runner.subscribed()).isFalse();
        assertThat(runner.faults()).containsExactly(Fault.networkIsolation());
        assertThat(network.modeOf("g-consumer-0")).isEqualTo(NetworkFaults.Mode.BLACKHOLE);
        assertThat(threads()).containsExactly("kaos-g-consumer-0");

        runner.switchProtocol(Protocol.CLASSIC);

        assertThat(clients).hasSize(2);
    }

    @Test
    void switchingTheProtocolBeforeTheStartOnlyRecordsIt() {
        var runner = runner();

        runner.switchProtocol(Protocol.CLASSIC);

        assertThat(runner.protocol()).isEqualTo(Protocol.CLASSIC);
        assertThat(clients).isEmpty();

        runner.start(services, resources);

        assertThat(settings.get(0)).containsEntry(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "classic");
    }

    @Test
    void switchingTheProtocolRefusesTheGuardedThread() {
        var runner = runner();
        guard.set(() -> {
            throw new SimulationStateException("listener");
        });

        assertThatThrownBy(() -> runner.switchProtocol(Protocol.CLASSIC)).isInstanceOf(SimulationStateException.class);
    }

    @Test
    void aSwitchWhoseClientCannotBeCreatedFailsTheConsumer() {
        var failing = new ConsumerRunner.Services(
            given -> {
                if ("classic".equals(given.get(ConsumerConfig.GROUP_PROTOCOL_CONFIG))) {
                    throw new org.apache.kafka.common.KafkaException("no classic");
                }
                return newClient(given);
            },
            threads,
            network
        );
        var runner = runner();
        runner.start(failing, resources);

        assertThatThrownBy(() -> runner.switchProtocol(Protocol.CLASSIC))
            .isInstanceOf(SimulationException.class)
            .hasMessageContaining("no classic");
        assertThat(runner.localState().failure()).isEqualTo("KafkaException: no classic");
        assertThat(runner.protocol()).isEqualTo(Protocol.CLASSIC);
        assertThat(clients.get(0).closed()).isTrue();
    }

    @Test
    void theClientPropertiesFollowTheSettingsAndTheProtocol() {
        var settings = new ConsumerSettings(Protocol.CLASSIC, "roundrobin", true, "rack-a", 0, 1000, 7000);

        var none = ConsumerRunner.ClassicTimeouts.NONE;
        var classic = ConsumerRunner.clientProperties("g", "g-consumer-0", settings, none, Protocol.CLASSIC);

        assertThat(classic)
            .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "g")
            .containsEntry(ConsumerConfig.CLIENT_ID_CONFIG, "g-consumer-0")
            .containsEntry(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "classic")
            .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 7000)
            .containsEntry(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, "g-consumer-0")
            .containsEntry(ConsumerConfig.CLIENT_RACK_CONFIG, "rack-a")
            .containsEntry(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                "org.apache.kafka.clients.consumer.RoundRobinAssignor"
            )
            .doesNotContainKey(ConsumerConfig.GROUP_REMOTE_ASSIGNOR_CONFIG)
            .doesNotContainKey(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG)
            .doesNotContainKey(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG);

        // The classic assignor does not exist for the consumer protocol, whose default applies.
        var switched = ConsumerRunner.clientProperties("g", "g-consumer-0", settings, none, Protocol.CONSUMER);

        assertThat(switched)
            .containsEntry(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "consumer")
            .doesNotContainKey(ConsumerConfig.GROUP_REMOTE_ASSIGNOR_CONFIG)
            .doesNotContainKey(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG);

        var dynamic = new ConsumerSettings(Protocol.CONSUMER, "range", false, "", 0, 1000, 300_000);
        assertThat(ConsumerRunner.clientProperties("g", "c", dynamic, none, Protocol.CONSUMER))
            .containsEntry(ConsumerConfig.GROUP_REMOTE_ASSIGNOR_CONFIG, "range")
            .doesNotContainKey(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG)
            .doesNotContainKey(ConsumerConfig.CLIENT_RACK_CONFIG);
        // A name both protocols list is kept across a switch.
        assertThat(ConsumerRunner.clientProperties("g", "c", dynamic, none, Protocol.CLASSIC))
            .containsEntry(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, "org.apache.kafka.clients.consumer.RangeAssignor"
            );
        assertThat(ConsumerRunner.assignorFor(dynamic, Protocol.CLASSIC)).isEqualTo("range");
        assertThat(ConsumerRunner.assignorFor(settings, Protocol.CONSUMER)).isEmpty();
    }

    @Test
    void onlyAClassicClientGetsTheTimeoutsOfTheGroup() {
        var settings = new ConsumerSettings(Protocol.CLASSIC, "", false, "", 0, 20, 300_000);
        var timeouts = new ConsumerRunner.ClassicTimeouts(OptionalInt.of(10_000), OptionalInt.of(1_000));

        assertThat(ConsumerRunner.clientProperties("g", "c", settings, timeouts, Protocol.CLASSIC))
            .containsEntry(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10_000)
            .containsEntry(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1_000);
        assertThat(ConsumerRunner.clientProperties("g", "c", settings, timeouts, Protocol.CONSUMER))
            .doesNotContainKey(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG)
            .doesNotContainKey(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG);
        // Only the setting that is explicit is set; the client keeps its default for the other.
        var sessionOnly = new ConsumerRunner.ClassicTimeouts(OptionalInt.of(10_000), OptionalInt.empty());
        assertThat(ConsumerRunner.clientProperties("g", "c", settings, sessionOnly, Protocol.CLASSIC))
            .containsEntry(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10_000)
            .doesNotContainKey(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG);
    }

    @Test
    void theClassicTimeoutsAreTheExplicitSettingsOfTheGroup() {
        var defaults = GroupSpec.of("g", g -> g.subscribe("orders"));
        var explicit = GroupSpec.of(
            "g",
            g -> g.subscribe("orders").sessionTimeout(Duration.ofSeconds(10)).heartbeatInterval(Duration.ofSeconds(3))
        );

        assertThat(ConsumerRunner.ClassicTimeouts.of(defaults)).isEqualTo(ConsumerRunner.ClassicTimeouts.NONE);
        assertThat(ConsumerRunner.ClassicTimeouts.of(explicit))
            .isEqualTo(new ConsumerRunner.ClassicTimeouts(OptionalInt.of(10_000), OptionalInt.of(3_000)));
    }

    @Test
    void aClassicConsumerWhoseHeartbeatIsNotBelowItsSessionTimeoutIsFailed() throws InterruptedException {
        var classic = new ConsumerSettings(Protocol.CLASSIC, "", false, "", 0, 20, 300_000);
        // The heartbeat interval keeps the client default of 3000 ms.
        var runner = runner(classic, new ConsumerRunner.ClassicTimeouts(OptionalInt.of(2_000), OptionalInt.empty()));

        runner.start(services, resources);

        assertThat(clients).isEmpty();
        assertThat(threads()).isEmpty();
        var state = runner.localState();
        assertThat(state.failure()).isEqualTo(
            "The heartbeat interval of a classic consumer, 3000 ms (the client default), must be lower than its "
                + "session timeout, 2000 ms"
        );
        assertThat(StatusDeriver.status(state, ConsumerStatus.JOINING, Set.of(), Set.of()))
            .isEqualTo(ConsumerStatus.FAILED);

        // The consumer protocol takes the timeouts from the group configs, so its client starts.
        runner.switchProtocol(Protocol.CONSUMER);

        assertThat(clients).hasSize(1);
        assertThat(runner.localState().failed()).isFalse();

        runner.stop();
    }

    @Test
    void anInvalidSessionTimeoutIsUnrecoverable() {
        assertThat(ConsumerRunner.isUnrecoverable(new InvalidSessionTimeoutException("out of bounds"))).isTrue();
    }

    @Test
    void theLocalStateMatchesAMemberByClientId() {
        var runner = runner();
        runner.start(services, resources);
        await().atMost(TIMEOUT).until(() -> !runner.localState().localAssignment().isEmpty());
        var member = new AdminView.Member("g-consumer-0", "m1", "", 3, Set.of(ORDERS_0_REF), Optional.empty());

        var state = runner.localState();

        assertThat(state.clientId()).isEqualTo(member.clientId());
        assertThat(state.localAssignment()).containsExactly(ORDERS_0_REF);
        assertThat(changes.get()).isPositive();
    }

    private List<String> threads() {
        var names = new ArrayList<String>();
        for (var thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("kaos-g-")) {
                names.add(thread.getName());
            }
        }
        return names;
    }
}
